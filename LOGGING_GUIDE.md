# MERV Client Logging Library Guide

## Overview

The MERV Client Logging Library provides a structured logging API similar to log4j and SLF4J. It offers a familiar interface for developers who have used these popular logging frameworks.

## Features

- ✅ **Familiar API**: Similar to log4j and SLF4J
- ✅ **Multiple Log Levels**: TRACE, DEBUG, INFO, WARN, ERROR
- ✅ **SLF4J Integration**: Automatically uses SLF4J if available
- ✅ **Fallback Support**: Console logging if SLF4J is not available
- ✅ **Message Formatting**: Support for `{}` placeholders
- ✅ **Exception Logging**: Built-in support for logging exceptions
- ✅ **Thread-Safe**: Safe for use in multi-threaded environments

## Quick Start

### 1. Configure Logging (Optional)

Create a `mervlogger.properties` file in your `src/main/resources` folder:

```properties
# Set global log level
merv.logger.level=DEBUG

# Use SLF4J backend if available
merv.logger.use.slf4j=true
```

See [Configuration](#configuration) section for all available options.

### 2. Get a Logger Instance

```java
import org.teche.merv.client.logging.MervLogger;
import org.teche.merv.client.logging.MervLoggerFactory;

// Get logger by class (recommended)
MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);

// Get logger by name
MervLogger logger = MervLoggerFactory.getLogger("com.example.MyClass");
```

### 3. Use the Logger

```java
// Simple logging
logger.info("Application started");
logger.debug("Debug information");
logger.warn("Warning message");
logger.error("Error message");

// Logging with parameters (SLF4J style)
logger.info("User {} logged in", username);
logger.debug("Processing request for user: {} with ID: {}", username, userId);

// Logging exceptions
try {
    performOperation();
} catch (Exception e) {
    logger.error("Operation failed", e);
}
```

## Configuration

The MERV Logger can be configured via a `mervlogger.properties` file placed in your `src/main/resources` folder.

### Properties File Location

Create `src/main/resources/mervlogger.properties` with your configuration:

```properties
# Global log level (TRACE, DEBUG, INFO, WARN, ERROR)
merv.logger.level=INFO

# Use SLF4J backend if available (true/false)
merv.logger.use.slf4j=true

# Custom log format pattern (optional)
merv.logger.format.pattern=[%d] %level %logger - %msg

# Date format pattern (optional)
merv.logger.date.format=yyyy-MM-dd HH:mm:ss.SSS
```

### Configuration Properties

| Property | Description | Default | Values |
|----------|-------------|---------|--------|
| `merv.logger.level` | Global log level | `INFO` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `merv.logger.use.slf4j` | Use SLF4J backend if available | `true` | `true`, `false` |
| `merv.logger.format.pattern` | Custom log format pattern | `[%d] %level %logger - %msg` | See format placeholders below |
| `merv.logger.date.format` | Date format pattern | `yyyy-MM-dd HH:mm:ss.SSS` | Java SimpleDateFormat patterns |

### Format Pattern Placeholders

When using custom format patterns, you can use these placeholders:

- `%d` or `%date` - Timestamp (formatted according to `merv.logger.date.format`)
- `%level` - Log level (TRACE, DEBUG, INFO, WARN, ERROR)
- `%logger` - Logger name (typically class name)
- `%msg` or `%message` - Log message
- `%n` - New line

### Example Configurations

**Development (verbose logging):**
```properties
merv.logger.level=DEBUG
merv.logger.use.slf4j=true
```

**Production (minimal logging):**
```properties
merv.logger.level=WARN
merv.logger.use.slf4j=true
```

**Console-only logging with custom format:**
```properties
merv.logger.level=INFO
merv.logger.use.slf4j=false
merv.logger.format.pattern=[%d] [%level] [%logger] %msg%n
merv.logger.date.format=yyyy-MM-dd HH:mm:ss
```

### Programmatic Configuration

You can also configure logging programmatically (this overrides properties file):

```java
// Set log level programmatically
MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);

// Disable SLF4J integration
MervLoggerFactory.setUseSlf4j(false);

// Reload configuration from properties file
MervLoggerConfig.reloadConfiguration();
```

## Log Levels

The library supports five log levels, ordered from most verbose to least verbose:

1. **TRACE** - Most verbose, detailed debugging information
2. **DEBUG** - Debugging information, typically used during development
3. **INFO** - Informational messages about normal application flow
4. **WARN** - Warning messages for potentially harmful situations
5. **ERROR** - Error messages for error events

### Setting Log Levels

**Via Properties File:**
```properties
merv.logger.level=DEBUG
```

**Programmatically:**
```java
// Set global log level (overrides properties file)
MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);

// Check if a level is enabled (useful for expensive operations)
if (logger.isDebugEnabled()) {
    logger.debug("Expensive debug calculation: {}", expensiveOperation());
}
```

## Usage Examples

### Basic Logging

```java
public class MyService {
    private static final MervLogger logger = MervLoggerFactory.getLogger(MyService.class);
    
    public void doSomething() {
        logger.info("Starting operation");
        // ... your code ...
        logger.info("Operation completed");
    }
}
```

### Logging with Parameters

```java
logger.info("User {} logged in from IP {}", username, ipAddress);
logger.debug("Processing {} items in batch {}", itemCount, batchId);
logger.warn("Low memory: {} MB remaining", availableMemory);
```

### Logging Exceptions

```java
try {
    riskyOperation();
} catch (IOException e) {
    logger.error("Failed to read file: {}", filename, e);
} catch (Exception e) {
    logger.error("Unexpected error occurred", e);
}
```

### Conditional Logging

```java
// Only perform expensive operation if debug is enabled
if (logger.isDebugEnabled()) {
    String details = expensiveDebugCalculation();
    logger.debug("Debug details: {}", details);
}
```

## SLF4J Integration

The MERV Logger automatically integrates with SLF4J if it's available on the classpath. This means:

- If SLF4J is present, logs are routed through SLF4J (respecting your SLF4J configuration)
- If SLF4J is not present, logs are written to console with a simple format

### Disabling SLF4J Integration

```java
// Force use of built-in console logging
MervLoggerFactory.setUseSlf4j(false);
```

## Advanced Usage

### Custom Logger Names

```java
// Use custom logger names for better organization
MervLogger dbLogger = MervLoggerFactory.getLogger("database");
MervLogger apiLogger = MervLoggerFactory.getLogger("api");
MervLogger cacheLogger = MervLoggerFactory.getLogger("cache");
```

### Programmatic Configuration

```java
// Set log level programmatically
MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);

// Check current configuration
LogLevel currentLevel = MervLoggerFactory.getGlobalLogLevel();

// Clear logger cache (useful for testing)
MervLoggerFactory.clearCache();
```

## Best Practices

1. **Use static final logger**: Always declare loggers as `static final`
   ```java
   private static final MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);
   ```

2. **Use appropriate log levels**:
   - TRACE: Very detailed information (rarely used)
   - DEBUG: Development debugging information
   - INFO: General application flow
   - WARN: Potentially problematic situations
   - ERROR: Error events that might still allow the application to continue

3. **Use parameterized messages**: Instead of string concatenation
   ```java
   // Good
   logger.info("User {} logged in", username);
   
   // Bad
   logger.info("User " + username + " logged in");
   ```

4. **Check log level before expensive operations**:
   ```java
   if (logger.isDebugEnabled()) {
       String details = expensiveCalculation();
       logger.debug("Details: {}", details);
   }
   ```

5. **Always log exceptions with context**:
   ```java
   // Good
   logger.error("Failed to process request {}", requestId, exception);
   
   // Bad
   logger.error("Error occurred");
   ```

## Migration from log4j/SLF4J

If you're migrating from log4j or SLF4J, the API is very similar:

### Before (SLF4J)
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger logger = LoggerFactory.getLogger(MyClass.class);
logger.info("Message");
```

### After (MERV Logger)
```java
import org.teche.merv.client.logging.MervLogger;
import org.teche.merv.client.logging.MervLoggerFactory;

private static final MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);
logger.info("Message");
```

The API is nearly identical, making migration straightforward!

## API Reference

### MervLoggerFactory

- `getLogger(Class<?> clazz)` - Get logger for a class
- `getLogger(String name)` - Get logger by name
- `setGlobalLogLevel(LogLevel level)` - Set global log level
- `getGlobalLogLevel()` - Get current global log level
- `setUseSlf4j(boolean useSlf4j)` - Enable/disable SLF4J integration
- `clearCache()` - Clear logger cache

### MervLogger

- `getName()` - Get logger name
- `isTraceEnabled()`, `isDebugEnabled()`, etc. - Check if level is enabled
- `trace()`, `debug()`, `info()`, `warn()`, `error()` - Log at different levels
- All methods support: message only, message with parameters, message with exception

## Troubleshooting

### Logs not appearing

1. Check the log level: `MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG)`
2. Verify SLF4J configuration if using SLF4J backend
3. Check if the logger is enabled: `logger.isDebugEnabled()`

### SLF4J integration not working

1. Verify SLF4J is on the classpath
2. Check SLF4J binding configuration
3. Try disabling SLF4J: `MervLoggerFactory.setUseSlf4j(false)`

## License

This logging library is part of the MERV Client API and follows the same license terms.

