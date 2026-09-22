package org.teche.merv.client.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.teche.merv.client.logging.appender.MervJulLogHandler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MervLogAppenderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MervLogSink.resetMervLogsPathCache();
        MervLogSink.setMervLogsPath(tempDir.toString());
        MervLoggerConfig.setFileLoggingEnabled(true);
        MervLoggerConfig.setLogServerUrl(null);
        MervLoggerFactory.setGlobalLogLevel(LogLevel.INFO);
    }

    @AfterEach
    void tearDown() {
        MervLogSink.resetMervLogsPathCache();
        MervLoggerConfig.reloadConfiguration();
    }

    @Test
    void appendWritesNdjsonWhenFileLoggingEnabled() throws Exception {
        MervLogAppender.append(LogLevel.INFO, "com.example.Demo", "hello from appender", null);

        File logDir = MervLogSink.resolveMervLogDir(tempDir.toString());
        assertTrue(logDir.isDirectory());
        List<String> files = MervLogSink.listMervLogFiles(tempDir.toString());
        assertFalse(files.isEmpty());
        String content = Files.readString(new File(logDir, files.get(files.size() - 1)).toPath());
        assertTrue(content.contains("hello from appender"));
        assertTrue(content.contains("com.example.Demo"));
    }

    @Test
    void appendSkipsWhenFileAndRemoteDisabled() throws Exception {
        MervLoggerConfig.setFileLoggingEnabled(false);
        MervLoggerConfig.setLogServerUrl(null);

        MervLogAppender.append(LogLevel.INFO, "com.example.Quiet", "should not appear", null);

        File logDir = MervLogSink.resolveMervLogDir(tempDir.toString());
        if (logDir.exists()) {
            assertTrue(MervLogSink.listMervLogFiles(tempDir.toString()).isEmpty());
        }
    }

    @Test
    void julHandlerForwardsToMervLogs() throws Exception {
        Logger logger = Logger.getLogger("com.example.Jul");
        MervJulLogHandler handler = new MervJulLogHandler();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.info("jul line");

        File logDir = MervLogSink.resolveMervLogDir(tempDir.toString());
        List<String> files = MervLogSink.listMervLogFiles(tempDir.toString());
        assertFalse(files.isEmpty());
        String content = Files.readString(new File(logDir, files.get(files.size() - 1)).toPath());
        assertTrue(content.contains("jul line"));
        logger.removeHandler(handler);
    }
}
