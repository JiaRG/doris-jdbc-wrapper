package io.github.hpkaiq.dorisjdbc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class DorisTraceLogger {
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String LOG_PATH_PROPERTY = "doris.jdbc.log.path";
    private static final String LOG_LEVEL_PROPERTY = "doris.jdbc.log.level";
    private static final String LOG_ENABLED_PROPERTY = "doris.jdbc.log.enabled";

    private DorisTraceLogger() {
    }

    static void log(String component, String message) {
        writeLine(Level.INFO, component + " | " + message);
    }

    static void logSql(String component, String method, String sql) {
        writeLine(Level.INFO, component + " | " + method + " | sql=" + sql);
    }

    static void logError(String component, String method, Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        writeLine(Level.ERROR, component + " | " + method + " | ERROR | " + writer);
    }

    private static void writeLine(Level level, String message) {
        if (!isEnabled(level)) {
            return;
        }
        Path logPath = resolveLogPath();
        String line = FORMATTER.format(LocalDateTime.now())
                + " | "
                + Thread.currentThread().getName()
                + " | "
                + message
                + System.lineSeparator();

        synchronized (LOCK) {
            try {
                Path parent = logPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        logPath,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            } catch (Exception ignored) {
                // Logging must never break driver behavior.
            }
        }
    }

    private static boolean isEnabled(Level level) {
        return level.priority <= configuredLevel().priority;
    }

    private static Level configuredLevel() {
        String configured = System.getProperty(LOG_LEVEL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            String enabled = System.getProperty(LOG_ENABLED_PROPERTY);
            if (enabled != null && !enabled.isBlank()) {
                return Boolean.parseBoolean(enabled) ? Level.INFO : Level.OFF;
            }
            return Level.ERROR;
        }
        try {
            return Level.valueOf(configured.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return Level.ERROR;
        }
    }

    private static Path resolveLogPath() {
        String configured = System.getProperty(LOG_PATH_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.home"), ".doris-jdbc-wrapper", "doris-jdbc-wrapper.log");
    }

    private enum Level {
        OFF(0),
        ERROR(1),
        INFO(2);

        private final int priority;

        Level(int priority) {
            this.priority = priority;
        }
    }
}
