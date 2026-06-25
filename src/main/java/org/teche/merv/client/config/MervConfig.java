package org.teche.merv.client.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration class for MervClient that loads settings from merv.properties file.
 * 
 * <p>Configuration file should be placed in the project root as merv.properties.
 * 
 * <p>Example configuration:
 * <pre>
 * merv.server=http://localhost:7777/api/v1
 * merv.username=admin
 * merv.password=password
 * merv.suite_alias=my-test-suite
 * merv.parent_hierarchy=uuid-here  (optional; omit to use Default Project)
 * </pre>
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
public class MervConfig {
    
    private static final String PROPERTIES_FILE = "merv.properties";
    private static final String PROP_SERVER = "merv.server";
    private static final String PROP_USERNAME = "merv.username";
    private static final String PROP_PASSWORD = "merv.password";
    private static final String PROP_API_KEY = "merv.api_key";
    private static final String PROP_SUITE_ALIAS = "merv.suite_alias";
    private static final String PROP_PARENT_HIERARCHY = "merv.parent_hierarchy";
    private static final String PROP_REGRESSION_SUITE = "merv.regression_suite";
    private static final String PROP_SPRINT = "merv.sprint";
    private static final String PROP_EXECUTION_PARALLEL = "merv.execution.parallel";
    private static final String PROP_REPORT_FOLDER = "merv.report.folder";
    /** Default on-disk report root when {@code merv.report.folder} is omitted (aligned with merv-client-js). */
    private static final String DEFAULT_REPORT_FOLDER_NAME = "merv-reports";
    
    private static volatile Properties properties;
    private static volatile boolean initialized = false;
    
    /**
     * Load configuration from merv.properties file
     * @return Properties object with loaded configuration
     * @throws Exception if properties file cannot be loaded
     */
    public static synchronized Properties loadConfiguration() throws Exception {
        if (initialized && properties != null) {
            return properties;
        }
        
        properties = new Properties();
        String mervProperty = System.getProperty("user.dir") + File.separator + PROPERTIES_FILE;
        File propertiesFile = new File(mervProperty);
        
        if (!propertiesFile.exists()) {
            throw new Exception("merv.properties file not available in project root: " + mervProperty);
        }
        
        try (InputStream inputStream = new FileInputStream(propertiesFile)) {
            properties.load(inputStream);
        }
        
        initialized = true;
        return properties;
    }
    
    /**
     * Get a property value
     * @param key the property key
     * @return the property value, or null if not found
     */
    public static String getProperty(String key) {
        if (!initialized) {
            try {
                loadConfiguration();
            } catch (Exception e) {
                return null;
            }
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
     * Get the suite alias from configuration
     * @return the suite alias, or null if not configured
     */
    public static String getSuiteAlias() {
        return getProperty(PROP_SUITE_ALIAS);
    }
    
    /**
     * Get the server URL from configuration
     * @return the server URL, or null if not configured
     */
    public static String getServer() {
        return getProperty(PROP_SERVER);
    }
    
    /**
     * Get the username from configuration
     * @return the username, or null if not configured
     */
    public static String getUsername() {
        return getProperty(PROP_USERNAME);
    }
    
    /**
     * Get the password from configuration
     * @return the password, or null if not configured
     */
    public static String getPassword() {
        return getProperty(PROP_PASSWORD);
    }
    
    /**
     * Get the API key from configuration
     * @return the API key, or null if not configured
     */
    public static String getApiKey() {
        return getProperty(PROP_API_KEY);
    }
    
    /**
     * Reload configuration from properties file
     */
    public static synchronized void reloadConfiguration() throws Exception {
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
    
    /**
     * Get the report folder path from configuration.
     * When {@code merv.report.folder} is not set, defaults to {@code merv-reports/} in the project root
     * (same convention as merv-client-js).
     *
     * @return the report folder path (always ends with a file separator)
     */
    public static String getReportFolder() {
        String reportFolder = getProperty(PROP_REPORT_FOLDER);
        if (reportFolder == null || reportFolder.trim().isEmpty()) {
            return System.getProperty("user.dir") + File.separator + DEFAULT_REPORT_FOLDER_NAME + File.separator;
        }

        return normalizeReportFolderPath(reportFolder.trim());
    }

    private static String normalizeReportFolderPath(String reportFolder) {
        String normalizedPath = reportFolder;
        if (!normalizedPath.endsWith(File.separator)) {
            normalizedPath += File.separator;
        }

        if (!new File(normalizedPath).isAbsolute()) {
            normalizedPath = System.getProperty("user.dir") + File.separator + normalizedPath;
        }

        return normalizedPath;
    }
}

