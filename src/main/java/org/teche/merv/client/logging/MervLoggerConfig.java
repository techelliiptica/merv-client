package org.teche.merv.client.logging;

import org.teche.merv.client.config.MervPropertiesLocator;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration class for MervLogger that loads settings from mervlogger.properties
 * and optionally overlays {@code merv.logger.*} from project-root {@code merv.properties}.
 *
 * <p>Example configuration:
 * <pre>
 * merv.logger.level=INFO
 * merv.logger.use.slf4j=true
 * merv.logger.file=true
 * </pre>
 *
 * @author MERV Client Team
 * @version 4.0.0
 */
public class MervLoggerConfig {

    private static final String PROPERTIES_FILE = "mervlogger.properties";
    private static final String PROP_LEVEL = "merv.logger.level";
    private static final String PROP_USE_SLF4J = "merv.logger.use.slf4j";
    private static final String PROP_FORMAT_PATTERN = "merv.logger.format.pattern";
    private static final String PROP_DATE_FORMAT = "merv.logger.date.format";
    private static final String PROP_FILE = "merv.logger.file";
    private static final String PROP_LOG_SERVER = "merv.log.server";
    private static final String PROP_LOG_SOURCE = "merv.log.source";

    private static volatile Properties properties;
    private static volatile boolean initialized = false;
    private static volatile boolean fileLoggingEnabled = true;
    private static volatile String logServerUrl = null;
    private static volatile String logSource = null;

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
            inputStream = MervLoggerConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE);

            if (inputStream != null) {
                properties.load(inputStream);
            } else {
                inputStream = ClassLoader.getSystemResourceAsStream(PROPERTIES_FILE);
                if (inputStream != null) {
                    properties.load(inputStream);
                }
            }

            applyConfiguration();
            mergeFromMervProperties();

        } catch (Exception e) {
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

    private static void applyConfiguration() {
        String levelStr = properties.getProperty(PROP_LEVEL);
        if (levelStr != null && !levelStr.trim().isEmpty()) {
            try {
                LogLevel level = LogLevel.fromString(levelStr.trim());
                MervLoggerFactory.setGlobalLogLevel(level);
            } catch (Exception e) {
                System.err.println("Warning: Invalid log level in properties: " + levelStr);
            }
        }

        String useSlf4jStr = properties.getProperty(PROP_USE_SLF4J);
        if (useSlf4jStr != null && !useSlf4jStr.trim().isEmpty()) {
            try {
                boolean useSlf4j = Boolean.parseBoolean(useSlf4jStr.trim());
                MervLoggerFactory.setUseSlf4j(useSlf4j);
            } catch (Exception e) {
                System.err.println("Warning: Invalid boolean value for use.slf4j: " + useSlf4jStr);
            }
        }

        applyFileLoggingProperty(properties.getProperty(PROP_FILE));
    }

    private static void mergeFromMervProperties() {
        try {
            Properties merv = MervPropertiesLocator.loadMervProperties();
            if (merv == null) {
                return;
            }
            String levelStr = firstNonBlank(merv.getProperty(PROP_LEVEL), merv.getProperty("merv.log.level"));
            if (levelStr != null) {
                try {
                    MervLoggerFactory.setGlobalLogLevel(LogLevel.fromString(levelStr.trim()));
                } catch (Exception ignored) {
                    /* ignore */
                }
            }
            applyFileLoggingProperty(firstNonBlank(merv.getProperty(PROP_FILE), merv.getProperty("merv.log.file")));
            applyLogServerProperty(firstNonBlank(
                    merv.getProperty(PROP_LOG_SERVER),
                    firstNonBlank(merv.getProperty("merv.logger.server"), merv.getProperty("merv.logs.server"))));
            applyLogSourceProperty(firstNonBlank(
                    merv.getProperty(PROP_LOG_SOURCE),
                    firstNonBlank(
                            merv.getProperty("merv.project"),
                            firstNonBlank(merv.getProperty("merv.logger.source"), merv.getProperty("merv.logs.source")))));
        } catch (Exception ignored) {
            /* optional overlay */
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        if (b != null && !b.trim().isEmpty()) {
            return b;
        }
        return null;
    }

    private static void applyFileLoggingProperty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String v = raw.trim().toLowerCase();
        fileLoggingEnabled = !(v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"));
    }

    private static void applyLogServerProperty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String v = raw.trim().replaceAll("/+$", "");
        if (v.isEmpty()) {
            return;
        }
        if (!v.contains("://")) {
            v = "http://" + v;
        }
        logServerUrl = v;
    }

    private static void applyLogSourceProperty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        logSource = raw.trim();
    }

    /**
     * When true (default), {@link MervLogger} also writes NDJSON under {@code {reportRoot}/log/}.
     */
    public static boolean isFileLoggingEnabled() {
        if (!initialized) {
            loadConfiguration();
        }
        return fileLoggingEnabled;
    }

    public static void setFileLoggingEnabled(boolean enabled) {
        fileLoggingEnabled = enabled;
    }

    /**
     * Base URL of a remote report server ({@code merv.log.server}) that accepts {@code /api/logs/ingest}.
     */
    public static String getLogServerUrl() {
        if (!initialized) {
            loadConfiguration();
        }
        String env = System.getenv("MERV_LOG_SERVER");
        if (env != null && !env.trim().isEmpty()) {
            String v = env.trim().replaceAll("/+$", "");
            if (!v.contains("://")) {
                v = "http://" + v;
            }
            return v;
        }
        return logServerUrl;
    }

    public static void setLogServerUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            logServerUrl = null;
            return;
        }
        applyLogServerProperty(url);
    }

    /** Project name ({@code merv.log.source}) stamped on lines forwarded to a shared log server. */
    public static String getLogSource() {
        if (!initialized) {
            loadConfiguration();
        }
        String env = System.getenv("MERV_LOG_SOURCE");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return logSource;
    }

    public static void setLogSource(String source) {
        logSource = source == null || source.trim().isEmpty() ? null : source.trim();
    }

    public static String getProperty(String key) {
        if (!initialized) {
            loadConfiguration();
        }
        return properties != null ? properties.getProperty(key) : null;
    }

    public static String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    public static String getFormatPattern() {
        return getProperty(PROP_FORMAT_PATTERN);
    }

    public static String getDateFormat() {
        return getProperty(PROP_DATE_FORMAT, "yyyy-MM-dd HH:mm:ss.SSS");
    }

    public static synchronized void reloadConfiguration() {
        initialized = false;
        loadConfiguration();
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
