package org.teche.merv.client.logging.appender;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.teche.merv.client.logging.MervLogAppender;

/**
 * Logback appender for SLF4J — forwards lines to Merv-Logs based on {@code merv.properties}.
 *
 * <pre>{@code
 * <!-- logback.xml -->
 * <appender name="MERV" class="org.teche.merv.client.logging.appender.MervLogbackAppender"/>
 * <root level="INFO">
 *   <appender-ref ref="MERV"/>
 * </root>
 * }</pre>
 */
public class MervLogbackAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        Throwable throwable = null;
        if (event.getThrowableProxy() instanceof ThrowableProxy proxy) {
            throwable = proxy.getThrowable();
        }
        MervLogAppender.append(
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                throwable);
    }
}
