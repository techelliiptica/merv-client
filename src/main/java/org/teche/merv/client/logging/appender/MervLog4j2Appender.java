package org.teche.merv.client.logging.appender;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.teche.merv.client.logging.MervLogAppender;

/**
 * Log4j 2 appender — forwards lines to Merv-Logs based on {@code merv.properties}.
 *
 * <pre>{@code
 * <!-- log4j2.xml -->
 * <MervLog name="MervLog"/>
 * <Loggers>
 *   <Root level="info">
 *     <AppenderRef ref="MervLog"/>
 *   </Root>
 * </Loggers>
 * }</pre>
 */
@Plugin(name = "MervLog", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class MervLog4j2Appender extends AbstractAppender {

    protected MervLog4j2Appender(String name, Filter filter, Layout<?> layout, boolean ignoreExceptions) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
    }

    @PluginFactory
    public static MervLog4j2Appender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Filter") Filter filter,
            @PluginElement("Layout") Layout<?> layout) {
        if (name == null) {
            LOGGER.error("MervLog appender requires a name");
            return null;
        }
        return new MervLog4j2Appender(name, filter, layout, true);
    }

    @Override
    public void append(LogEvent event) {
        if (event == null) {
            return;
        }
        MervLogAppender.append(
                event.getLevel().name(),
                event.getLoggerName(),
                event.getMessage().getFormattedMessage(),
                event.getThrown());
    }
}
