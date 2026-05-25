package io.github.hpkaiq.dorisjdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisDatabaseMetaDataTest {

    @AfterEach
    void resetMetadataCaches() {
        DorisDatabaseMetaData.clearColumnCache();
        DorisDatabaseMetaData.resetColumnCacheConfig();
        System.clearProperty("doris.jdbc.database.product.name");
        System.clearProperty("doris.jdbc.log.enabled");
        System.clearProperty("doris.jdbc.log.level");
        System.clearProperty("doris.jdbc.log.path");
    }

    @Test
    void getColumnsWritesInfoTraceLogWhenEnabled(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("doris-wrapper.log");
        String originalLogPath = System.getProperty("doris.jdbc.log.path");
        String originalLogLevel = System.getProperty("doris.jdbc.log.level");
        System.setProperty("doris.jdbc.log.path", logFile.toString());
        System.setProperty("doris.jdbc.log.level", "INFO");
        try {
            AtomicReference<String> executedSql = new AtomicReference<>();
            DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(
                    Map.of(
                            "select * from `test_catalog`.`analytics`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                            createSelectMetadataResult(
                                    new SelectColumn("vehicle_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNoNulls)
                            ),
                            "show full columns from `test_catalog`.`analytics`.`dwd_vehicle_super_data_latest_row_sip`",
                            createShowFullColumnsResult()
                    ),
                    executedSql
            )));
            DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

            metaData.getColumns(
                    "test_catalog",
                    "test_catalog.analytics",
                    "dwd\\_vehicle\\_super\\_data\\_latest\\_row\\_sip",
                    null
            );

            assertTrue(Files.exists(logFile));
            String content = Files.readString(logFile);
            assertTrue(content.contains("getColumns"));
            assertTrue(content.contains("show full columns from `test_catalog`.`analytics`.`dwd_vehicle_super_data_latest_row_sip`"));
        } finally {
            if (originalLogPath == null) {
                System.clearProperty("doris.jdbc.log.path");
            } else {
                System.setProperty("doris.jdbc.log.path", originalLogPath);
            }
            if (originalLogLevel == null) {
                System.clearProperty("doris.jdbc.log.level");
            } else {
                System.setProperty("doris.jdbc.log.level", originalLogLevel);
            }
        }
    }

    @Test
    void getColumnsDoesNotWriteInfoTraceLogByDefault(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("doris-wrapper.log");
        System.setProperty("doris.jdbc.log.path", logFile.toString());
        AtomicReference<String> executedSql = new AtomicReference<>();
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(
                Map.of(
                        "select * from `test_catalog`.`analytics`.`vehicle` limit 0",
                        createSelectMetadataResult(new SelectColumn("vehicle_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNoNulls)),
                        "show full columns from `test_catalog`.`analytics`.`vehicle`",
                        createShowFullColumnsResult()
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        metaData.getColumns("test_catalog", "test_catalog.analytics", "vehicle", null);

        assertFalse(Files.exists(logFile));
    }

    @Test
    void errorsAreLoggedByDefault(@TempDir Path tempDir) {
        Path logFile = tempDir.resolve("doris-wrapper.log");
        System.setProperty("doris.jdbc.log.path", logFile.toString());
        SQLException failure = new SQLException("synthetic failure");

        DorisTraceLogger.logError("DorisTraceLoggerTest", "operation", failure);

        assertTrue(Files.exists(logFile));
    }

    @Test
    void getColumnsSupportsWildcardTablePatterns() throws Exception {
        AtomicReference<String> lastExecutedSql = new AtomicReference<>();
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(
                Map.of(
                        "SHOW FULL TABLES FROM `internal`.`test`",
                        createShowFullTablesResult(
                                "dwd_vehicle_super_data_latest_row_sip",
                                "dwd_vehicle_super_data_latest_row_detail_sip"
                        ),
                        "select * from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                        createSelectMetadataResult(
                                new SelectColumn("vehicle_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNoNulls)
                        ),
                        "show full columns from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip`",
                        createShowFullColumnsResult("vehicle_id", "bigint", "NO", "vehicle id"),
                        "select * from `internal`.`test`.`dwd_vehicle_super_data_latest_row_detail_sip` limit 0",
                        createSelectMetadataResult(
                                new SelectColumn("detail_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNullable)
                        ),
                        "show full columns from `internal`.`test`.`dwd_vehicle_super_data_latest_row_detail_sip`",
                        createShowFullColumnsResult("detail_id", "bigint", "YES", "detail id")
                ),
                lastExecutedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet columns = metaData.getColumns("internal", "test", "%", "%");

        assertTrue(columns.next());
        assertAll(
                () -> assertEquals("dwd_vehicle_super_data_latest_row_sip", columns.getString("TABLE_NAME")),
                () -> assertEquals("vehicle_id", columns.getString("COLUMN_NAME")),
                () -> assertEquals(1, columns.getInt("ORDINAL_POSITION"))
        );
        assertTrue(columns.next());
        assertAll(
                () -> assertEquals("dwd_vehicle_super_data_latest_row_detail_sip", columns.getString("TABLE_NAME")),
                () -> assertEquals("detail_id", columns.getString("COLUMN_NAME")),
                () -> assertEquals(1, columns.getInt("ORDINAL_POSITION"))
        );
    }

    @Test
    void getColumnsReturnsOnlySelectableColumnsWhenShowFullColumnsContainsHiddenColumns() throws Exception {
        AtomicReference<String> executedSql = new AtomicReference<>();
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(
                Map.of(
                        "select * from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                        createSelectMetadataResult(
                                new SelectColumn("vehicle_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNoNulls),
                                new SelectColumn("event_time", Types.TIMESTAMP, "DATETIME", 19, 0, ResultSetMetaData.columnNullable)
                        ),
                        "show full columns from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip`",
                        createShowFullColumnsResult(
                                new ShowColumn("vehicle_id", "bigint", "NO", "vehicle id"),
                                new ShowColumn("event_time", "datetime", "YES", "event time"),
                                new ShowColumn("__DORIS_DELETE_SIGN__", "tinyint", "NO", "hidden sign")
                        )
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet columns = metaData.getColumns("internal", "test", "dwd\\_vehicle\\_super\\_data\\_latest\\_row\\_sip", "%");

        assertTrue(columns.next());
        assertAll(
                () -> assertEquals("vehicle_id", columns.getString("COLUMN_NAME")),
                () -> assertEquals(1, columns.getInt("ORDINAL_POSITION"))
        );
        assertTrue(columns.next());
        assertAll(
                () -> assertEquals("event_time", columns.getString("COLUMN_NAME")),
                () -> assertEquals(2, columns.getInt("ORDINAL_POSITION"))
        );
        assertTrue(!columns.next());
    }

    @Test
    void getColumnsUsesCacheUntilTtlExpires() throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        DorisDatabaseMetaData.setColumnCacheTtlMillis(100L);
        DorisDatabaseMetaData.setCacheTimeProvider(now::get);

        List<String> executedSql = new ArrayList<>();
        DorisConnection connection = new DorisConnection(connectionProxy(recordingQueryStatementProxy(
                Map.of(
                        "SHOW FULL TABLES FROM `internal`.`test`",
                        createShowFullTablesResult("dwd_vehicle_super_data_latest_row_sip"),
                        "select * from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                        createSelectMetadataResult(
                                new SelectColumn("vehicle_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNoNulls)
                        ),
                        "show full columns from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip`",
                        createShowFullColumnsResult("vehicle_id", "bigint", "NO", "vehicle id")
                ),
                executedSql
        )));

        DatabaseMetaData firstMeta = connection.getMetaData();
        firstMeta.getColumns("internal", "test", "%", "%");

        DatabaseMetaData secondMeta = connection.getMetaData();
        secondMeta.getColumns("internal", "test", "dwd\\_vehicle\\_super\\_data\\_latest\\_row\\_sip", "%");

        now.addAndGet(101L);
        DatabaseMetaData thirdMeta = connection.getMetaData();
        thirdMeta.getColumns("internal", "test", "dwd\\_vehicle\\_super\\_data\\_latest\\_row\\_sip", "%");

        assertEquals(
                List.of(
                        "SHOW FULL TABLES FROM `internal`.`test`",
                        "show full columns from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip`",
                        "select * from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                        "show full columns from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip`",
                        "select * from `internal`.`test`.`dwd_vehicle_super_data_latest_row_sip` limit 0"
                ),
                executedSql
        );
    }

    @Test
    void getColumnsReturnsJdbcCompliantMetadataShape() throws Exception {
        AtomicReference<String> executedSql = new AtomicReference<>();
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(
                Map.of(
                        "select * from `test_catalog`.`analytics`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                        createSelectMetadataResult(
                                new SelectColumn("vehicle_id", Types.BIGINT, "BIGINT", 19, 0, ResultSetMetaData.columnNoNulls)
                        ),
                        "show full columns from `test_catalog`.`analytics`.`dwd_vehicle_super_data_latest_row_sip`",
                        createShowFullColumnsResult()
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet columns = metaData.getColumns(
                "test_catalog",
                "test_catalog.analytics",
                "dwd\\_vehicle\\_super\\_data\\_latest\\_row\\_sip",
                null
        );

        ResultSetMetaData resultSetMetaData = columns.getMetaData();
        assertAll(
                () -> assertEquals(
                        "select * from `test_catalog`.`analytics`.`dwd_vehicle_super_data_latest_row_sip` limit 0",
                        executedSql.get()
                ),
                () -> assertEquals(24, resultSetMetaData.getColumnCount()),
                () -> assertEquals("TABLE_CAT", resultSetMetaData.getColumnName(1)),
                () -> assertEquals("TABLE_CAT", resultSetMetaData.getColumnLabel(1)),
                () -> assertEquals("TABLE_SCHEM", resultSetMetaData.getColumnName(2)),
                () -> assertEquals("TABLE_SCHEM", resultSetMetaData.getColumnLabel(2)),
                () -> assertEquals("TABLE_NAME", resultSetMetaData.getColumnName(3)),
                () -> assertEquals("TABLE_NAME", resultSetMetaData.getColumnLabel(3)),
                () -> assertEquals("COLUMN_NAME", resultSetMetaData.getColumnName(4)),
                () -> assertEquals("COLUMN_NAME", resultSetMetaData.getColumnLabel(4)),
                () -> assertEquals("DATA_TYPE", resultSetMetaData.getColumnName(5)),
                () -> assertEquals("NULLABLE", resultSetMetaData.getColumnName(11)),
                () -> assertEquals("IS_NULLABLE", resultSetMetaData.getColumnName(18)),
                () -> assertEquals("IS_AUTOINCREMENT", resultSetMetaData.getColumnName(23)),
                () -> assertEquals("IS_GENERATEDCOLUMN", resultSetMetaData.getColumnName(24)),
                () -> assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(5)),
                () -> assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(11)),
                () -> assertEquals(Types.VARCHAR, resultSetMetaData.getColumnType(23))
        );

        assertTrue(columns.next());
        assertAll(
                () -> assertEquals("test_catalog", columns.getString("TABLE_CAT")),
                () -> assertEquals("analytics", columns.getString("TABLE_SCHEM")),
                () -> assertEquals("dwd_vehicle_super_data_latest_row_sip", columns.getString("TABLE_NAME")),
                () -> assertEquals("vehicle_id", columns.getString("COLUMN_NAME")),
                () -> assertEquals(Types.BIGINT, columns.getInt("DATA_TYPE")),
                () -> assertEquals("bigint", columns.getString("TYPE_NAME")),
                () -> assertEquals(19, columns.getInt("COLUMN_SIZE")),
                () -> assertEquals(10, columns.getInt("NUM_PREC_RADIX")),
                () -> assertEquals(DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE")),
                () -> assertEquals("NO", columns.getString("IS_NULLABLE")),
                () -> assertEquals("NO", columns.getString("IS_AUTOINCREMENT")),
                () -> assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"))
        );
    }

    @Test
    void getPrimaryKeysAndIndexInfoUseDorisMetadataInsteadOfDelegate() throws Exception {
        List<String> executedSql = new ArrayList<>();
        DorisConnection connection = new DorisConnection(connectionProxy(indexStatementProxy(createShowIndexResult(), executedSql)));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet primaryKeys = metaData.getPrimaryKeys(
                "test_catalog",
                "analytics",
                "dwd_vehicle_super_data_latest_row_sip"
        );
        assertTrue(primaryKeys.next());
        assertAll(
                () -> assertEquals("test_catalog", primaryKeys.getString("TABLE_CAT")),
                () -> assertEquals("analytics", primaryKeys.getString("TABLE_SCHEM")),
                () -> assertEquals("dwd_vehicle_super_data_latest_row_sip", primaryKeys.getString("TABLE_NAME")),
                () -> assertEquals("vehicle_id", primaryKeys.getString("COLUMN_NAME")),
                () -> assertEquals(1, primaryKeys.getShort("KEY_SEQ")),
                () -> assertEquals("PRIMARY", primaryKeys.getString("PK_NAME"))
        );

        ResultSet indexInfo = metaData.getIndexInfo(
                "test_catalog",
                "analytics",
                "dwd_vehicle_super_data_latest_row_sip",
                false,
                false
        );
        assertTrue(indexInfo.next());
        assertAll(
                () -> assertEquals("test_catalog", indexInfo.getString("TABLE_CAT")),
                () -> assertEquals("analytics", indexInfo.getString("TABLE_SCHEM")),
                () -> assertEquals("dwd_vehicle_super_data_latest_row_sip", indexInfo.getString("TABLE_NAME")),
                () -> assertEquals("PRIMARY", indexInfo.getString("INDEX_NAME")),
                () -> assertEquals("vehicle_id", indexInfo.getString("COLUMN_NAME")),
                () -> assertEquals(DatabaseMetaData.tableIndexOther, indexInfo.getShort("TYPE")),
                () -> assertEquals(1, indexInfo.getShort("ORDINAL_POSITION")),
                () -> assertEquals("A", indexInfo.getString("ASC_OR_DESC")),
                () -> assertEquals(128L, indexInfo.getLong("CARDINALITY"))
        );

        assertEquals(
                List.of(
                        "EXECUTE:SWITCH `test_catalog`",
                        "QUERY:SHOW INDEX FROM `analytics`.`dwd_vehicle_super_data_latest_row_sip`",
                        "EXECUTE:SWITCH `internal`",
                        "EXECUTE:SWITCH `test_catalog`",
                        "QUERY:SHOW INDEX FROM `analytics`.`dwd_vehicle_super_data_latest_row_sip`",
                        "EXECUTE:SWITCH `internal`"
                ),
                executedSql
        );
    }

    @Test
    void getIndexInfoRetriesMysqlShowIndexSyntaxWhenQualifiedSyntaxReturnsNoRows() throws Exception {
        List<String> executedSql = new ArrayList<>();
        DorisConnection connection = new DorisConnection(connectionProxy(metadataStatementProxy(
                Map.of(
                        "SHOW INDEX FROM `iot-business`.`charge_price_tenant`",
                        createEmptyShowIndexResult(),
                        "SHOW INDEX FROM `charge_price_tenant` FROM `iot-business`",
                        createShowIndexResult()
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet indexInfo = metaData.getIndexInfo(
                "mysql_catalog",
                "iot-business",
                "charge_price_tenant",
                false,
                true
        );

        assertTrue(indexInfo.next());
        assertAll(
                () -> assertEquals("PRIMARY", indexInfo.getString("INDEX_NAME")),
                () -> assertEquals("vehicle_id", indexInfo.getString("COLUMN_NAME")),
                () -> assertEquals(List.of(
                        "EXECUTE:SWITCH `mysql_catalog`",
                        "QUERY:SHOW INDEX FROM `iot-business`.`charge_price_tenant`",
                        "QUERY:SHOW INDEX FROM `charge_price_tenant` FROM `iot-business`",
                        "EXECUTE:SWITCH `internal`"
                ), executedSql)
        );
    }

    @Test
    void getIndexInfoParsesShowCreateTableWhenShowIndexReturnsNoRows() throws Exception {
        List<String> executedSql = new ArrayList<>();
        DorisConnection connection = new DorisConnection(connectionProxy(metadataStatementProxy(
                Map.of(
                        "SHOW INDEX FROM `iot-business`.`charge_price_tenant`",
                        createEmptyShowIndexResult(),
                        "SHOW INDEX FROM `charge_price_tenant` FROM `iot-business`",
                        createEmptyShowIndexResult(),
                        "SHOW CREATE TABLE `iot-business`.`charge_price_tenant`",
                        createShowCreateTableResult("""
                                CREATE TABLE `charge_price_tenant` (
                                  `tenant_id` bigint NOT NULL COMMENT 'tenant id',
                                  `price_id` bigint NOT NULL,
                                  PRIMARY KEY (`tenant_id`),
                                  KEY `idx_price_id` (`price_id`)
                                ) ENGINE=InnoDB COMMENT='charge price tenant'
                                """)
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet indexInfo = metaData.getIndexInfo(
                "mysql_catalog",
                "iot-business",
                "charge_price_tenant",
                false,
                true
        );

        assertTrue(indexInfo.next());
        assertAll(
                () -> assertEquals("PRIMARY", indexInfo.getString("INDEX_NAME")),
                () -> assertEquals("tenant_id", indexInfo.getString("COLUMN_NAME")),
                () -> assertEquals(false, indexInfo.getBoolean("NON_UNIQUE"))
        );
        assertTrue(indexInfo.next());
        assertAll(
                () -> assertEquals("idx_price_id", indexInfo.getString("INDEX_NAME")),
                () -> assertEquals("price_id", indexInfo.getString("COLUMN_NAME")),
                () -> assertEquals(true, indexInfo.getBoolean("NON_UNIQUE"))
        );
        assertEquals(List.of(
                "EXECUTE:SWITCH `mysql_catalog`",
                "QUERY:SHOW INDEX FROM `iot-business`.`charge_price_tenant`",
                "QUERY:SHOW INDEX FROM `charge_price_tenant` FROM `iot-business`",
                "QUERY:SHOW CREATE TABLE `iot-business`.`charge_price_tenant`",
                "EXECUTE:SWITCH `internal`"
        ), executedSql);
    }

    @Test
    void reportsDorisCatalogAndSchemaSupportForSqlResolution() throws Exception {
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(Map.of(), new AtomicReference<>())));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        assertAll(
                () -> assertTrue(metaData.supportsSchemasInDataManipulation()),
                () -> assertTrue(metaData.supportsCatalogsInDataManipulation()),
                () -> assertTrue(metaData.supportsSchemasInTableDefinitions()),
                () -> assertTrue(metaData.supportsCatalogsInTableDefinitions())
        );
    }

    @Test
    void reportsDorisProductNameByDefaultForJetBrainsCatalogModel() throws Exception {
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(Map.of(), new AtomicReference<>())));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        assertEquals("Doris", metaData.getDatabaseProductName());
    }

    @Test
    void allowsOverridingDatabaseProductName() throws Exception {
        System.setProperty("doris.jdbc.database.product.name", "MySQL");
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(Map.of(), new AtomicReference<>())));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        assertEquals("MySQL", metaData.getDatabaseProductName());
    }

    @Test
    void getTableTypesReportsDorisTypes() throws Exception {
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(Map.of(), new AtomicReference<>())));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet tableTypes = metaData.getTableTypes();

        assertTrue(tableTypes.next());
        assertEquals("TABLE", tableTypes.getString("TABLE_TYPE"));
        assertTrue(tableTypes.next());
        assertEquals("VIEW", tableTypes.getString("TABLE_TYPE"));
        assertTrue(tableTypes.next());
        assertEquals("MATERIALIZED VIEW", tableTypes.getString("TABLE_TYPE"));
        assertTrue(!tableTypes.next());
    }

    @Test
    void getTablesMapsDorisTypesAndHonorsPatterns() throws Exception {
        AtomicReference<String> executedSql = new AtomicReference<>();
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(
                Map.of(
                        "SHOW FULL TABLES FROM `internal`.`test`",
                        createShowFullTablesResult(
                                new TableRow("ods_vehicle", "BASE TABLE"),
                                new TableRow("v_vehicle", "VIEW"),
                                new TableRow("mv_vehicle", "MATERIALIZED VIEW")
                        ),
                        "SHOW TABLE STATUS FROM `internal`.`test`",
                        createShowTableStatusResult(
                                new TableComment("ods_vehicle", "ods vehicle table"),
                                new TableComment("v_vehicle", "vehicle view"),
                                new TableComment("mv_vehicle", "vehicle materialized view")
                        )
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet tables = metaData.getTables("internal", "test", "%vehicle", new String[]{"VIEW", "MATERIALIZED VIEW"});

        assertTrue(tables.next());
        assertAll(
                () -> assertEquals("v_vehicle", tables.getString("TABLE_NAME")),
                () -> assertEquals("VIEW", tables.getString("TABLE_TYPE")),
                () -> assertEquals("vehicle view", tables.getString("REMARKS"))
        );
        assertTrue(tables.next());
        assertAll(
                () -> assertEquals("mv_vehicle", tables.getString("TABLE_NAME")),
                () -> assertEquals("MATERIALIZED VIEW", tables.getString("TABLE_TYPE")),
                () -> assertEquals("vehicle materialized view", tables.getString("REMARKS"))
        );
        assertTrue(!tables.next());
        assertEquals("SHOW TABLE STATUS FROM `internal`.`test`", executedSql.get());
    }

    @Test
    void getTablesEnumeratesSchemasWhenSchemaPatternIsWildcard() throws Exception {
        List<String> executedSql = new ArrayList<>();
        DorisConnection connection = new DorisConnection(connectionProxy(recordingQueryStatementProxy(
                Map.of(
                        "SHOW DATABASES FROM `internal`",
                        createSchemasResult("test", "realinfo"),
                        "SHOW FULL TABLES FROM `internal`.`test`",
                        createShowFullTablesResult(new TableRow("v_vehicle", "VIEW")),
                        "SHOW TABLE STATUS FROM `internal`.`test`",
                        createShowTableStatusResult(new TableComment("v_vehicle", "vehicle view")),
                        "SHOW FULL TABLES FROM `internal`.`realinfo`",
                        createShowFullTablesResult(new TableRow("v_realinfo", "VIEW")),
                        "SHOW TABLE STATUS FROM `internal`.`realinfo`",
                        createShowTableStatusResult(new TableComment("v_realinfo", "realinfo view"))
                ),
                executedSql
        )));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        ResultSet tables = metaData.getTables("internal", "%", "v\\_%", new String[]{"VIEW"});

        assertTrue(tables.next());
        assertAll(
                () -> assertEquals("test", tables.getString("TABLE_SCHEM")),
                () -> assertEquals("v_vehicle", tables.getString("TABLE_NAME")),
                () -> assertEquals("VIEW", tables.getString("TABLE_TYPE")),
                () -> assertEquals("vehicle view", tables.getString("REMARKS"))
        );
        assertTrue(tables.next());
        assertAll(
                () -> assertEquals("realinfo", tables.getString("TABLE_SCHEM")),
                () -> assertEquals("v_realinfo", tables.getString("TABLE_NAME")),
                () -> assertEquals("VIEW", tables.getString("TABLE_TYPE")),
                () -> assertEquals("realinfo view", tables.getString("REMARKS"))
        );
        assertTrue(!tables.next());
        assertEquals(List.of(
                "SHOW DATABASES FROM `internal`",
                "SHOW FULL TABLES FROM `internal`.`test`",
                "SHOW TABLE STATUS FROM `internal`.`test`",
                "SHOW FULL TABLES FROM `internal`.`realinfo`",
                "SHOW TABLE STATUS FROM `internal`.`realinfo`"
        ), executedSql);
    }

    @Test
    void unsupportedRelationshipMetadataDoesNotDelegateToMysqlMetadata() throws Exception {
        DatabaseMetaData throwingDelegate = (DatabaseMetaData) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getImportedKeys", "getExportedKeys", "getCrossReference",
                         "getTablePrivileges", "getColumnPrivileges", "getBestRowIdentifier", "getVersionColumns" ->
                            throw new SQLException("delegate should not be called");
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(Map.of(), new AtomicReference<>())));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, throwingDelegate);

        assertAll(
                () -> assertTrue(!metaData.getImportedKeys("internal", "test", "mv_vehicle").next()),
                () -> assertTrue(!metaData.getExportedKeys("internal", "test", "mv_vehicle").next()),
                () -> assertTrue(!metaData.getCrossReference("internal", "test", "parent", "internal", "test", "child").next()),
                () -> assertTrue(!metaData.getTablePrivileges("internal", "test", "mv_vehicle").next()),
                () -> assertTrue(!metaData.getColumnPrivileges("internal", "test", "mv_vehicle", "%").next()),
                () -> assertTrue(!metaData.getBestRowIdentifier("internal", "test", "mv_vehicle", DatabaseMetaData.bestRowSession, true).next()),
                () -> assertTrue(!metaData.getVersionColumns("internal", "test", "mv_vehicle").next())
        );
    }

    @Test
    void createsMysqlDelegateWithoutDriverManagerAutoRegistration() throws Exception {
        Locale originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.CHINESE);
        try {
            Driver mysqlDriver = DorisDriver.createMysqlDriver();

            assertAll(
                () -> assertEquals("com.mysql.cj.jdbc.NonRegisteringDriver", mysqlDriver.getClass().getName()),
                () -> assertTrue(mysqlDriver != null)
            );
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void metadataAndStatementOverloadsReturnWrapperConnection() throws Exception {
        DorisConnection connection = new DorisConnection(connectionProxy(queryStatementProxy(Map.of(), new AtomicReference<>())));
        DorisDatabaseMetaData metaData = new DorisDatabaseMetaData(connection, databaseMetaDataProxy());

        assertAll(
                () -> assertEquals(connection, metaData.getConnection()),
                () -> assertEquals(connection, connection.createStatement().getConnection()),
                () -> assertEquals(connection, connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).getConnection()),
                () -> assertEquals(connection, connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT).getConnection()),
                () -> assertEquals(connection, connection.prepareStatement("select 1").getConnection()),
                () -> assertEquals(connection, connection.prepareStatement("select 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).getConnection()),
                () -> assertEquals(connection, connection.prepareStatement("select 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT).getConnection()),
                () -> assertEquals(connection, connection.prepareStatement("select 1", Statement.NO_GENERATED_KEYS).getConnection())
        );
    }

    @Test
    void rewritesIdeaDoubleQuotedIdentifiersForStatementExecution() throws Exception {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Statement delegate = (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        executedSql.set((String) args[0]);
                        return true;
                    }
                    return defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
        DorisConnection connection = new DorisConnection(connectionProxy(delegate));
        DorisStatement statement = new DorisStatement(delegate, connection);

        boolean hasResultSet = statement.execute("""
                SELECT *
                FROM "iot-business".charging_pile t
                WHERE t.name = '"iot-business"'
                """);

        assertAll(
                () -> assertTrue(hasResultSet),
                () -> assertEquals("""
                        SELECT *
                        FROM `iot-business`.charging_pile t
                        WHERE t.name = '"iot-business"'
                        """, executedSql.get())
        );
    }

    @Test
    void rewritesIdeaDoubleQuotedIdentifiersBeforePreparingStatements() throws Exception {
        AtomicReference<String> preparedSql = new AtomicReference<>();
        PreparedStatement preparedStatement = (PreparedStatement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, args) -> defaultValue(proxy, method.getName(), method.getReturnType(), args)
        );
        Connection delegate = (Connection) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        preparedSql.set((String) args[0]);
                        yield preparedStatement;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
        DorisConnection connection = new DorisConnection(delegate);

        PreparedStatement wrapped = connection.prepareStatement("""
                SELECT t.id
                FROM "iot-business".charging_pile t
                WHERE t.name = ?
                """);

        assertAll(
                () -> assertEquals(connection, wrapped.getConnection()),
                () -> assertEquals("""
                        SELECT t.id
                        FROM `iot-business`.charging_pile t
                        WHERE t.name = ?
                        """, preparedSql.get())
        );
    }

    @Test
    void retriesShowCreateViewAsMaterializedViewWhenDorisRequiresIt() throws Exception {
        List<String> executedSql = new ArrayList<>();
        ResultSet fallbackResult = createShowCreateResult();
        Statement delegate = (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        String sql = (String) args[0];
                        executedSql.add(sql);
                        if ("SHOW CREATE VIEW `internal`.`test`.`mv_vehicle`".equals(sql)) {
                            throw new SQLException("errCode = 2, detailMessage = not support async materialized view, please use `show create materialized view`");
                        }
                        if ("SHOW CREATE MATERIALIZED VIEW `internal`.`test`.`mv_vehicle`".equals(sql)) {
                            return cloneRowSet(fallbackResult);
                        }
                    }
                    return defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
        DorisConnection connection = new DorisConnection(connectionProxy(delegate));
        DorisStatement statement = new DorisStatement(delegate, connection);

        ResultSet rs = statement.executeQuery("SHOW CREATE VIEW `internal`.`test`.`mv_vehicle`");

        assertTrue(rs.next());
        assertAll(
                () -> assertEquals("mv_vehicle", rs.getString(1)),
                () -> assertEquals(List.of(
                        "SHOW CREATE VIEW `internal`.`test`.`mv_vehicle`",
                        "SHOW CREATE MATERIALIZED VIEW `internal`.`test`.`mv_vehicle`"
                ), executedSql)
        );
    }

    @Test
    void retriesShowCreateTableAsMaterializedViewWhenDorisRequiresIt() throws Exception {
        List<String> executedSql = new ArrayList<>();
        ResultSet fallbackResult = createShowCreateResult();
        Statement delegate = (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        String sql = (String) args[0];
                        executedSql.add(sql);
                        if ("SHOW CREATE TABLE `internal`.`test`.`mv_vehicle`".equals(sql)) {
                            throw new SQLException("errCode = 2, detailMessage = not support async materialized view, please use `show create materialized view`");
                        }
                        if ("SHOW CREATE MATERIALIZED VIEW `internal`.`test`.`mv_vehicle`".equals(sql)) {
                            return cloneRowSet(fallbackResult);
                        }
                    }
                    return defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
        DorisConnection connection = new DorisConnection(connectionProxy(delegate));
        DorisStatement statement = new DorisStatement(delegate, connection);

        ResultSet rs = statement.executeQuery("SHOW CREATE TABLE `internal`.`test`.`mv_vehicle`");

        assertTrue(rs.next());
        assertAll(
                () -> assertEquals("mv_vehicle", rs.getString(1)),
                () -> assertEquals(List.of(
                        "SHOW CREATE TABLE `internal`.`test`.`mv_vehicle`",
                        "SHOW CREATE MATERIALIZED VIEW `internal`.`test`.`mv_vehicle`"
                ), executedSql)
        );
    }

    @Test
    void executeRetriesShowCreateViewAsMaterializedViewWhenDorisRequiresIt() throws Exception {
        List<String> executedSql = new ArrayList<>();
        Statement delegate = (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        String sql = (String) args[0];
                        executedSql.add(sql);
                        if ("show create view `internal`.`test`.`mv_vehicle`".equals(sql)) {
                            throw new SQLException("errCode = 2, detailMessage = not support async materialized view, please use `show create materialized view`");
                        }
                        if ("SHOW CREATE MATERIALIZED VIEW `internal`.`test`.`mv_vehicle`".equals(sql)) {
                            return true;
                        }
                    }
                    return defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
        DorisConnection connection = new DorisConnection(connectionProxy(delegate));
        DorisStatement statement = new DorisStatement(delegate, connection);

        boolean hasResultSet = statement.execute("show create view `internal`.`test`.`mv_vehicle`");

        assertAll(
                () -> assertTrue(hasResultSet),
                () -> assertEquals(List.of(
                        "show create view `internal`.`test`.`mv_vehicle`",
                        "SHOW CREATE MATERIALIZED VIEW `internal`.`test`.`mv_vehicle`"
                ), executedSql)
        );
    }

    private static ResultSet createShowFullColumnsResult() throws SQLException {
        return createShowFullColumnsResult(new ShowColumn("vehicle_id", "bigint", "NO", "vehicle id"));
    }

    private static ResultSet createShowCreateResult() throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(2);
        metaData.setColumnName(1, "View");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Create View");
        metaData.setColumnType(2, Types.VARCHAR);
        rowSet.setMetaData(metaData);

        rowSet.moveToInsertRow();
        rowSet.updateString(1, "mv_vehicle");
        rowSet.updateString(2, "CREATE MATERIALIZED VIEW mv_vehicle AS SELECT 1");
        rowSet.insertRow();
        rowSet.moveToCurrentRow();
        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createShowCreateTableResult(String createSql) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(2);
        metaData.setColumnName(1, "Table");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Create Table");
        metaData.setColumnType(2, Types.VARCHAR);
        rowSet.setMetaData(metaData);

        rowSet.moveToInsertRow();
        rowSet.updateString(1, "charge_price_tenant");
        rowSet.updateString(2, createSql);
        rowSet.insertRow();
        rowSet.moveToCurrentRow();
        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createShowFullColumnsResult(String field, String type, String nullable, String comment) throws SQLException {
        return createShowFullColumnsResult(new ShowColumn(field, type, nullable, comment));
    }

    private static ResultSet createShowFullColumnsResult(ShowColumn... columns) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(5);
        metaData.setColumnName(1, "Field");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Type");
        metaData.setColumnType(2, Types.VARCHAR);
        metaData.setColumnName(3, "Null");
        metaData.setColumnType(3, Types.VARCHAR);
        metaData.setColumnName(4, "Default");
        metaData.setColumnType(4, Types.VARCHAR);
        metaData.setColumnName(5, "Comment");
        metaData.setColumnType(5, Types.VARCHAR);
        rowSet.setMetaData(metaData);

        for (ShowColumn column : columns) {
            rowSet.moveToInsertRow();
            rowSet.updateString("Field", column.field());
            rowSet.updateString("Type", column.type());
            rowSet.updateString("Null", column.nullable());
            rowSet.updateNull("Default");
            rowSet.updateString("Comment", column.comment());
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
            rowSet.last();
        }

        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createSelectMetadataResult(SelectColumn... columns) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(columns.length);
        for (int i = 0; i < columns.length; i++) {
            int index = i + 1;
            SelectColumn column = columns[i];
            metaData.setColumnName(index, column.name());
            metaData.setColumnLabel(index, column.name());
            metaData.setColumnType(index, column.jdbcType());
            metaData.setColumnTypeName(index, column.typeName());
            metaData.setPrecision(index, column.precision());
            metaData.setScale(index, column.scale());
            metaData.setNullable(index, column.nullable());
            metaData.setAutoIncrement(index, false);
            metaData.setSigned(index, true);
            metaData.setSearchable(index, true);
            metaData.setCaseSensitive(index, true);
            metaData.setCurrency(index, false);
            metaData.setCatalogName(index, "internal");
            metaData.setSchemaName(index, "test");
            metaData.setTableName(index, "mock_table");
            metaData.setColumnDisplaySize(index, Math.max(column.precision(), 1));
        }
        rowSet.setMetaData(metaData);

        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createShowFullTablesResult(String... tableNames) throws SQLException {
        TableRow[] tables = new TableRow[tableNames.length];
        for (int i = 0; i < tableNames.length; i++) {
            tables[i] = new TableRow(tableNames[i], "BASE TABLE");
        }
        return createShowFullTablesResult(tables);
    }

    private static ResultSet createShowFullTablesResult(TableRow... tables) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(2);
        metaData.setColumnName(1, "Tables_in_test");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Table_type");
        metaData.setColumnType(2, Types.VARCHAR);
        rowSet.setMetaData(metaData);

        for (TableRow table : tables) {
            rowSet.moveToInsertRow();
            rowSet.updateString(1, table.name());
            rowSet.updateString(2, table.type());
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
            rowSet.last();
        }

        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createSchemasResult(String... schemaNames) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(1);
        metaData.setColumnName(1, "Database");
        metaData.setColumnType(1, Types.VARCHAR);
        rowSet.setMetaData(metaData);

        for (String schemaName : schemaNames) {
            rowSet.moveToInsertRow();
            rowSet.updateString(1, schemaName);
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
            rowSet.last();
        }

        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createShowTableStatusResult(TableComment... comments) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(2);
        metaData.setColumnName(1, "Name");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Comment");
        metaData.setColumnType(2, Types.VARCHAR);
        rowSet.setMetaData(metaData);

        for (TableComment comment : comments) {
            rowSet.moveToInsertRow();
            rowSet.updateString("Name", comment.name());
            rowSet.updateString("Comment", comment.comment());
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
            rowSet.last();
        }

        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createShowIndexResult() throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(7);
        metaData.setColumnName(1, "Table");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Non_unique");
        metaData.setColumnType(2, Types.INTEGER);
        metaData.setColumnName(3, "Key_name");
        metaData.setColumnType(3, Types.VARCHAR);
        metaData.setColumnName(4, "Seq_in_index");
        metaData.setColumnType(4, Types.INTEGER);
        metaData.setColumnName(5, "Column_name");
        metaData.setColumnType(5, Types.VARCHAR);
        metaData.setColumnName(6, "Collation");
        metaData.setColumnType(6, Types.VARCHAR);
        metaData.setColumnName(7, "Cardinality");
        metaData.setColumnType(7, Types.BIGINT);
        rowSet.setMetaData(metaData);

        rowSet.moveToInsertRow();
        rowSet.updateString("Table", "dwd_vehicle_super_data_latest_row_sip");
        rowSet.updateInt("Non_unique", 0);
        rowSet.updateString("Key_name", "PRIMARY");
        rowSet.updateInt("Seq_in_index", 1);
        rowSet.updateString("Column_name", "vehicle_id");
        rowSet.updateString("Collation", "A");
        rowSet.updateLong("Cardinality", 128L);
        rowSet.insertRow();
        rowSet.moveToCurrentRow();

        rowSet.moveToInsertRow();
        rowSet.updateString("Table", "dwd_vehicle_super_data_latest_row_sip");
        rowSet.updateInt("Non_unique", 1);
        rowSet.updateString("Key_name", "idx_vehicle_ts");
        rowSet.updateInt("Seq_in_index", 1);
        rowSet.updateString("Column_name", "event_time");
        rowSet.updateString("Collation", "A");
        rowSet.updateLong("Cardinality", 256L);
        rowSet.insertRow();
        rowSet.moveToCurrentRow();

        rowSet.beforeFirst();
        return rowSet;
    }

    private static ResultSet createEmptyShowIndexResult() throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
        metaData.setColumnCount(7);
        metaData.setColumnName(1, "Table");
        metaData.setColumnType(1, Types.VARCHAR);
        metaData.setColumnName(2, "Non_unique");
        metaData.setColumnType(2, Types.INTEGER);
        metaData.setColumnName(3, "Key_name");
        metaData.setColumnType(3, Types.VARCHAR);
        metaData.setColumnName(4, "Seq_in_index");
        metaData.setColumnType(4, Types.INTEGER);
        metaData.setColumnName(5, "Column_name");
        metaData.setColumnType(5, Types.VARCHAR);
        metaData.setColumnName(6, "Collation");
        metaData.setColumnType(6, Types.VARCHAR);
        metaData.setColumnName(7, "Cardinality");
        metaData.setColumnType(7, Types.BIGINT);
        rowSet.setMetaData(metaData);
        rowSet.beforeFirst();
        return rowSet;
    }

    private static Connection connectionProxy(Statement statement) {
        return (Connection) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "isClosed" -> false;
                    case "close" -> null;
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
    }

    private static Statement queryStatementProxy(Map<String, ResultSet> resultsBySql, AtomicReference<String> executedSql) {
        return (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        String sql = (String) args[0];
                        executedSql.set(sql);
                        ResultSet resultSet = resultsBySql.get(sql);
                        yield resultSet == null ? null : cloneRowSet(resultSet);
                    }
                    case "close" -> null;
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
    }

    private static Statement recordingQueryStatementProxy(Map<String, ResultSet> resultsBySql, List<String> executedSql) {
        return (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        String sql = (String) args[0];
                        executedSql.add(sql);
                        ResultSet resultSet = resultsBySql.get(sql);
                        yield resultSet == null ? null : cloneRowSet(resultSet);
                    }
                    case "close" -> null;
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
    }

    private static Statement indexStatementProxy(ResultSet showIndexResult, List<String> executedSql) {
        return (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        executedSql.add("EXECUTE:" + args[0]);
                        yield false;
                    }
                    case "executeQuery" -> {
                        String sql = (String) args[0];
                        executedSql.add("QUERY:" + sql);
                        yield cloneRowSet(showIndexResult);
                    }
                    case "close" -> null;
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
    }

    private static Statement metadataStatementProxy(Map<String, ResultSet> resultsBySql, List<String> executedSql) {
        return (Statement) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        executedSql.add("EXECUTE:" + args[0]);
                        yield false;
                    }
                    case "executeQuery" -> {
                        String sql = (String) args[0];
                        executedSql.add("QUERY:" + sql);
                        ResultSet resultSet = resultsBySql.get(sql);
                        yield resultSet == null ? null : cloneRowSet(resultSet);
                    }
                    case "close" -> null;
                    default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
                }
        );
    }

    private static ResultSet cloneRowSet(ResultSet source) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        rowSet.populate(source);
        source.beforeFirst();
        return rowSet;
    }

    private static DatabaseMetaData databaseMetaDataProxy() {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                DorisDatabaseMetaDataTest.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> defaultValue(proxy, method.getName(), method.getReturnType(), args)
        );
    }

    private static Object defaultValue(Object proxy, String methodName, Class<?> returnType, Object[] args) {
        if ("toString".equals(methodName)) {
            return proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return proxy == args[0];
        }
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private record ShowColumn(String field, String type, String nullable, String comment) {
    }

    private record SelectColumn(String name, int jdbcType, String typeName, int precision, int scale, int nullable) {
    }

    private record TableRow(String name, String type) {
    }

    private record TableComment(String name, String comment) {
    }
}
