package io.github.hpkaiq.dorisjdbc;


import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;

public class DorisConnection implements Connection {
    private static final AtomicLong CONNECTION_IDS = new AtomicLong(1);
    private final Connection delegate;
    private final long connectionId = CONNECTION_IDS.getAndIncrement();
    private volatile String currentCatalog = "internal";
    private volatile String currentSchema;

    DorisConnection(Connection delegate) {
        this.delegate = delegate;
    }

    private Statement wrapStatement(Statement statement) {
        return new DorisStatement(statement, this);
    }

    private PreparedStatement wrapPreparedStatement(PreparedStatement statement, String sql) {
        return new DorisPreparedStatement(statement, this, sql);
    }

    private String normalizeSql(String operation, String sql) {
        String normalized = DorisSqlNormalizer.normalize(sql);
        if (normalized != sql) {
            DorisTraceLogger.logSql("DorisConnection", operation + " normalized", normalized);
        }
        return normalized;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        DorisTraceLogger.log("DorisConnection", "setCatalog | from=" + currentCatalog + " | to=" + catalog);
        checkOpen();
        if (catalog != null && !catalog.equalsIgnoreCase(this.currentCatalog)) {
            try (Statement stmt = delegate.createStatement()) {
                String sql = "SWITCH `" + catalog + "`";
                DorisTraceLogger.logSql("DorisConnection", "setCatalog", sql);
                stmt.execute(sql);
            } catch (SQLException e) {
                DorisTraceLogger.logError("DorisConnection", "setCatalog", e);
                throw e;
            }
            this.currentCatalog = catalog;
            this.currentSchema = null;
        }
    }

    private void checkOpen()
            throws SQLException {
        if (isClosed()) {
            throw new SQLException("Connection is closed");
        }
    }

    @Override
    public String getCatalog() {
        return currentCatalog;
    }

    long getConnectionId() {
        return connectionId;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        delegate.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return delegate.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return wrapStatement(delegate.createStatement(resultSetType, resultSetConcurrency));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        String normalizedSql = normalizeSql("prepareStatement", sql);
        return wrapPreparedStatement(delegate.prepareStatement(normalizedSql, resultSetType, resultSetConcurrency), normalizedSql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return delegate.prepareCall(normalizeSql("prepareCall", sql), resultSetType, resultSetConcurrency);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        delegate.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        delegate.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return delegate.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return delegate.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        delegate.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        delegate.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return wrapStatement(delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        String normalizedSql = normalizeSql("prepareStatement", sql);
        return wrapPreparedStatement(delegate.prepareStatement(normalizedSql, resultSetType, resultSetConcurrency, resultSetHoldability), normalizedSql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return delegate.prepareCall(normalizeSql("prepareCall", sql), resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        String normalizedSql = normalizeSql("prepareStatement", sql);
        return wrapPreparedStatement(delegate.prepareStatement(normalizedSql, autoGeneratedKeys), normalizedSql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        String normalizedSql = normalizeSql("prepareStatement", sql);
        return wrapPreparedStatement(delegate.prepareStatement(normalizedSql, columnIndexes), normalizedSql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        String normalizedSql = normalizeSql("prepareStatement", sql);
        return wrapPreparedStatement(delegate.prepareStatement(normalizedSql, columnNames), normalizedSql);
    }

    @Override
    public Clob createClob() throws SQLException {
        return delegate.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return delegate.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return delegate.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return delegate.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        delegate.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        delegate.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return delegate.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return delegate.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return delegate.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        DorisTraceLogger.log("DorisConnection", "setSchema | from=" + currentSchema + " | to=" + schema);
        currentSchema = schema;
        delegate.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        if (currentSchema != null && !currentSchema.isEmpty()) {
            return currentSchema;
        }
        String schema = delegate.getSchema();
        if (schema != null && !schema.isEmpty()) {
            currentSchema = schema;
        }
        DorisTraceLogger.log("DorisConnection", "getSchema | schema=" + currentSchema);
        return currentSchema;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        delegate.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        delegate.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        DorisTraceLogger.log("DorisConnection", "getMetaData | catalog=" + currentCatalog);
        return new DorisDatabaseMetaData(this, delegate.getMetaData());
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        delegate.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public Statement createStatement() throws SQLException {
        return wrapStatement(delegate.createStatement());
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        String normalizedSql = normalizeSql("prepareStatement", sql);
        return wrapPreparedStatement(delegate.prepareStatement(normalizedSql), normalizedSql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return delegate.prepareCall(normalizeSql("prepareCall", sql));
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return delegate.nativeSQL(normalizeSql("nativeSQL", sql));
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        delegate.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        delegate.commit();
    }

    @Override
    public void rollback() throws SQLException {
        delegate.rollback();
    }

    @Override
    public void close() throws SQLException {
        DorisDatabaseMetaData.clearColumnCache(connectionId);
        delegate.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
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
