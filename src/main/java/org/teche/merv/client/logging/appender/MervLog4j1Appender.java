package org.teche.merv.client.logging.appender;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;
import org.teche.merv.client.logging.MervLogAppender;

/**
 * Log4j 1.x appender — forwards lines to Merv-Logs based on {@code merv.properties}.
 *
 * <pre>{@code
 * log4j.appender.MERV=org.teche.merv.client.logging.appender.MervLog4j1Appender
 * log4j.rootLogger=INFO, stdout, MERV
 * }</pre>
 */
public class MervLog4j1Appender extends AppenderSkeleton {

    @Override
    protected void append(LoggingEvent event) {
        if (event == null) {
            return;
        }
        MervLogAppender.append(
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getRenderedMessage(),
                event.getThrowableInformation() != null
                        ? event.getThrowableInformation().getThrowable()
                        : null);
    }

    @Override
    public void close() {
        /* no resources */
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
