package org.teche.merv.client.logging;

/**
 * Example demonstrating how to use MervLogger, similar to log4j and SLF4J.
 * 
 * <p>This class shows various ways to use the MERV logging library:
 * <ul>
 *   <li>Getting a logger instance</li>
 *   <li>Configuring log levels</li>
 *   <li>Logging at different levels</li>
 *   <li>Logging with parameters</li>
 *   <li>Logging exceptions</li>
 * </ul>
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
public class LoggingExample {
    
    // Get logger instance - similar to log4j/slf4j
    private static final MervLogger logger = MervLoggerFactory.getLogger(LoggingExample.class);
    
    public static void main(String[] args) {
        // Configure global log level (optional, defaults to INFO)
        MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);
        
        // Example 1: Simple logging
        logger.info("Application started");
        logger.debug("Debug information");
        logger.warn("This is a warning");
        logger.error("This is an error");
        
        // Example 2: Logging with parameters (similar to SLF4J)
        String username = "john.doe";
        int userId = 12345;
        logger.info("User {} (ID: {}) logged in", username, userId);
        logger.debug("Processing request for user: {}", username);
        
        // Example 3: Logging exceptions
        try {
            performOperation();
        } catch (Exception e) {
            logger.error("Failed to perform operation", e);
        }
        
        // Example 4: Conditional logging (check if level is enabled)
        if (logger.isDebugEnabled()) {
            logger.debug("Expensive debug calculation: {}", expensiveOperation());
        }
        
        // Example 5: Different log levels
        logger.trace("Very detailed trace information");
        logger.debug("Debug information for development");
        logger.info("General informational message");
        logger.warn("Warning: Something might be wrong");
        logger.error("Error: Something went wrong");
        
        // Example 6: Get logger by name instead of class
        MervLogger customLogger = MervLoggerFactory.getLogger("com.example.MyCustomLogger");
        customLogger.info("Using a custom logger name");
    }
    
    private static void performOperation() throws Exception {
        throw new RuntimeException("Simulated error");
    }
    
    private static String expensiveOperation() {
        // Simulate expensive operation
        return "Result of expensive operation";
    }
}

