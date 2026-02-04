package org.teche.merv.client.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory class for creating MervLogger instances, similar to log4j LoggerFactory and SLF4J LoggerFactory.
 * 
 * <p>Configuration can be done via mervlogger.properties file in the resources folder.
 * The factory automatically loads configuration on first use.
 * 
 * <p>Usage example:
 * <pre>
 * // Get logger by class
 * MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);
 * 
 * // Get logger by name
 * MervLogger logger = MervLoggerFactory.getLogger("com.example.MyClass");
 * 
 * // Configure global log level programmatically (overrides properties file)
 * MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);
 * </pre>
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
public class MervLoggerFactory {
    
    private static final ConcurrentMap<String, MervLogger> LOGGER_CACHE = new ConcurrentHashMap<>();
    private static volatile LogLevel globalLogLevel = LogLevel.INFO;
    private static volatile boolean useSlf4j = true;
    private static volatile boolean configLoaded = false;
    
    /**
     * Private constructor to prevent instantiation
     */
    private MervLoggerFactory() {
        // Factory class, no instantiation
    }
    
    /**
     * Get a logger instance for the given class
     * @param clazz the class to get a logger for
     * @return a MervLogger instance
     */
    public static MervLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
    
    /**
     * Get a logger instance for the given name
     * @param name the logger name (typically the fully qualified class name)
     * @return a MervLogger instance
     */
    public static MervLogger getLogger(String name) {
        // Load configuration on first use
        if (!configLoaded) {
            synchronized (MervLoggerFactory.class) {
                if (!configLoaded) {
                    MervLoggerConfig.loadConfiguration();
                    configLoaded = true;
                }
            }
        }
        return LOGGER_CACHE.computeIfAbsent(name, MervLoggerImpl::new);
    }
    
    /**
     * Set the global log level for all loggers
     * @param level the log level to set
     */
    public static void setGlobalLogLevel(LogLevel level) {
        if (level != null) {
            globalLogLevel = level;
        }
    }
    
    /**
     * Get the current global log level
     * @return the current global log level
     */
    public static LogLevel getGlobalLogLevel() {
        return globalLogLevel;
    }
    
    /**
     * Set whether to use SLF4J backend if available
     * @param useSlf4j true to use SLF4J, false to use built-in logging
     */
    public static void setUseSlf4j(boolean useSlf4j) {
        MervLoggerFactory.useSlf4j = useSlf4j;
    }
    
    /**
     * Check if SLF4J backend is enabled
     * @return true if SLF4J is enabled
     */
    public static boolean isUseSlf4j() {
        return useSlf4j;
    }
    
    /**
     * Clear the logger cache (useful for testing or reconfiguration)
     */
    public static void clearCache() {
        LOGGER_CACHE.clear();
    }
    
    /**
     * Get the number of cached loggers
     * @return the number of cached loggers
     */
    public static int getCacheSize() {
        return LOGGER_CACHE.size();
    }
}

