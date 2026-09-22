package org.teche.merv.client.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.config.MervPropertiesLocator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes {@link MervLogRecord} lines as NDJSON under {@code {reportRoot}/log/}
 * (5-minute UTC buckets), matching the JS Merv-Logs layout for {@code merv-logs.html}.
 */
public final class MervLogSink {

    public static final String MERV_LOG_DIR = "log";
    public static final long MERV_LOG_CHUNK_MS = 5L * 60L * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH-mm")
            .withZone(ZoneOffset.UTC);

    private static volatile String cachedReportRoot;
    private static final Object WRITE_LOCK = new Object();

    private MervLogSink() {}

    /** Override report root used for log writes (tests / handlers). */
    public static void setMervLogsPath(String reportRootOrNull) {
        if (reportRootOrNull == null || reportRootOrNull.isBlank()) {
            cachedReportRoot = null;
            return;
        }
        File f = new File(reportRootOrNull.trim());
        String path = f.getAbsolutePath();
        if (MERV_LOG_DIR.equalsIgnoreCase(f.getName())) {
            cachedReportRoot = f.getParentFile() != null ? f.getParentFile().getAbsolutePath() : path;
        } else {
            cachedReportRoot = path;
        }
    }

    public static void resetMervLogsPathCache() {
        cachedReportRoot = null;
    }

