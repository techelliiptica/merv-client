package org.teche.merv.client.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/**
 * Core bridge from third-party logging frameworks into Merv-Logs.
 *
 * <p>Reads {@code merv.properties} / {@code mervlogger.properties} via {@link MervLoggerConfig}:
 * <ul>
 *   <li>{@code merv.logger.file} / {@code merv.log.file} — local NDJSON under {@code {reportRoot}/log/}</li>
 *   <li>{@code merv.log.server} — async forward to {@code /api/logs/ingest}</li>
 *   <li>{@code merv.log.source} — project label on forwarded lines</li>
 *   <li>{@code merv.logger.level} — minimum level for appender output</li>
 * </ul>
 *
 * <p>Attach framework adapters:
 * <ul>
 *   <li>Logback (SLF4J): {@link org.teche.merv.client.logging.appender.MervLogbackAppender}</li>
 *   <li>Log4j 2: {@link org.teche.merv.client.logging.appender.MervLog4j2Appender}</li>
 *   <li>Log4j 1.x: {@link org.teche.merv.client.logging.appender.MervLog4j1Appender}</li>
 *   <li>JUL: {@link org.teche.merv.client.logging.appender.MervJulLogHandler}</li>
 * </ul>
 */
public final class MervLogAppender {

    private MervLogAppender() {
    }

    /** True when local file logging or remote {@code merv.log.server} is configured. */
    public static boolean isActive() {
        ensureConfigLoaded();
        return MervLoggerConfig.isFileLoggingEnabled()
                || hasRemoteServer();
    }

    public static boolean isLevelEnabled(LogLevel level) {
        if (level == null) {
            return false;
        }
        ensureConfigLoaded();
        return level.isEnabled(MervLoggerFactory.getGlobalLogLevel());
    }

    public static boolean isLevelEnabled(String levelName) {
        return isLevelEnabled(LogLevel.fromString(levelName));
    }

    /**
     * Append one log line to Merv-Logs (local NDJSON and/or remote server per config).
     * Never throws to the caller.
     */
    public static void append(LogLevel level, String loggerName, String message, Throwable throwable) {
        if (level == null || !isActive() || !isLevelEnabled(level)) {
            return;
        }
        try {
            MervLogRecord record = toRecord(level.getName(), loggerName, message, throwable);
            MervLogSink.appendMervLogRecord(record);
        } catch (Exception ignored) {
            /* never break the host logger */
        }
    }

    /** Convenience overload for framework level names (TRACE, DEBUG, INFO, …). */
    public static void append(String levelName, String loggerName, String message, Throwable throwable) {
        append(LogLevel.fromString(levelName), loggerName, message, throwable);
    }

    static MervLogRecord toRecord(String levelName, String loggerName, String message, Throwable throwable) {
        MervLogRecord record = new MervLogRecord();
        record.setTs(Instant.now().toString());
        record.setLevel(levelName != null ? levelName.toUpperCase() : LogLevel.INFO.getName());
        record.setName(loggerName != null ? loggerName : "");
        record.setMsg(message != null ? message : "");
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            record.setStack(sw.toString());
        }
        String suite = MervLogContext.getSuite();
        String testcase = MervLogContext.getTestcase();
        if (suite != null && !suite.isBlank()) {
            record.setSuite(suite);
        }
        if (testcase != null && !testcase.isBlank()) {
            record.setTestcase(testcase);
        }
        return record;
    }

    private static void ensureConfigLoaded() {
        if (!MervLoggerConfig.isInitialized()) {
            MervLoggerConfig.loadConfiguration();
        }
    }

    private static boolean hasRemoteServer() {
        String remote = MervLoggerConfig.getLogServerUrl();
        return remote != null && !remote.isBlank();
    }
}
