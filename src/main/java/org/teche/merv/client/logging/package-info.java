/**
 * MERV Client Logging Package
 * 
 * <p>This package provides a structured logging library similar to log4j and SLF4J
 * for use in MERV client applications.
 * 
 * <h2>Quick Start</h2>
 * <pre>
 * // Get a logger instance
 * MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);
 * 
 * // Configure log level
 * MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);
 * 
 * // Use the logger
 * logger.info("Application started");
 * logger.debug("Processing user: {}", username);
 * logger.error("Failed to connect", exception);
 * </pre>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Similar API to log4j and SLF4J</li>
 *   <li>Multiple log levels: TRACE, DEBUG, INFO, WARN, ERROR</li>
 *   <li>Automatic SLF4J integration if available</li>
 *   <li>Fallback to console logging if SLF4J is not available</li>
 *   <li>Message formatting with {} placeholders</li>
 *   <li>Exception logging support</li>
 * </ul>
 * 
 * @author MERV Client Team
 * @version 3.0.0
 */
package org.teche.merv.client.logging;

