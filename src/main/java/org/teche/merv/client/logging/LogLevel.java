package org.teche.merv.client.logging;

/**
 * Enumeration of log levels, similar to log4j and SLF4J.
 * Levels are ordered from most verbose (TRACE) to least verbose (ERROR).
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
public enum LogLevel {
    /**
     * TRACE level - Most verbose, used for detailed debugging information
     */
    TRACE(0, "TRACE"),
    
    /**
     * DEBUG level - Debugging information, typically used during development
     */
    DEBUG(1, "DEBUG"),
    
    /**
     * INFO level - Informational messages about normal application flow
     */
    INFO(2, "INFO"),
    
    /**
     * WARN level - Warning messages for potentially harmful situations
     */
    WARN(3, "WARN"),
    
    /**
     * ERROR level - Error messages for error events that might still allow the application to continue
     */
    ERROR(4, "ERROR");
    
    private final int level;
    private final String name;
    
    LogLevel(int level, String name) {
        this.level = level;
        this.name = name;
    }
    
    /**
     * Returns the numeric value of this log level
     * @return the level value
     */
    public int getLevel() {
        return level;
    }
    
    /**
     * Returns the string name of this log level
     * @return the level name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Checks if this level is enabled compared to the given threshold level
     * @param threshold the threshold level to compare against
     * @return true if this level is enabled (level >= threshold)
     */
    public boolean isEnabled(LogLevel threshold) {
        return this.level >= threshold.level;
    }
    
    /**
     * Parses a string to a LogLevel
     * @param level the string representation of the level (case-insensitive)
     * @return the corresponding LogLevel, or INFO if not found
     */
    public static LogLevel fromString(String level) {
        if (level == null) {
            return INFO;
        }
        try {
            return LogLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}

