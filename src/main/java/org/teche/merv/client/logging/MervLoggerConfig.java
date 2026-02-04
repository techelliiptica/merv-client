package org.teche.merv.client.logging;

import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration class for MervLogger that loads settings from mervlogger.properties file.
 * 
 * <p>Configuration file should be placed in the resources folder as mervlogger.properties.
 * 
 * <p>Example configuration:
 * <pre>
 * # Global log level (TRACE, DEBUG, INFO, WARN, ERROR)
 * merv.logger.level=INFO
 * 
 * # Use SLF4J backend if available (true/false)
 * merv.logger.use.slf4j=true
 * 
 * # Log format pattern (optional)
 * merv.logger.format.pattern=%d{yyyy-MM-dd HH:mm:ss.SSS} [%level] %logger - %msg
 * </pre>
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
public class MervLoggerConfig {
    
    private static final String PROPERTIES_FILE = "mervlogger.properties";
    private static final String PROP_LEVEL = "merv.logger.level";
    private static final String PROP_USE_SLF4J = "merv.logger.use.slf4j";
    private static final String PROP_FORMAT_PATTERN = "merv.logger.format.pattern";
    private static final String PROP_DATE_FORMAT = "merv.logger.date.format";
    
    private static volatile Properties properties;
    private static volatile boolean initialized = false;
    
    /**
     * Load configuration from mervlogger.properties file
     */
    public static synchronized void loadConfiguration() {
        if (initialized) {
            return;
        }
        
        properties = new Properties();
        InputStream inputStream = null;
        
        try {
            // Try to load from classpath
            inputStream = MervLoggerConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE);
            
            if (inputStream != null) {
                properties.load(inputStream);
            } else {
                // Try to load from system classloader
                inputStream = ClassLoader.getSystemResourceAsStream(PROPERTIES_FILE);
                if (inputStream != null) {
                    properties.load(inputStream);
                }
            }
            
            // Apply configuration
            applyConfiguration();
            
        } catch (Exception e) {
            // If properties file doesn't exist or can't be loaded, use defaults
            System.err.println("Warning: Could not load mervlogger.properties, using defaults: " + e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        
        initialized = true;
    }
    
    /**
     * Apply configuration from properties to MervLoggerFactory
     */
    private static void applyConfiguration() {
        // Set log level
        String levelStr = properties.getProperty(PROP_LEVEL);
        if (levelStr != null && !levelStr.trim().isEmpty()) {
            try {
                LogLevel level = LogLevel.fromString(levelStr.trim());
                MervLoggerFactory.setGlobalLogLevel(level);
            } catch (Exception e) {
                System.err.println("Warning: Invalid log level in properties: " + levelStr);
            }
        }
        
        // Set SLF4J usage
        String useSlf4jStr = properties.getProperty(PROP_USE_SLF4J);
        if (useSlf4jStr != null && !useSlf4jStr.trim().isEmpty()) {
            try {
                boolean useSlf4j = Boolean.parseBoolean(useSlf4jStr.trim());
                MervLoggerFactory.setUseSlf4j(useSlf4j);
            } catch (Exception e) {
                System.err.println("Warning: Invalid boolean value for use.slf4j: " + useSlf4jStr);
            }
        }
    }
    
    /**
     * Get a property value
     * @param key the property key
     * @return the property value, or null if not found
     */
    public static String getProperty(String key) {
        if (!initialized) {
            loadConfiguration();
        }
        return properties != null ? properties.getProperty(key) : null;
    }
    
    /**
     * Get a property value with default
     * @param key the property key
     * @param defaultValue the default value if not found
     * @return the property value or default
     */
    public static String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * Get the log format pattern
     * @return the format pattern, or null if not configured
     */
    public static String getFormatPattern() {
        return getProperty(PROP_FORMAT_PATTERN);
    }
    
    /**
     * Get the date format pattern
     * @return the date format pattern, or default if not configured
     */
    public static String getDateFormat() {
        return getProperty(PROP_DATE_FORMAT, "yyyy-MM-dd HH:mm:ss.SSS");
    }
    
    /**
     * Reload configuration from properties file
     */
    public static synchronized void reloadConfiguration() {
        initialized = false;
        loadConfiguration();
    }
    
    /**
     * Check if configuration has been loaded
     * @return true if configuration is loaded
     */
    public static boolean isInitialized() {
        return initialized;
    }
}

