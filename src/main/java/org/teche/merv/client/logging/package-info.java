/**
 * MERV Client Logging Package
 *
 * <p>Structured logging similar to log4j / SLF4J, plus optional <strong>Merv-Logs</strong>
 * NDJSON under {@code {merv.report.folder}/log/} for the live console page.
 *
 * <h2>Quick Start</h2>
 * <pre>
 * MervLogger logger = MervLoggerFactory.getLogger(MyClass.class);
 * MervLoggerFactory.setGlobalLogLevel(LogLevel.DEBUG);
 * logger.info("Application started");
 * logger.debug("Processing user: {}", username);
 * logger.error("Failed to connect", exception);
 * </pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>API similar to log4j and SLF4J</li>
 *   <li>Levels: TRACE, DEBUG, INFO, WARN, ERROR</li>
 *   <li>SLF4J when available; otherwise console</li>
 *   <li>{@code {}} message formatting and exception logging</li>
 *   <li>Merv-Logs file sink ({@link org.teche.merv.client.logging.MervLogSink}) for {@code merv-logs.html}</li>
 *   <li>{@link org.teche.merv.client.logging.MervLogContext} for suite / testcase labels</li>
 * </ul>
 *
 * @author MERV Client Team
 * @version 4.0.0
 */
package org.teche.merv.client.logging;
