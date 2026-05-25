package io.github.hpkaiq.dorisjdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

public final class DorisDriver implements Driver {
    private static final String URL_PREFIX = "jdbc:doris://";
    private static volatile Driver mysqlDriver;

    static {
        try {
            DriverManager.registerDriver(new DorisDriver());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Driver getMysqlDriver() throws SQLException {
        Driver current = mysqlDriver;
        if (current != null) {
            return current;
        }
        synchronized (DorisDriver.class) {
            if (mysqlDriver == null) {
                mysqlDriver = createMysqlDriver();
            }
            return mysqlDriver;
        }
    }

    static Driver createMysqlDriver() throws SQLException {
        try {
            Driver driver = new com.mysql.cj.jdbc.NonRegisteringDriver();
            DorisTraceLogger.log(
                    "DorisDriver",
                    "createMysqlDriver | delegateClass=" + driver.getClass().getName()
                            + " | delegateClassLoader=" + driver.getClass().getClassLoader()
            );
            return driver;
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDriver", "createMysqlDriver", e);
            throw e;
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        DorisTraceLogger.log("DorisDriver", "connect | url=" + url);
        if (!acceptsURL(url)) {
            DorisTraceLogger.log("DorisDriver", "connect | url not accepted");
            return null;
        }
        String mysqlUrl = "jdbc:mysql://" + url.substring(URL_PREFIX.length());
        DorisTraceLogger.log("DorisDriver", "connect | mysqlUrl=" + mysqlUrl);
        try {
            Connection raw = getMysqlDriver().connect(mysqlUrl, info);
            DorisTraceLogger.log("DorisDriver", "connect | delegate connected");
            return new DorisConnection(raw);
        } catch (SQLException e) {
            DorisTraceLogger.logError("DorisDriver", "connect", e);
            throw e;
        }
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(URL_PREFIX); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
