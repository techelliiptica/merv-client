package org.teche.merv.client.logging.appender;

import org.teche.merv.client.logging.LogLevel;
import org.teche.merv.client.logging.MervLogAppender;
import org.teche.merv.client.logging.MervLoggerConfig;
import org.teche.merv.client.logging.MervLoggerFactory;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * {@code java.util.logging} handler — forwards lines to Merv-Logs based on {@code merv.properties}.
 *
 * <pre>{@code
 * Logger root = LogManager.getLogManager().getLogger("");
 * root.addHandler(new MervJulLogHandler());
 * root.setLevel(Level.ALL);
 * }</pre>
 */
public class MervJulLogHandler extends Handler {

    public MervJulLogHandler() {
        if (!MervLoggerConfig.isInitialized()) {
            MervLoggerConfig.loadConfiguration();
        }
        setLevel(mapLevel(MervLoggerFactory.getGlobalLogLevel()));
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        Level julLevel = record.getLevel();
        String levelName = julLevel != null ? julLevel.getName() : LogLevel.INFO.getName();
        String loggerName = record.getLoggerName();
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (message != null && params != null && params.length > 0) {
            try {
                message = java.text.MessageFormat.format(message, params);
            } catch (Exception ignored) {
                /* use raw message */
            }
        }
        Throwable thrown = record.getThrown();
        MervLogAppender.append(levelName, loggerName, message, thrown);
    }

    @Override
    public void flush() {
        /* NDJSON append is synchronous per line */
    }

    @Override
    public void close() {
        /* no resources */
    }

    private static Level mapLevel(LogLevel level) {
        if (level == null) {
            return Level.INFO;
        }
        return switch (level) {
            case TRACE -> Level.FINEST;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };
    }
}
