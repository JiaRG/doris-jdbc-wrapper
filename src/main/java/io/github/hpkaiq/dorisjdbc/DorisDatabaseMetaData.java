package io.github.hpkaiq.dorisjdbc;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class DorisDatabaseMetaData implements DatabaseMetaData {
    private static final long DEFAULT_COLUMN_CACHE_TTL_MILLIS = 30_000L;
    private static final String DATABASE_PRODUCT_NAME_PROPERTY = "doris.jdbc.database.product.name";
    private static final Pattern INDEX_NAME_PATTERN = Pattern.compile(
            "^(?:UNIQUE\\s+)?(?:KEY|INDEX)\\s+(?:`([^`]+)`|([^\\s(]+))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BACKTICK_IDENTIFIER_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Map<ColumnCacheKey, CachedTableColumns> COLUMN_CACHE = new ConcurrentHashMap<>();
    private static volatile long columnCacheTtlMillis =
            Long.getLong("doris.jdbc.columns.cache.ttl.millis", DEFAULT_COLUMN_CACHE_TTL_MILLIS);
    private static volatile LongSupplier cacheTimeProvider = System::currentTimeMillis;
    private final DorisConnection conn;
    private final DatabaseMetaData delegate;

    DorisDatabaseMetaData(DorisConnection conn, DatabaseMetaData delegate) {
        this.conn = conn;
        this.delegate = delegate;
    }

    static void clearColumnCache() {
        COLUMN_CACHE.clear();
    }

    static void clearColumnCache(long connectionId) {
        COLUMN_CACHE.entrySet().removeIf(entry -> entry.getKey().connectionId() == connectionId);
    }

    static void setColumnCacheTtlMillis(long ttlMillis) {
        columnCacheTtlMillis = ttlMillis;
    }

    static void setCacheTimeProvider(LongSupplier provider) {
        cacheTimeProvider = provider;
    }

    static void resetColumnCacheConfig() {
        columnCacheTtlMillis = Long.getLong("doris.jdbc.columns.cache.ttl.millis", DEFAULT_COLUMN_CACHE_TTL_MILLIS);
        cacheTimeProvider = System::currentTimeMillis;
    }

    private static String formatTypes(String[] types) {
        return types == null ? "null" : Arrays.toString(types);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta("TABLE_CAT"));

        int returnedRows = 0;
        try (Statement st = conn.createStatement();
             ResultSet showCatalogs = st.executeQuery("SHOW CATALOGS")) {
            DorisTraceLogger.logSql("DorisDatabaseMetaData", "getCatalogs", "SHOW CATALOGS");
            while (showCatalogs.next()) {
                String cat = showCatalogs.getString("CatalogName");
                crs.moveToInsertRow();
                crs.updateString("TABLE_CAT", cat);
                crs.insertRow();
                crs.moveToCurrentRow();
                returnedRows++;
            }
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getCatalogs", e);
            throw e;
        }
        DorisTraceLogger.log("DorisDatabaseMetaData", "getCatalogs | returnedRows=" + returnedRows);
        crs.beforeFirst();
        return crs;
    }

    private static RowSetMetaDataImpl buildMeta(String... cols) throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(cols.length);
        for (int i = 0; i < cols.length; i++) {
            int colIndex = i + 1;
            meta.setColumnName(colIndex, cols[i]);
            meta.setColumnLabel(colIndex, cols[i]);
            meta.setColumnType(colIndex, java.sql.Types.VARCHAR); // 默认给 VARCHAR，避免 NPE
            meta.setNullable(colIndex, ResultSetMetaData.columnNullable);
        }
        return meta;
    }

    private static void configureMetaColumn(RowSetMetaDataImpl meta, int index, String name, int type) throws SQLException {
        meta.setColumnName(index, name);
        meta.setColumnLabel(index, name);
        meta.setColumnType(index, type);
        meta.setNullable(index, ResultSetMetaData.columnNullable);
    }

    private static RowSetMetaDataImpl buildColumnsMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(24);
        configureMetaColumn(meta, 1, "TABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 2, "TABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 3, "TABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 4, "COLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 5, "DATA_TYPE", Types.INTEGER);
        configureMetaColumn(meta, 6, "TYPE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 7, "COLUMN_SIZE", Types.INTEGER);
        configureMetaColumn(meta, 8, "BUFFER_LENGTH", Types.INTEGER);
        configureMetaColumn(meta, 9, "DECIMAL_DIGITS", Types.INTEGER);
        configureMetaColumn(meta, 10, "NUM_PREC_RADIX", Types.INTEGER);
        configureMetaColumn(meta, 11, "NULLABLE", Types.INTEGER);
        configureMetaColumn(meta, 12, "REMARKS", Types.VARCHAR);
        configureMetaColumn(meta, 13, "COLUMN_DEF", Types.VARCHAR);
        configureMetaColumn(meta, 14, "SQL_DATA_TYPE", Types.INTEGER);
        configureMetaColumn(meta, 15, "SQL_DATETIME_SUB", Types.INTEGER);
        configureMetaColumn(meta, 16, "CHAR_OCTET_LENGTH", Types.INTEGER);
        configureMetaColumn(meta, 17, "ORDINAL_POSITION", Types.INTEGER);
        configureMetaColumn(meta, 18, "IS_NULLABLE", Types.VARCHAR);
        configureMetaColumn(meta, 19, "SCOPE_CATALOG", Types.VARCHAR);
        configureMetaColumn(meta, 20, "SCOPE_SCHEMA", Types.VARCHAR);
        configureMetaColumn(meta, 21, "SCOPE_TABLE", Types.VARCHAR);
        configureMetaColumn(meta, 22, "SOURCE_DATA_TYPE", Types.SMALLINT);
        configureMetaColumn(meta, 23, "IS_AUTOINCREMENT", Types.VARCHAR);
        configureMetaColumn(meta, 24, "IS_GENERATEDCOLUMN", Types.VARCHAR);
        return meta;
    }

    private static RowSetMetaDataImpl buildPrimaryKeysMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(6);
        configureMetaColumn(meta, 1, "TABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 2, "TABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 3, "TABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 4, "COLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 5, "KEY_SEQ", Types.SMALLINT);
        configureMetaColumn(meta, 6, "PK_NAME", Types.VARCHAR);
        return meta;
    }

    private static RowSetMetaDataImpl buildIndexInfoMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(13);
        configureMetaColumn(meta, 1, "TABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 2, "TABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 3, "TABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 4, "NON_UNIQUE", Types.BOOLEAN);
        configureMetaColumn(meta, 5, "INDEX_QUALIFIER", Types.VARCHAR);
        configureMetaColumn(meta, 6, "INDEX_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 7, "TYPE", Types.SMALLINT);
        configureMetaColumn(meta, 8, "ORDINAL_POSITION", Types.SMALLINT);
        configureMetaColumn(meta, 9, "COLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 10, "ASC_OR_DESC", Types.VARCHAR);
        configureMetaColumn(meta, 11, "CARDINALITY", Types.BIGINT);
        configureMetaColumn(meta, 12, "PAGES", Types.BIGINT);
        configureMetaColumn(meta, 13, "FILTER_CONDITION", Types.VARCHAR);
        return meta;
    }

    private static RowSetMetaDataImpl buildShowIndexMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(7);
        configureMetaColumn(meta, 1, "Table", Types.VARCHAR);
        configureMetaColumn(meta, 2, "Non_unique", Types.INTEGER);
        configureMetaColumn(meta, 3, "Key_name", Types.VARCHAR);
        configureMetaColumn(meta, 4, "Seq_in_index", Types.INTEGER);
        configureMetaColumn(meta, 5, "Column_name", Types.VARCHAR);
        configureMetaColumn(meta, 6, "Collation", Types.VARCHAR);
        configureMetaColumn(meta, 7, "Cardinality", Types.BIGINT);
        return meta;
    }

    private static RowSetMetaDataImpl buildForeignKeysMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(14);
        configureMetaColumn(meta, 1, "PKTABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 2, "PKTABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 3, "PKTABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 4, "PKCOLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 5, "FKTABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 6, "FKTABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 7, "FKTABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 8, "FKCOLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 9, "KEY_SEQ", Types.SMALLINT);
        configureMetaColumn(meta, 10, "UPDATE_RULE", Types.SMALLINT);
        configureMetaColumn(meta, 11, "DELETE_RULE", Types.SMALLINT);
        configureMetaColumn(meta, 12, "FK_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 13, "PK_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 14, "DEFERRABILITY", Types.SMALLINT);
        return meta;
    }

    private static RowSetMetaDataImpl buildTablePrivilegesMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(7);
        configureMetaColumn(meta, 1, "TABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 2, "TABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 3, "TABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 4, "GRANTOR", Types.VARCHAR);
        configureMetaColumn(meta, 5, "GRANTEE", Types.VARCHAR);
        configureMetaColumn(meta, 6, "PRIVILEGE", Types.VARCHAR);
        configureMetaColumn(meta, 7, "IS_GRANTABLE", Types.VARCHAR);
        return meta;
    }

    private static RowSetMetaDataImpl buildColumnPrivilegesMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(8);
        configureMetaColumn(meta, 1, "TABLE_CAT", Types.VARCHAR);
        configureMetaColumn(meta, 2, "TABLE_SCHEM", Types.VARCHAR);
        configureMetaColumn(meta, 3, "TABLE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 4, "COLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 5, "GRANTOR", Types.VARCHAR);
        configureMetaColumn(meta, 6, "GRANTEE", Types.VARCHAR);
        configureMetaColumn(meta, 7, "PRIVILEGE", Types.VARCHAR);
        configureMetaColumn(meta, 8, "IS_GRANTABLE", Types.VARCHAR);
        return meta;
    }

    private static RowSetMetaDataImpl buildPseudoColumnsMeta() throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(8);
        configureMetaColumn(meta, 1, "SCOPE", Types.SMALLINT);
        configureMetaColumn(meta, 2, "COLUMN_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 3, "DATA_TYPE", Types.INTEGER);
        configureMetaColumn(meta, 4, "TYPE_NAME", Types.VARCHAR);
        configureMetaColumn(meta, 5, "COLUMN_SIZE", Types.INTEGER);
        configureMetaColumn(meta, 6, "BUFFER_LENGTH", Types.INTEGER);
        configureMetaColumn(meta, 7, "DECIMAL_DIGITS", Types.SMALLINT);
        configureMetaColumn(meta, 8, "PSEUDO_COLUMN", Types.SMALLINT);
        return meta;
    }

    private static ResultSet emptyResultSet(RowSetMetaDataImpl meta) throws SQLException {
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(meta);
        crs.beforeFirst();
        return crs;
    }

    private static String normalizeTableType(String tableType) {
        if (tableType == null || tableType.isBlank()) {
            return "TABLE";
        }
        String normalized = tableType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BASE TABLE" -> "TABLE";
            case "ASYNC MATERIALIZED VIEW" -> "MATERIALIZED VIEW";
            default -> normalized;
        };
    }

    private static boolean matchesTableTypes(String tableType, String[] types) {
        if (types == null || types.length == 0) {
            return true;
        }
        for (String type : types) {
            if (normalizeTableType(type).equalsIgnoreCase(tableType)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        return pattern.replace("\\", "");
    }

    private static String normalizeSchemaPattern(String schemaPattern) {
        String normalized = normalizePattern(schemaPattern);
        if (normalized != null && normalized.contains(".")) {
            return normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        return normalized;
    }

    private static String normalizeIsNullable(String nullable) {
        if (nullable == null) {
            return "";
        }
        return switch (nullable.toUpperCase()) {
            case "YES", "NO" -> nullable.toUpperCase();
            default -> "";
        };
    }

    private static String normalizeIsNullable(int nullableType) {
        return switch (nullableType) {
            case DatabaseMetaData.columnNullable -> "YES";
            case DatabaseMetaData.columnNoNulls -> "NO";
            default -> "";
        };
    }

    private static Integer precisionRadix(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> 10;
            default -> null;
        };
    }

    private static Integer charOctetLength(int jdbcType, int columnSize) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> columnSize;
            default -> null;
        };
    }

    private static boolean hasWildcard(String pattern) {
        if (pattern == null) {
            return false;
        }
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '%' || ch == '_') {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesJdbcPattern(String value, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        StringBuilder regex = new StringBuilder("^");
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                regex.append(Pattern.quote(String.valueOf(ch)));
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        if (escaped) {
            regex.append(Pattern.quote("\\"));
        }
        regex.append('$');
        return value != null && value.matches(regex.toString());
    }

    private List<String> getMatchingTableNames(String catalog, String schema, String tablePattern) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        String sql = String.format("SHOW FULL TABLES FROM `%s`.`%s`", catalog, schema);
        DorisTraceLogger.logSql("DorisDatabaseMetaData", "getMatchingTableNames", sql);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String tableName = rs.getString(1);
                if (matchesJdbcPattern(tableName, tablePattern)) {
                    tableNames.add(tableName);
                }
            }
        }
        return tableNames;
    }

    private List<String> getMatchingSchemas(String catalog, String schemaPattern) throws SQLException {
        String normalizedPattern = normalizeSchemaPattern(schemaPattern);
        if (normalizedPattern != null && !normalizedPattern.isEmpty() && !hasWildcard(normalizedPattern)) {
            return List.of(normalizedPattern);
        }

        String effectivePattern = (normalizedPattern == null || normalizedPattern.isEmpty()) ? "%" : normalizedPattern;
        List<String> schemaNames = new ArrayList<>();
        String sql = "SHOW DATABASES FROM `" + catalog + "`";
        DorisTraceLogger.logSql("DorisDatabaseMetaData", "getMatchingSchemas", sql);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String schemaName = rs.getString(1);
                if (matchesJdbcPattern(schemaName, effectivePattern)) {
                    schemaNames.add(schemaName);
                }
            }
        }
        return schemaNames;
    }

    private Map<String, String> getTableComments(String catalog, String schema) {
        Map<String, String> comments = new LinkedHashMap<>();
        String qualifiedSql = String.format("SHOW TABLE STATUS FROM `%s`.`%s`", catalog, schema);
        if (tryPopulateTableComments(comments, qualifiedSql)) {
            return comments;
        }

        try {
            String originalCatalog = switchCatalogIfNeeded(catalog);
            try {
                String schemaSql = String.format("SHOW TABLE STATUS FROM `%s`", schema);
                tryPopulateTableComments(comments, schemaSql);
            } finally {
                restoreCatalog(originalCatalog, catalog);
            }
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getTableComments | catalog=" + catalog + " | schema=" + schema, e);
        }
        return comments;
    }

    private boolean tryPopulateTableComments(Map<String, String> comments, String sql) {
        DorisTraceLogger.logSql("DorisDatabaseMetaData", "getTableComments", sql);
        int rowCount = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs != null && rs.next()) {
                rowCount++;
                String tableName = getStringOrNull(rs, "Name", 1);
                if (tableName != null && !tableName.isBlank()) {
                    comments.put(tableName, getStringOrNull(rs, "Comment", 2));
                }
            }
            DorisTraceLogger.log(
                    "DorisDatabaseMetaData",
                    "getTableComments | sqlReturnedRows=" + rowCount
                            + " | comments=" + comments.size()
                            + " | sql=" + sql
            );
            return true;
        } catch (SQLException | RuntimeException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getTableComments | sql=" + sql, e);
            return false;
        }
    }

    private static String getStringOrNull(ResultSet rs, String columnLabel, int columnIndex) throws SQLException {
        try {
            return rs.getString(columnLabel);
        } catch (SQLException e) {
            return rs.getString(columnIndex);
        }
    }

    private CachedRowSet queryShowIndex(String method, String schema, String tableName) throws SQLException {
        List<String> queries = List.of(
                String.format("SHOW INDEX FROM `%s`.`%s`", schema, tableName),
                String.format("SHOW INDEX FROM `%s` FROM `%s`", tableName, schema)
        );
        CachedRowSet firstEmptyResult = null;
        SQLException firstFailure = null;
        for (String sql : queries) {
            DorisTraceLogger.logSql("DorisDatabaseMetaData", method, sql);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
                rows.populate(rs);
                int rowCount = countRows(rows);
                DorisTraceLogger.log("DorisDatabaseMetaData", method + " | sqlReturnedRows=" + rowCount + " | sql=" + sql);
                if (rowCount > 0) {
                    return rows;
                }
                if (firstEmptyResult == null) {
                    firstEmptyResult = rows;
                }
            } catch (SQLException e) {
                DorisTraceLogger.logError("DorisDatabaseMetaData", method + " | sql=" + sql, e);
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        CachedRowSet parsedRows = queryShowCreateTableIndexes(method, schema, tableName);
        if (countRows(parsedRows) > 0) {
            return parsedRows;
        }
        if (firstEmptyResult != null) {
            firstEmptyResult.beforeFirst();
            return firstEmptyResult;
        }
        throw firstFailure;
    }

    private CachedRowSet queryShowCreateTableIndexes(String method, String schema, String tableName) throws SQLException {
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        rows.setMetaData(buildShowIndexMeta());

        String sql = String.format("SHOW CREATE TABLE `%s`.`%s`", schema, tableName);
        DorisTraceLogger.logSql("DorisDatabaseMetaData", method, sql);
        int ddlRows = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs != null && rs.next()) {
                ddlRows++;
                String createSql = rs.getString(2);
                parseShowCreateTableIndexes(tableName, createSql, rows);
            }
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", method + " | sql=" + sql, e);
        }

        int parsedRows = countRows(rows);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                method + " | showCreateReturnedRows=" + ddlRows
                        + " | parsedIndexRows=" + parsedRows
                        + " | sql=" + sql
        );
        rows.beforeFirst();
        return rows;
    }

    private static void parseShowCreateTableIndexes(String tableName, String createSql, CachedRowSet rows) throws SQLException {
        if (createSql == null || createSql.isBlank()) {
            return;
        }
        for (String rawLine : createSql.split("\\R")) {
            String line = stripTrailingComma(rawLine.trim());
            if (line.isEmpty()) {
                continue;
            }
            line = stripConstraintPrefix(line);
            String upperLine = line.toUpperCase(Locale.ROOT);
            if (upperLine.contains("FOREIGN KEY")) {
                continue;
            }
            if (upperLine.startsWith("PRIMARY KEY")) {
                insertParsedIndexRows(tableName, "PRIMARY", false, extractIndexColumns(line), rows);
            } else if (upperLine.startsWith("UNIQUE KEY") || upperLine.startsWith("UNIQUE INDEX")) {
                String indexName = extractIndexName(line);
                if (indexName == null || indexName.isBlank()) {
                    indexName = "UNIQUE_KEY";
                }
                insertParsedIndexRows(tableName, indexName, false, extractIndexColumns(line), rows);
            } else if (upperLine.startsWith("KEY ") || upperLine.startsWith("INDEX ")) {
                String indexName = extractIndexName(line);
                if (indexName != null && !indexName.isBlank()) {
                    insertParsedIndexRows(tableName, indexName, true, extractIndexColumns(line), rows);
                }
            }
        }
    }

    private static String stripTrailingComma(String value) {
        String result = value;
        while (result.endsWith(",")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static String stripConstraintPrefix(String line) {
        String upperLine = line.toUpperCase(Locale.ROOT);
        if (!upperLine.startsWith("CONSTRAINT ")) {
            return line;
        }
        String remainder = line.substring("CONSTRAINT ".length()).trim();
        if (remainder.startsWith("`")) {
            int closingQuote = remainder.indexOf('`', 1);
            if (closingQuote >= 0) {
                return remainder.substring(closingQuote + 1).trim();
            }
        }
        int firstSpace = remainder.indexOf(' ');
        return firstSpace >= 0 ? remainder.substring(firstSpace + 1).trim() : line;
    }

    private static String extractIndexName(String line) {
        Matcher matcher = INDEX_NAME_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String quotedName = matcher.group(1);
        return quotedName != null ? quotedName : matcher.group(2);
    }

    private static List<String> extractIndexColumns(String line) {
        int openParenthesis = line.indexOf('(');
        int closeParenthesis = findMatchingParenthesis(line, openParenthesis);
        if (openParenthesis < 0 || closeParenthesis <= openParenthesis) {
            return List.of();
        }

        String columnList = line.substring(openParenthesis + 1, closeParenthesis);
        List<String> columns = new ArrayList<>();
        Matcher matcher = BACKTICK_IDENTIFIER_PATTERN.matcher(columnList);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        if (!columns.isEmpty()) {
            return columns;
        }

        for (String part : columnList.split(",")) {
            String column = part.trim();
            if (column.isEmpty() || column.startsWith("(")) {
                continue;
            }
            column = column.replaceFirst("(?i)\\s+(ASC|DESC)\\b.*$", "");
            int prefixLengthStart = column.indexOf('(');
            if (prefixLengthStart > 0) {
                column = column.substring(0, prefixLengthStart);
            }
            column = column.replace("`", "").trim();
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static int findMatchingParenthesis(String value, int openParenthesis) {
        if (openParenthesis < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = openParenthesis; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void insertParsedIndexRows(
            String tableName,
            String indexName,
            boolean nonUnique,
            List<String> columns,
            CachedRowSet rows
    ) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            rows.moveToInsertRow();
            rows.updateString("Table", tableName);
            rows.updateInt("Non_unique", nonUnique ? 1 : 0);
            rows.updateString("Key_name", indexName);
            rows.updateInt("Seq_in_index", i + 1);
            rows.updateString("Column_name", columns.get(i));
            rows.updateString("Collation", "A");
            rows.updateNull("Cardinality");
            rows.insertRow();
            rows.moveToCurrentRow();
        }
    }

    private static int countRows(ResultSet rs) throws SQLException {
        int count = 0;
        rs.beforeFirst();
        while (rs.next()) {
            count++;
        }
        rs.beforeFirst();
        return count;
    }

    private List<SelectableColumn> getSelectableColumns(String catalog, String schema, String tableName) throws SQLException {
        List<SelectableColumn> columns = new ArrayList<>();
        String sql = String.format("select * from `%s`.`%s`.`%s` limit 0", catalog, schema, tableName);
        DorisTraceLogger.logSql("DorisDatabaseMetaData", "getSelectableColumns", sql);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String columnName = metaData.getColumnLabel(i);
                if (columnName == null || columnName.isEmpty()) {
                    columnName = metaData.getColumnName(i);
                }
                columns.add(new SelectableColumn(
                        columnName,
                        metaData.getColumnType(i),
                        metaData.getColumnTypeName(i),
                        metaData.getPrecision(i),
                        metaData.getScale(i),
                        metaData.isNullable(i),
                        metaData.isAutoIncrement(i)
                ));
            }
        }
        return columns;
    }

    private Map<String, ColumnDetails> getColumnDetails(String catalog, String schema, String tableName) throws SQLException {
        Map<String, ColumnDetails> details = new LinkedHashMap<>();
        String sql = String.format("show full columns from `%s`.`%s`.`%s`", catalog, schema, tableName);
        DorisTraceLogger.logSql("DorisDatabaseMetaData", "getColumnDetails", sql);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                details.put(
                        rs.getString("Field"),
                        new ColumnDetails(
                                rs.getString("Type"),
                                rs.getString("Null"),
                                rs.getString("Default"),
                                rs.getString("Comment")
                        )
                );
            }
        }
        return details;
    }

    private static List<SelectableColumn> fallbackSelectableColumns(Map<String, ColumnDetails> detailsByName) {
        List<SelectableColumn> columns = new ArrayList<>(detailsByName.size());
        for (Map.Entry<String, ColumnDetails> entry : detailsByName.entrySet()) {
            ColumnDetails detail = entry.getValue();
            String dorisType = detail.dorisType();
            int jdbcType = TypeMapper.toJdbcType(dorisType);
            columns.add(new SelectableColumn(
                    entry.getKey(),
                    jdbcType,
                    TypeMapper.baseTypeName(dorisType),
                    TypeMapper.extractLength(dorisType),
                    TypeMapper.extractScale(dorisType),
                    TypeMapper.extractNullAble(detail.nullable()),
                    false
            ));
        }
        return columns;
    }

    private static String normalizeCachePart(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private ColumnCacheKey buildColumnCacheKey(String catalog, String schema, String tableName) {
        return new ColumnCacheKey(
                conn.getConnectionId(),
                normalizeCachePart(catalog),
                normalizeCachePart(schema),
                normalizeCachePart(tableName)
        );
    }

    private CachedTableColumns getCachedTableColumns(String catalog, String schema, String tableName) throws SQLException {
        if (columnCacheTtlMillis <= 0) {
            DorisTraceLogger.log("DorisDatabaseMetaData", "getColumns | cacheDisabled | table=" + tableName);
            return loadTableColumns(catalog, schema, tableName);
        }

        ColumnCacheKey cacheKey = buildColumnCacheKey(catalog, schema, tableName);
        long now = cacheTimeProvider.getAsLong();
        CachedTableColumns cached = COLUMN_CACHE.get(cacheKey);
        if (cached != null && now - cached.cachedAtMillis() <= columnCacheTtlMillis) {
            DorisTraceLogger.log(
                    "DorisDatabaseMetaData",
                    "getColumns | cacheHit=true | table=" + tableName
                            + " | selectableColumns=" + cached.selectableColumns()
                            + " | describedColumns=" + cached.describedColumns()
                            + " | ageMillis=" + (now - cached.cachedAtMillis())
            );
            return cached;
        }

        if (cached != null) {
            DorisTraceLogger.log(
                    "DorisDatabaseMetaData",
                    "getColumns | cacheExpired | table=" + tableName
                            + " | ageMillis=" + (now - cached.cachedAtMillis())
                            + " | ttlMillis=" + columnCacheTtlMillis
            );
        }

        CachedTableColumns loaded = loadTableColumns(catalog, schema, tableName).withCachedAt(now);
        COLUMN_CACHE.put(cacheKey, loaded);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getColumns | cacheHit=false | table=" + tableName
                        + " | selectableColumns=" + loaded.selectableColumns()
                        + " | describedColumns=" + loaded.describedColumns()
        );
        return loaded;
    }

    private CachedTableColumns loadTableColumns(String catalog, String schema, String tableName) throws SQLException {
        Map<String, ColumnDetails> detailsByName = getColumnDetails(catalog, schema, tableName);
        List<SelectableColumn> selectableColumns;
        try {
            selectableColumns = getSelectableColumns(catalog, schema, tableName);
        } catch (SQLException selectException) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getSelectableColumns", selectException);
            selectableColumns = fallbackSelectableColumns(detailsByName);
        }

        List<CachedColumnRow> rows = new ArrayList<>(selectableColumns.size());
        int pos = 1;
        for (SelectableColumn selectableColumn : selectableColumns) {
            String colName = selectableColumn.name();
            ColumnDetails detail = detailsByName.get(colName);
            String dorisType = detail != null ? detail.dorisType() : null;
            int jdbcType = selectableColumn.jdbcType();
            int columnSize = selectableColumn.columnSize() > 0
                    ? selectableColumn.columnSize()
                    : (dorisType != null ? TypeMapper.extractLength(dorisType) : 0);
            int scale = selectableColumn.scale() > 0
                    ? selectableColumn.scale()
                    : (dorisType != null ? TypeMapper.extractScale(dorisType) : 0);
            int nullableType = detail != null
                    ? TypeMapper.extractNullAble(detail.nullable())
                    : selectableColumn.nullableType();
            String typeName = dorisType != null
                    ? TypeMapper.baseTypeName(dorisType)
                    : selectableColumn.typeName();
            String nullable = detail != null
                    ? normalizeIsNullable(detail.nullable())
                    : normalizeIsNullable(nullableType);
            String defVal = detail != null ? detail.defaultValue() : null;
            String comment = detail != null ? detail.comment() : "";
            Integer precisionRadix = precisionRadix(jdbcType);
            Integer charOctetLength = charOctetLength(jdbcType, columnSize);

            rows.add(new CachedColumnRow(
                    colName,
                    jdbcType,
                    typeName,
                    columnSize,
                    scale,
                    precisionRadix,
                    nullableType,
                    comment,
                    defVal,
                    charOctetLength,
                    pos++,
                    nullable,
                    selectableColumn.autoIncrement() ? "YES" : "NO",
                    "NO"
            ));
        }
        return new CachedTableColumns(List.copyOf(rows), selectableColumns.size(), detailsByName.size(), 0L);
    }

    private static void appendColumnRow(CachedRowSet crs, String catalog, String schema, String tableName, CachedColumnRow row)
            throws SQLException {
        crs.moveToInsertRow();
        crs.updateString("TABLE_CAT", catalog);
        crs.updateString("TABLE_SCHEM", schema);
        crs.updateString("TABLE_NAME", tableName);
        crs.updateString("COLUMN_NAME", row.columnName());
        crs.updateInt("DATA_TYPE", row.jdbcType());
        crs.updateString("TYPE_NAME", row.typeName());
        crs.updateInt("COLUMN_SIZE", row.columnSize());
        crs.updateNull("BUFFER_LENGTH");
        crs.updateInt("DECIMAL_DIGITS", row.scale());
        if (row.precisionRadix() != null) {
            crs.updateInt("NUM_PREC_RADIX", row.precisionRadix());
        } else {
            crs.updateNull("NUM_PREC_RADIX");
        }
        crs.updateInt("NULLABLE", row.nullableType());
        crs.updateString("REMARKS", row.comment());
        crs.updateString("COLUMN_DEF", row.defaultValue());
        crs.updateNull("SQL_DATA_TYPE");
        crs.updateNull("SQL_DATETIME_SUB");
        if (row.charOctetLength() != null) {
            crs.updateInt("CHAR_OCTET_LENGTH", row.charOctetLength());
        } else {
            crs.updateNull("CHAR_OCTET_LENGTH");
        }
        crs.updateInt("ORDINAL_POSITION", row.ordinalPosition());
        crs.updateString("IS_NULLABLE", row.isNullable());
        crs.updateNull("SCOPE_CATALOG");
        crs.updateNull("SCOPE_SCHEMA");
        crs.updateNull("SCOPE_TABLE");
        crs.updateNull("SOURCE_DATA_TYPE");
        crs.updateString("IS_AUTOINCREMENT", row.isAutoincrement());
        crs.updateString("IS_GENERATEDCOLUMN", row.isGeneratedColumn());
        crs.insertRow();
        crs.moveToCurrentRow();
        crs.last();
    }

    private String switchCatalogIfNeeded(String catalog) throws SQLException {
        String originalCatalog = conn.getCatalog();
        if (catalog != null && !catalog.isEmpty()
                && (originalCatalog == null || !catalog.equalsIgnoreCase(originalCatalog))) {
            DorisTraceLogger.log("DorisDatabaseMetaData", "switchCatalogIfNeeded | from=" + originalCatalog + " | to=" + catalog);
            conn.setCatalog(catalog);
        }
        return originalCatalog;
    }

    private void restoreCatalog(String originalCatalog, String currentCatalog) throws SQLException {
        if (originalCatalog != null
                && currentCatalog != null
                && !originalCatalog.equalsIgnoreCase(currentCatalog)) {
            DorisTraceLogger.log("DorisDatabaseMetaData", "restoreCatalog | from=" + currentCatalog + " | to=" + originalCatalog);
            conn.setCatalog(originalCatalog);
        }
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta("TABLE_TYPE"));
        for (String tableType : List.of("TABLE", "VIEW", "MATERIALIZED VIEW")) {
            crs.moveToInsertRow();
            crs.updateString("TABLE_TYPE", tableType);
            crs.insertRow();
            crs.moveToCurrentRow();
            crs.last();
        }
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getSchemas | catalog=" + catalog + " | schemaPattern=" + schemaPattern);
        ResultSet catalogs = getCatalogs();
        Statement st = conn.createStatement();
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta("TABLE_SCHEM", "TABLE_CATALOG"));
        int scannedCatalogs = 0;
        int matchedCatalogs = 0;
        int returnedRows = 0;
        while (catalogs.next()) {
            scannedCatalogs++;
            String cl = catalogs.getString("TABLE_CAT");
            if (catalog != null && !catalog.isEmpty() && !cl.equalsIgnoreCase(catalog)) {
                continue;
            }
            matchedCatalogs++;
            String sql = "SHOW DATABASES FROM `" + cl + "`";
            DorisTraceLogger.logSql("DorisDatabaseMetaData", "getSchemas", sql);
            ResultSet rs = st.executeQuery(sql);
            while (rs.next()) {
                String db = rs.getString(1);
                if (!matchesJdbcPattern(db, schemaPattern)) {
                    continue;
                }
                crs.moveToInsertRow();
                crs.updateString("TABLE_SCHEM", db);
                crs.updateString("TABLE_CATALOG", cl);
                crs.insertRow();
                crs.moveToCurrentRow();
                returnedRows++;
            }
        }
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getSchemas | scannedCatalogs=" + scannedCatalogs
                        + " | matchedCatalogs=" + matchedCatalogs
                        + " | returnedRows=" + returnedRows
                        + " | catalog=" + catalog
                        + " | schemaPattern=" + schemaPattern
        );
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getTables | catalog=" + catalog + " | schemaPattern=" + schemaPattern + " | tableNamePattern=" + tableNamePattern
                        + " | types=" + formatTypes(types)
        );
        final String useCatalog = (catalog != null && !catalog.isEmpty()) ? catalog : conn.getCatalog();
        List<String> targetSchemas = getMatchingSchemas(useCatalog, schemaPattern);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getTables | useCatalog=" + useCatalog + " | targetSchemas=" + targetSchemas
        );

        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"));

        int returnedRows = 0;
        for (String schema : targetSchemas) {
            List<TableListing> matchedTables = new ArrayList<>();
            int scannedTables = 0;
            String query = String.format("SHOW FULL TABLES FROM `%s`.`%s`", useCatalog, schema);
            DorisTraceLogger.logSql("DorisDatabaseMetaData", "getTables", query);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(query)) {
                while (rs.next()) {
                    scannedTables++;
                    String tableName = rs.getString(1);
                    String tableType = normalizeTableType(rs.getString(2));
                    if (!matchesJdbcPattern(tableName, tableNamePattern) || !matchesTableTypes(tableType, types)) {
                        continue;
                    }
                    matchedTables.add(new TableListing(tableName, tableType));
                }
            }
            DorisTraceLogger.log(
                    "DorisDatabaseMetaData",
                    "getTables | schema=" + schema
                            + " | scannedTables=" + scannedTables
                            + " | matchedTables=" + matchedTables.size()
                            + " | tableNamePattern=" + tableNamePattern
                            + " | types=" + formatTypes(types)
            );

            Map<String, String> tableComments = matchedTables.isEmpty()
                    ? Map.of()
                    : getTableComments(useCatalog, schema);
            for (TableListing table : matchedTables) {
                    crs.moveToInsertRow();
                    crs.updateString("TABLE_CAT", useCatalog);
                    crs.updateString("TABLE_SCHEM", schema);
                    crs.updateString("TABLE_NAME", table.name());
                    crs.updateString("TABLE_TYPE", table.type());
                    crs.updateString("REMARKS", tableComments.getOrDefault(table.name(), ""));
                    crs.insertRow();
                    crs.moveToCurrentRow();
                    crs.last();
                    returnedRows++;
            }
        }
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getTables | returnedRows=" + returnedRows
                        + " | useCatalog=" + useCatalog
                        + " | schemaPattern=" + schemaPattern
                        + " | tableNamePattern=" + tableNamePattern
                        + " | types=" + formatTypes(types)
        );
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        String useCatalog = (catalog != null && !catalog.isEmpty()) ? catalog : conn.getCatalog();
        String useSchema = normalizeSchemaPattern(schemaPattern);
        String useTableName = normalizePattern(tableNamePattern);
        boolean wildcardTablePattern = hasWildcard(tableNamePattern);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getColumns | catalog=" + catalog
                        + " | schemaPattern=" + schemaPattern
                        + " | tableNamePattern=" + tableNamePattern
                        + " | columnNamePattern=" + columnNamePattern
                        + " | normalizedSchema=" + useSchema
                        + " | normalizedTable=" + useTableName
                        + " | useCatalog=" + useCatalog
                        + " | wildcardTablePattern=" + wildcardTablePattern
        );

        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildColumnsMeta());
        if (useSchema == null || (!wildcardTablePattern && useTableName == null)) {
            DorisTraceLogger.log(
                    "DorisDatabaseMetaData",
                    "getColumns | skipped because schema or table is null"
                            + " | useCatalog=" + useCatalog
                            + " | normalizedSchema=" + useSchema
                            + " | normalizedTable=" + useTableName
            );
            crs.beforeFirst();
            return crs;
        }

        List<String> targetTables = wildcardTablePattern
                ? getMatchingTableNames(useCatalog, useSchema, tableNamePattern)
                : List.of(useTableName);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getColumns | targetTables=" + targetTables
                        + " | useCatalog=" + useCatalog
                        + " | useSchema=" + useSchema
        );
        int returnedRows = 0;
        try {
            for (String tableName : targetTables) {
                int insertedColumns = 0;
                CachedTableColumns tableColumns = getCachedTableColumns(useCatalog, useSchema, tableName);
                for (CachedColumnRow row : tableColumns.rows()) {
                    if (!matchesJdbcPattern(row.columnName(), columnNamePattern)) {
                        continue;
                    }
                    appendColumnRow(crs, useCatalog, useSchema, tableName, row);
                    insertedColumns++;
                    returnedRows++;
                }
                DorisTraceLogger.log(
                        "DorisDatabaseMetaData",
                        "getColumns | table=" + tableName
                                + " | selectableColumns=" + tableColumns.selectableColumns()
                                + " | describedColumns=" + tableColumns.describedColumns()
                                + " | returnedColumns=" + insertedColumns
                );
            }
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getColumns", e);
            throw e;
        }
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getColumns | returnedRows=" + returnedRows
                        + " | useCatalog=" + useCatalog
                        + " | useSchema=" + useSchema
                        + " | tableNamePattern=" + tableNamePattern
                        + " | columnNamePattern=" + columnNamePattern
        );
        crs.beforeFirst();
        return crs;
    }

    private record SelectableColumn(
            String name,
            int jdbcType,
            String typeName,
            int columnSize,
            int scale,
            int nullableType,
            boolean autoIncrement
    ) {
    }

    private record TableListing(
            String name,
            String type
    ) {
    }

    private record ColumnDetails(
            String dorisType,
            String nullable,
            String defaultValue,
            String comment
    ) {
    }

    private record CachedColumnRow(
            String columnName,
            int jdbcType,
            String typeName,
            int columnSize,
            int scale,
            Integer precisionRadix,
            int nullableType,
            String comment,
            String defaultValue,
            Integer charOctetLength,
            int ordinalPosition,
            String isNullable,
            String isAutoincrement,
            String isGeneratedColumn
    ) {
    }

    private record CachedTableColumns(
            List<CachedColumnRow> rows,
            int selectableColumns,
            int describedColumns,
            long cachedAtMillis
    ) {
        private CachedTableColumns withCachedAt(long cachedAtMillis) {
            return new CachedTableColumns(rows, selectableColumns, describedColumns, cachedAtMillis);
        }
    }

    private record ColumnCacheKey(
            long connectionId,
            String catalog,
            String schema,
            String table
    ) {
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return delegate.supportsStoredFunctionsUsingCallSyntax();
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return delegate.autoCommitFailureClosesAllResultSets();
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return delegate.getClientInfoProperties();
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return delegate.getFunctions(catalog, schemaPattern, functionNamePattern);
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta(
                "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME",
                "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE",
                "TYPE_NAME", "PRECISION", "LENGTH", "SCALE",
                "RADIX", "NULLABLE", "REMARKS"
        ));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
                "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE"
        ));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return delegate.generatedKeyAlwaysReturned();
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return delegate.allProceduresAreCallable();
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return delegate.allTablesAreSelectable();
    }

    @Override
    public String getURL() throws SQLException {
        return delegate.getURL();
    }

    @Override
    public String getUserName() throws SQLException {
        return delegate.getUserName();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return delegate.nullsAreSortedHigh();
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return delegate.nullsAreSortedLow();
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return delegate.nullsAreSortedAtStart();
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return delegate.nullsAreSortedAtEnd();
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        String productName = System.getProperty(DATABASE_PRODUCT_NAME_PROPERTY);
        String effectiveProductName = productName == null || productName.isBlank() ? "Doris" : productName;
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getDatabaseProductName | returned=" + effectiveProductName
                        + " | override=" + (productName == null || productName.isBlank() ? "" : productName)
        );
        return effectiveProductName;
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return delegate.getDatabaseProductVersion();
    }

    @Override
    public String getDriverName() throws SQLException {
        return delegate.getDriverName();
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return delegate.getDriverVersion();
    }

    @Override
    public int getDriverMajorVersion() {
        return delegate.getDriverMajorVersion();
    }

    @Override
    public int getDriverMinorVersion() {
        return delegate.getDriverMinorVersion();
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return delegate.usesLocalFiles();
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return delegate.usesLocalFilePerTable();
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return delegate.supportsMixedCaseIdentifiers();
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return delegate.storesUpperCaseIdentifiers();
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return delegate.storesLowerCaseIdentifiers();
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return delegate.storesMixedCaseIdentifiers();
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return delegate.supportsMixedCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return delegate.storesUpperCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return delegate.storesLowerCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return delegate.storesMixedCaseQuotedIdentifiers();
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        return delegate.getIdentifierQuoteString();
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        return delegate.getSQLKeywords();
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return delegate.getNumericFunctions();
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return delegate.getStringFunctions();
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        return delegate.getSystemFunctions();
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return delegate.getTimeDateFunctions();
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return delegate.getSearchStringEscape();
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return delegate.getExtraNameCharacters();
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return delegate.supportsAlterTableWithAddColumn();
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return delegate.supportsAlterTableWithDropColumn();
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return delegate.supportsColumnAliasing();
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return delegate.nullPlusNonNullIsNull();
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        return delegate.supportsConvert();
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        return delegate.supportsConvert(fromType, toType);
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return delegate.supportsTableCorrelationNames();
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return delegate.supportsDifferentTableCorrelationNames();
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return delegate.supportsExpressionsInOrderBy();
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return delegate.supportsOrderByUnrelated();
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return delegate.supportsGroupBy();
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return delegate.supportsGroupByUnrelated();
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return delegate.supportsGroupByBeyondSelect();
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return delegate.supportsLikeEscapeClause();
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return delegate.supportsMultipleResultSets();
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return delegate.supportsMultipleTransactions();
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return delegate.supportsNonNullableColumns();
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return delegate.supportsMinimumSQLGrammar();
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return delegate.supportsCoreSQLGrammar();
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return delegate.supportsExtendedSQLGrammar();
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return delegate.supportsANSI92EntryLevelSQL();
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return delegate.supportsANSI92IntermediateSQL();
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return delegate.supportsANSI92FullSQL();
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return delegate.supportsIntegrityEnhancementFacility();
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return delegate.supportsOuterJoins();
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return delegate.supportsLimitedOuterJoins();
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        return "schema";
        //return delegate.getSchemaTerm();
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return "procedure";
        //return delegate.getProcedureTerm();
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return "catalog";
        //return delegate.getCatalogTerm();
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return delegate.isCatalogAtStart();
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        return delegate.getCatalogSeparator();
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return true;
        //return delegate.supportsSchemasInTableDefinitions();
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return true;
        //return delegate.supportsCatalogsInTableDefinitions();
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return delegate.supportsPositionedDelete();
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return delegate.supportsPositionedUpdate();
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return delegate.supportsSelectForUpdate();
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return delegate.supportsStoredProcedures();
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return delegate.supportsSubqueriesInComparisons();
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return delegate.supportsSubqueriesInExists();
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return delegate.supportsSubqueriesInIns();
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return delegate.supportsSubqueriesInQuantifieds();
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return delegate.supportsCorrelatedSubqueries();
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return delegate.supportsUnion();
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return delegate.supportsUnionAll();
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return delegate.supportsOpenCursorsAcrossCommit();
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return delegate.supportsOpenCursorsAcrossRollback();
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return delegate.supportsOpenStatementsAcrossCommit();
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return delegate.supportsOpenStatementsAcrossRollback();
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return delegate.getMaxBinaryLiteralLength();
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return delegate.getMaxCharLiteralLength();
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return delegate.getMaxColumnNameLength();
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return delegate.getMaxColumnsInGroupBy();
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return delegate.getMaxColumnsInIndex();
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return delegate.getMaxColumnsInOrderBy();
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return delegate.getMaxColumnsInSelect();
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return delegate.getMaxColumnsInTable();
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return delegate.getMaxConnections();
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return delegate.getMaxCursorNameLength();
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        return delegate.getMaxIndexLength();
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return delegate.getMaxSchemaNameLength();
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return delegate.getMaxProcedureNameLength();
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return delegate.getMaxCatalogNameLength();
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        return delegate.getMaxRowSize();
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return delegate.doesMaxRowSizeIncludeBlobs();
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return delegate.getMaxStatementLength();
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return delegate.getMaxStatements();
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return delegate.getMaxTableNameLength();
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return delegate.getMaxTablesInSelect();
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return delegate.getMaxUserNameLength();
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return delegate.getDefaultTransactionIsolation();
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return delegate.supportsTransactions();
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        return delegate.supportsTransactionIsolationLevel(level);
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return delegate.supportsDataDefinitionAndDataManipulationTransactions();
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return delegate.supportsDataManipulationTransactionsOnly();
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return delegate.dataDefinitionCausesTransactionCommit();
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return delegate.dataDefinitionIgnoredInTransactions();
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        //return delegate.getProcedures(catalog, schemaPattern, procedureNamePattern);
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME"));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        //return delegate.getProcedureColumns(catalog, schemaPattern, procedureNamePattern, columnNamePattern);
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME",
                "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS"));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getColumnPrivileges | return empty | catalog=" + catalog + " | schema=" + schema + " | table=" + table);
        return emptyResultSet(buildColumnPrivilegesMeta());
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getTablePrivileges | return empty | catalog=" + catalog + " | schemaPattern=" + schemaPattern + " | tableNamePattern=" + tableNamePattern);
        return emptyResultSet(buildTablePrivilegesMeta());
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getBestRowIdentifier | return empty | catalog=" + catalog + " | schema=" + schema + " | table=" + table);
        return emptyResultSet(buildPseudoColumnsMeta());
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getVersionColumns | return empty | catalog=" + catalog + " | schema=" + schema + " | table=" + table);
        return emptyResultSet(buildPseudoColumnsMeta());
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        String useCatalog = (catalog != null && !catalog.isEmpty()) ? catalog : conn.getCatalog();
        String useSchema = normalizeSchemaPattern(schema);
        String useTableName = normalizePattern(table);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getPrimaryKeys | catalog=" + catalog + " | schema=" + schema + " | table=" + table
                        + " | normalizedSchema=" + useSchema + " | normalizedTable=" + useTableName
        );
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildPrimaryKeysMeta());
        if (useSchema == null || useTableName == null) {
            DorisTraceLogger.log("DorisDatabaseMetaData", "getPrimaryKeys | skipped because schema or table is null");
            crs.beforeFirst();
            return crs;
        }

        String originalCatalog = switchCatalogIfNeeded(useCatalog);
        int returnedRows = 0;
        try (ResultSet rs = queryShowIndex("getPrimaryKeys", useSchema, useTableName)) {
            while (rs.next()) {
                String keyName = rs.getString("Key_name");
                if (!"PRIMARY".equalsIgnoreCase(keyName)) {
                    continue;
                }
                crs.moveToInsertRow();
                crs.updateString("TABLE_CAT", useCatalog);
                crs.updateString("TABLE_SCHEM", useSchema);
                crs.updateString("TABLE_NAME", useTableName);
                crs.updateString("COLUMN_NAME", rs.getString("Column_name"));
                crs.updateShort("KEY_SEQ", rs.getShort("Seq_in_index"));
                crs.updateString("PK_NAME", keyName);
                crs.insertRow();
                crs.moveToCurrentRow();
                returnedRows++;
            }
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getPrimaryKeys", e);
        } finally {
            restoreCatalog(originalCatalog, useCatalog);
        }
        DorisTraceLogger.log("DorisDatabaseMetaData", "getPrimaryKeys | returnedRows=" + returnedRows + " | table=" + useTableName);
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getImportedKeys | return empty | catalog=" + catalog + " | schema=" + schema + " | table=" + table);
        return emptyResultSet(buildForeignKeysMeta());
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getExportedKeys | return empty | catalog=" + catalog + " | schema=" + schema + " | table=" + table);
        return emptyResultSet(buildForeignKeysMeta());
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getCrossReference | return empty | parentCatalog=" + parentCatalog + " | parentSchema=" + parentSchema + " | parentTable=" + parentTable + " | foreignCatalog=" + foreignCatalog + " | foreignSchema=" + foreignSchema + " | foreignTable=" + foreignTable);
        return emptyResultSet(buildForeignKeysMeta());
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return delegate.getTypeInfo();
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        String useCatalog = (catalog != null && !catalog.isEmpty()) ? catalog : conn.getCatalog();
        String useSchema = normalizeSchemaPattern(schema);
        String useTableName = normalizePattern(table);
        DorisTraceLogger.log(
                "DorisDatabaseMetaData",
                "getIndexInfo | catalog=" + catalog + " | schema=" + schema + " | table=" + table
                        + " | unique=" + unique + " | approximate=" + approximate
                        + " | normalizedSchema=" + useSchema + " | normalizedTable=" + useTableName
        );
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildIndexInfoMeta());
        if (useSchema == null || useTableName == null) {
            DorisTraceLogger.log("DorisDatabaseMetaData", "getIndexInfo | skipped because schema or table is null");
            crs.beforeFirst();
            return crs;
        }

        String originalCatalog = switchCatalogIfNeeded(useCatalog);
        int returnedRows = 0;
        try (ResultSet rs = queryShowIndex("getIndexInfo", useSchema, useTableName)) {
            while (rs.next()) {
                boolean nonUnique = rs.getInt("Non_unique") != 0;
                if (unique && nonUnique) {
                    continue;
                }
                crs.moveToInsertRow();
                crs.updateString("TABLE_CAT", useCatalog);
                crs.updateString("TABLE_SCHEM", useSchema);
                crs.updateString("TABLE_NAME", useTableName);
                crs.updateBoolean("NON_UNIQUE", nonUnique);
                crs.updateNull("INDEX_QUALIFIER");
                crs.updateString("INDEX_NAME", rs.getString("Key_name"));
                crs.updateShort("TYPE", (short) DatabaseMetaData.tableIndexOther);
                crs.updateShort("ORDINAL_POSITION", rs.getShort("Seq_in_index"));
                crs.updateString("COLUMN_NAME", rs.getString("Column_name"));
                crs.updateString("ASC_OR_DESC", rs.getString("Collation"));
                long cardinality = rs.getLong("Cardinality");
                if (rs.wasNull()) {
                    crs.updateNull("CARDINALITY");
                } else {
                    crs.updateLong("CARDINALITY", cardinality);
                }
                crs.updateNull("PAGES");
                crs.updateNull("FILTER_CONDITION");
                crs.insertRow();
                crs.moveToCurrentRow();
                returnedRows++;
                DorisTraceLogger.log(
                        "DorisDatabaseMetaData",
                        "getIndexInfo | row | index=" + rs.getString("Key_name")
                                + " | column=" + rs.getString("Column_name")
                                + " | nonUnique=" + nonUnique
                                + " | seq=" + rs.getShort("Seq_in_index")
                );
            }
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDatabaseMetaData", "getIndexInfo", e);
        } finally {
            restoreCatalog(originalCatalog, useCatalog);
        }
        DorisTraceLogger.log("DorisDatabaseMetaData", "getIndexInfo | returnedRows=" + returnedRows + " | table=" + useTableName);
        crs.beforeFirst();
        return crs;
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return delegate.supportsResultSetType(type);
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        return delegate.supportsResultSetConcurrency(type, concurrency);
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return delegate.ownUpdatesAreVisible(type);
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return delegate.ownDeletesAreVisible(type);
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return delegate.ownInsertsAreVisible(type);
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return delegate.othersUpdatesAreVisible(type);
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return delegate.othersDeletesAreVisible(type);
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return delegate.othersInsertsAreVisible(type);
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        return delegate.updatesAreDetected(type);
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        return delegate.deletesAreDetected(type);
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        return delegate.insertsAreDetected(type);
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return delegate.supportsBatchUpdates();
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        //return delegate.getUDTs(catalog, schemaPattern, typeNamePattern, types);
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta(
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE",
                "REMARKS", "BASE_TYPE"
        ));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public Connection getConnection() throws SQLException {
        DorisTraceLogger.log("DorisDatabaseMetaData", "getConnection | returning wrapped connection");
        return conn;
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return delegate.supportsSavepoints();
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return delegate.supportsNamedParameters();
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return delegate.supportsMultipleOpenResults();
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        return delegate.supportsGetGeneratedKeys();
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        //return delegate.getSuperTypes(catalog, schemaPattern, typeNamePattern);
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta(
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME",
                "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME"
        ));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        //return delegate.getSuperTables(catalog, schemaPattern, tableNamePattern);
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                "SUPERTABLE_NAME"
        ));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException {
        //return delegate.getAttributes(catalog, schemaPattern, typeNamePattern, attributeNamePattern);
        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
        crs.setMetaData(buildMeta(
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME",
                "DATA_TYPE", "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS",
                "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE"
        ));
        crs.beforeFirst();
        return crs;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return delegate.supportsResultSetHoldability(holdability);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return delegate.getResultSetHoldability();
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return delegate.getDatabaseMajorVersion();
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return delegate.getDatabaseMinorVersion();
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return delegate.getJDBCMajorVersion();
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return delegate.getJDBCMinorVersion();
    }

    @Override
    public int getSQLStateType() throws SQLException {
        return delegate.getSQLStateType();
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        return delegate.locatorsUpdateCopy();
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return delegate.supportsStatementPooling();
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return delegate.getRowIdLifetime();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
