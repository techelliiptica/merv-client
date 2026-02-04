package org.teche.merv.client.logging;

/**
 * MERV Logger interface, similar to log4j and SLF4J Logger.
 * Provides methods for logging at different levels (TRACE, DEBUG, INFO, WARN, ERROR).
 * 
 * <p>Usage example:
 * <pre>
 * MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);
 * logger.info("Application started");
 * logger.debug("Processing user: {}", username);
 * logger.error("Failed to connect", exception);
 * </pre>
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
public interface MervLogger {
    
    /**
     * Returns the name of this logger
     * @return the logger name
     */
    String getName();
    
    /**
     * Checks if TRACE level is enabled
     * @return true if TRACE level is enabled
     */
    boolean isTraceEnabled();
    
    /**
     * Checks if DEBUG level is enabled
     * @return true if DEBUG level is enabled
     */
    boolean isDebugEnabled();
    
    /**
     * Checks if INFO level is enabled
     * @return true if INFO level is enabled
     */
    boolean isInfoEnabled();
    
    /**
     * Checks if WARN level is enabled
     * @return true if WARN level is enabled
     */
    boolean isWarnEnabled();
    
    /**
     * Checks if ERROR level is enabled
     * @return true if ERROR level is enabled
     */
    boolean isErrorEnabled();
    
    /**
     * Log a message at TRACE level
     * @param message the message to log
     */
    void trace(String message);
    
    /**
     * Log a message at TRACE level with parameters
     * @param message the message template (supports {} placeholders)
     * @param args the arguments to substitute in the message
     */
    void trace(String message, Object... args);
    
    /**
     * Log a message at TRACE level with an exception
     * @param message the message to log
     * @param throwable the exception to log
     */
    void trace(String message, Throwable throwable);
    
    /**
     * Log a message at DEBUG level
     * @param message the message to log
     */
    void debug(String message);
    
    /**
     * Log a message at DEBUG level with parameters
     * @param message the message template (supports {} placeholders)
     * @param args the arguments to substitute in the message
     */
    void debug(String message, Object... args);
    
    /**
     * Log a message at DEBUG level with an exception
     * @param message the message to log
     * @param throwable the exception to log
     */
    void debug(String message, Throwable throwable);
    
    /**
     * Log a message at INFO level
     * @param message the message to log
     */
    void info(String message);
    
    /**
     * Log a message at INFO level with parameters
     * @param message the message template (supports {} placeholders)
     * @param args the arguments to substitute in the message
     */
    void info(String message, Object... args);
    
    /**
     * Log a message at INFO level with an exception
     * @param message the message to log
     * @param throwable the exception to log
     */
    void info(String message, Throwable throwable);
    
    /**
     * Log a message at WARN level
     * @param message the message to log
     */
    void warn(String message);
    
    /**
     * Log a message at WARN level with parameters
     * @param message the message template (supports {} placeholders)
     * @param args the arguments to substitute in the message
     */
    void warn(String message, Object... args);
    
    /**
     * Log a message at WARN level with an exception
     * @param message the message to log
     * @param throwable the exception to log
     */
    void warn(String message, Throwable throwable);
    
    /**
     * Log a message at ERROR level
     * @param message the message to log
     */
    void error(String message);
    
    /**
     * Log a message at ERROR level with parameters
     * @param message the message template (supports {} placeholders)
     * @param args the arguments to substitute in the message
     */
    void error(String message, Object... args);
    
    /**
     * Log a message at ERROR level with an exception
     * @param message the message to log
     * @param throwable the exception to log
     */
    void error(String message, Throwable throwable);
}