    public static String resolveMervReportRootForLogs() {
        if (cachedReportRoot != null) {
            return cachedReportRoot;
        }
        try {
            java.util.Properties merv = MervPropertiesLocator.loadMervProperties();
            if (merv != null) {
                String folder = merv.getProperty("merv.report.folder");
                if (folder != null && !folder.isBlank()) {
                    java.io.File base = MervPropertiesLocator.findMervPropertiesFile();
                    java.io.File parent = base != null ? base.getParentFile() : new java.io.File(System.getProperty("user.dir"));
                    java.io.File f = new java.io.File(folder.trim());
                    if (!f.isAbsolute()) {
                        f = new java.io.File(parent, folder.trim());
                    }
                    cachedReportRoot = f.getAbsolutePath();
                    return cachedReportRoot;
                }
            }
            String folder = MervConfig.getReportFolder();
            if (folder != null && !folder.isBlank()) {
                cachedReportRoot = new File(folder).getAbsolutePath();
                return cachedReportRoot;
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        cachedReportRoot = new File(System.getProperty("user.dir"), "merv-reports").getAbsolutePath();
        return cachedReportRoot;
    }

    public static File resolveMervLogDir(String reportRoot) {
        return new File(reportRoot, MERV_LOG_DIR);
    }

    public static Instant mervLogBucketStart(Instant instant) {
        long ms = instant.toEpochMilli();
        return Instant.ofEpochMilli((ms / MERV_LOG_CHUNK_MS) * MERV_LOG_CHUNK_MS);
    }

    public static String mervLogFileNameForTime(Instant instant) {
        return "merv-" + FILE_STAMP.format(mervLogBucketStart(instant)) + ".ndjson";
    }

    public static File resolveMervLogFileForTime(Instant instant, String reportRoot) {
        return new File(resolveMervLogDir(reportRoot), mervLogFileNameForTime(instant));
    }

    /** Append one NDJSON line and refresh {@code log/manifest.json}. Also forwards when {@code merv.log.server} is set. */
    public static void appendMervLogRecord(MervLogRecord record) {
        appendMervLogRecord(record, true);
    }

    /**
     * @param forwardRemote when false, skip {@code merv.log.server} POST (used by ingest handlers).
     */
    public static void appendMervLogRecord(MervLogRecord record, boolean forwardRemote) {
        if (record == null) {
            return;
        }
        boolean fileOn = MervLoggerConfig.isFileLoggingEnabled();
        String remote = MervLoggerConfig.getLogServerUrl();
        boolean hasRemote = forwardRemote && remote != null && !remote.isBlank();
        if (!fileOn && !hasRemote) {
            return;
        }
        // Stamp project name only when sending to a shared log server.
        if (hasRemote) {
            stampProject(record);
        }
        // Always keep a local copy in this project first; remote forward is async.
        if (fileOn || hasRemote) {
            appendMervLogRecordLocal(record, null);
        }
        if (hasRemote) {
            MervLogRemoteForwarder.forward(record, remote);
        }
    }

    /** Write NDJSON only under {@code reportRoot} (no remote forward). */
    public static void appendMervLogRecordLocal(MervLogRecord record, String reportRootOverride) {
        if (record == null) {
            return;
        }
        synchronized (WRITE_LOCK) {
            try {
                String reportRoot = reportRootOverride != null && !reportRootOverride.isBlank()
                        ? new File(reportRootOverride).getAbsolutePath()
                        : resolveMervReportRootForLogs();
                Instant when = Instant.now();
                if (record.getTs() != null && !record.getTs().isBlank()) {
                    try {
                        when = Instant.parse(record.getTs());
                    } catch (Exception ignored) {
                        when = Instant.now();
                    }
                } else {
                    record.setTs(Instant.now().toString());
                }
                File file = resolveMervLogFileForTime(when, reportRoot);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                String line = MAPPER.writeValueAsString(record) + "\n";
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                    w.write(line);
                }
                writeMervLogManifest(reportRoot);
            } catch (Exception ignored) {
                /* never break the test run for logging I/O */
            }
        }
    }

    private static void stampProject(MervLogRecord record) {
        if (record.getProject() != null && !record.getProject().isBlank()) {
            return;
        }
        String source = MervLoggerConfig.getLogSource();
        if (source != null && !source.isBlank()) {
            record.setProject(source.trim());
            return;
        }
        // Fallback: project folder name (user.dir)
        try {
            String dir = System.getProperty("user.dir");
            if (dir != null && !dir.isBlank()) {
                File f = new File(dir);
                String name = f.getName();
                if (name != null && !name.isBlank()) {
                    record.setProject(name);
                }
            }
        } catch (Exception ignored) {
            /* ignore */
        }
    }

    public static void writeMervLogManifest(String reportRoot) {
        try {
            File logDir = resolveMervLogDir(reportRoot);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            List<String> files = listMervLogFiles(reportRoot);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", files);
            payload.put("updatedAt", Instant.now().toString());
            File manifest = new File(logDir, "manifest.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifest, payload);
        } catch (Exception ignored) {
            /* ignore */
        }
    }

    /** List {@code merv-*.ndjson} under {@code log/}, oldest → newest. */
    public static List<String> listMervLogFiles(String reportRoot) {
        List<String> names = new ArrayList<>();
        File logDir = resolveMervLogDir(reportRoot);
        try {
            if (logDir.isDirectory()) {
                String[] listed = logDir.list((dir, name) ->
                        name != null && name.toLowerCase(Locale.ROOT).startsWith("merv-")
                                && name.toLowerCase(Locale.ROOT).endsWith(".ndjson"));
                if (listed != null) {
                    names.addAll(Arrays.asList(listed));
                }
            }
        } catch (Exception ignored) {
            /* ignore */
        }
        names.sort(String::compareTo);
        return names;
    }

    /** Ensure {@code merv-logs.html} exists under the report root (from classpath). */
    public static void ensureMervLogsPage(String reportRoot) {
        try {
            File dest = new File(reportRoot, "merv-logs.html");
            boolean needsWrite = true;
            if (dest.isFile()) {
                String cur = Files.readString(dest.toPath(), StandardCharsets.UTF_8);
                if (cur.contains("merv-logs-page-v13") && cur.contains("MERV_LOGS_QUERY_API")) {
                    needsWrite = false;
                }
            }
            if (!needsWrite) {
                return;
            }
            try (var in = MervLogSink.class.getClassLoader().getResourceAsStream("merv-logs.html")) {
                if (in == null) {
                    return;
                }
                byte[] bytes = in.readAllBytes();
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.write(dest.toPath(), bytes);
            }
            writeMervLogManifest(reportRoot);
        } catch (Exception ignored) {
            /* best effort */
        }
    }
}
