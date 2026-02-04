package org.teche.merv.client.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Implementation of MervLogger interface.
 * Uses SLF4J backend if available, otherwise falls back to built-in console logging.
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
class MervLoggerImpl implements MervLogger {
    
    private final String name;
    private final Logger slf4jLogger;
    private final boolean useSlf4j;
    private final LogLevel effectiveLevel;
    
    /**
     * Creates a new MervLoggerImpl instance
     * @param name the logger name
     */
    MervLoggerImpl(String name) {
        this.name = name;
        this.useSlf4j = MervLoggerFactory.isUseSlf4j() && isSlf4jAvailable();
        this.slf4jLogger = useSlf4j ? LoggerFactory.getLogger(name) : null;
        this.effectiveLevel = MervLoggerFactory.getGlobalLogLevel();
    }
    
    /**
     * Check if SLF4J is available on the classpath
     * @return true if SLF4J is available
     */
    private boolean isSlf4jAvailable() {
        try {
            Class.forName("org.slf4j.LoggerFactory");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public boolean isTraceEnabled() {
        if (useSlf4j && slf4jLogger != null) {
            return slf4jLogger.isTraceEnabled();
        }
        return LogLevel.TRACE.isEnabled(effectiveLevel);
    }
    
    @Override
    public boolean isDebugEnabled() {
        if (useSlf4j && slf4jLogger != null) {
            return slf4jLogger.isDebugEnabled();
        }
        return LogLevel.DEBUG.isEnabled(effectiveLevel);
    }
    
    @Override
    public boolean isInfoEnabled() {
        if (useSlf4j && slf4jLogger != null) {
            return slf4jLogger.isInfoEnabled();
        }
        return LogLevel.INFO.isEnabled(effectiveLevel);
    }
    
    @Override
    public boolean isWarnEnabled() {
        if (useSlf4j && slf4jLogger != null) {
            return slf4jLogger.isWarnEnabled();
        }
        return LogLevel.WARN.isEnabled(effectiveLevel);
    }
    
    @Override
    public boolean isErrorEnabled() {
        if (useSlf4j && slf4jLogger != null) {
            return slf4jLogger.isErrorEnabled();
        }
        return LogLevel.ERROR.isEnabled(effectiveLevel);
    }
    
    @Override
    public void trace(String message) {
        if (isTraceEnabled()) {
            log(LogLevel.TRACE, message, null);
        }
    }
    
    @Override
    public void trace(String message, Object... args) {
        if (isTraceEnabled()) {
            log(LogLevel.TRACE, formatMessage(message, args), null);
        }
    }
    
    @Override
    public void trace(String message, Throwable throwable) {
        if (isTraceEnabled()) {
            log(LogLevel.TRACE, message, throwable);
        }
    }
    
    @Override
    public void debug(String message) {
        if (isDebugEnabled()) {
            log(LogLevel.DEBUG, message, null);
        }
    }
    
    @Override
    public void debug(String message, Object... args) {
        if (isDebugEnabled()) {
            log(LogLevel.DEBUG, formatMessage(message, args), null);
        }
    }
    
    @Override
    public void debug(String message, Throwable throwable) {
        if (isDebugEnabled()) {
            log(LogLevel.DEBUG, message, throwable);
        }
    }
    
    @Override
    public void info(String message) {
        if (isInfoEnabled()) {
            log(LogLevel.INFO, message, null);
        }
    }
    
    @Override
    public void info(String message, Object... args) {
        if (isInfoEnabled()) {
            log(LogLevel.INFO, formatMessage(message, args), null);
        }
    }
    
    @Override
    public void info(String message, Throwable throwable) {
        if (isInfoEnabled()) {
            log(LogLevel.INFO, message, throwable);
        }
    }
    
    @Override
    public void warn(String message) {
        if (isWarnEnabled()) {
            log(LogLevel.WARN, message, null);
        }
    }
    
    @Override
    public void warn(String message, Object... args) {
        if (isWarnEnabled()) {
            log(LogLevel.WARN, formatMessage(message, args), null);
        }
    }
    
    @Override
    public void warn(String message, Throwable throwable) {
        if (isWarnEnabled()) {
            log(LogLevel.WARN, message, throwable);
        }
    }
    
    @Override
    public void error(String message) {
        if (isErrorEnabled()) {
            log(LogLevel.ERROR, message, null);
        }
    }
    
    @Override
    public void error(String message, Object... args) {
        if (isErrorEnabled()) {
            log(LogLevel.ERROR, formatMessage(message, args), null);
        }
    }
    
    @Override
    public void error(String message, Throwable throwable) {
        if (isErrorEnabled()) {
            log(LogLevel.ERROR, message, throwable);
        }
    }
    
    /**
     * Internal logging method that routes to SLF4J or built-in logging
     */
    private void log(LogLevel level, String message, Throwable throwable) {
        if (useSlf4j && slf4jLogger != null) {
            logToSlf4j(level, message, throwable);
        } else {
            logToConsole(level, message, throwable);
        }
    }
    
    /**
     * Log to SLF4J backend
     */
    private void logToSlf4j(LogLevel level, String message, Throwable throwable) {
        switch (level) {
            case TRACE:
                if (throwable != null) {
                    slf4jLogger.trace(message, throwable);
                } else {
                    slf4jLogger.trace(message);
                }
                break;
            case DEBUG:
                if (throwable != null) {
                    slf4jLogger.debug(message, throwable);
                } else {
                    slf4jLogger.debug(message);
                }
                break;
            case INFO:
                if (throwable != null) {
                    slf4jLogger.info(message, throwable);
                } else {
                    slf4jLogger.info(message);
                }
                break;
            case WARN:
                if (throwable != null) {
                    slf4jLogger.warn(message, throwable);
                } else {
                    slf4jLogger.warn(message);
                }
                break;
            case ERROR:
                if (throwable != null) {
                    slf4jLogger.error(message, throwable);
                } else {
                    slf4jLogger.error(message);
                }
                break;
        }
    }
    
    /**
     * Log to console (fallback when SLF4J is not available)
     */
    private void logToConsole(LogLevel level, String message, Throwable throwable) {
        String dateFormatPattern = MervLoggerConfig.getDateFormat();
        SimpleDateFormat dateFormat = new SimpleDateFormat(dateFormatPattern);
        String timestamp = dateFormat.format(new Date());
        String levelName = level.getName();
        
        // Check for custom format pattern
        String formatPattern = MervLoggerConfig.getFormatPattern();
        String logMessage;
        
        if (formatPattern != null && !formatPattern.isEmpty()) {
            // Use custom format pattern
            logMessage = formatPattern
                .replace("%d", timestamp)
                .replace("%date", timestamp)
                .replace("%level", levelName)
                .replace("%logger", name)
                .replace("%msg", message)
                .replace("%message", message)
                .replace("%n", System.lineSeparator());
        } else {
            // Default format
            logMessage = String.format("[%s] %s %s - %s", timestamp, levelName, name, message);
        }
        
        PrintWriter writer = null;
        if (level == LogLevel.ERROR || level == LogLevel.WARN) {
            System.err.println(logMessage);
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                writer = new PrintWriter(sw);
                throwable.printStackTrace(writer);
                System.err.println(sw.toString());
            }
        } else {
            System.out.println(logMessage);
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                writer = new PrintWriter(sw);
                throwable.printStackTrace(writer);
                System.out.println(sw.toString());
            }
        }
        
        if (writer != null) {
            writer.close();
        }
    }
    
    /**
     * Format message with placeholders (simple {} replacement)
     */
    private String formatMessage(String message, Object... args) {
        if (message == null || args == null || args.length == 0) {
            return message;
        }
        
        String result = message;
        for (Object arg : args) {
            int index = result.indexOf("{}");
            if (index != -1) {
                String argStr = arg != null ? arg.toString() : "null";
                result = result.substring(0, index) + argStr + result.substring(index + 2);
            } else {
                break; // No more placeholders
            }
        }
        return result;
    }
}

