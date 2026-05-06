package org.teche.merv.client.report.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.teche.merv.client.utils.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a consolidated failure-reasons JSON for the local report UI.
 *
 * <p>Rules:
 * - Use only the latest execution (latest {@code merv-report.json} timestamp) for each testcaseName.
 * - Only include failures where the latest status is FAILED and failureReason is non-empty.
 * - If a testcase passed in latest run, it must not appear under older failure reasons.</p>
 */
public final class MervConsolidatedFailureReasonsWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MervConsolidatedFailureReasonsWriter() {}

    public static void write(String reportRoot) {
        if (reportRoot == null || reportRoot.trim().isEmpty()) {
            return;
        }
        try {
            File root = new File(reportRoot.trim());
            if (!root.isDirectory()) {
                return;
            }

            Map<String, LatestCase> latestByName = new HashMap<>();
            File[] children = root.listFiles();
            if (children == null) {
                return;
            }

            for (File runFolder : children) {
                if (runFolder == null || !runFolder.isDirectory()) {
                    continue;
                }
                File json = new File(new File(runFolder, "json"), "merv-report.json");
                if (!json.isFile()) {
                    continue;
                }
                long ts = json.lastModified();
                JsonNode node;
                try {
                    node = MAPPER.readTree(json);
                } catch (Exception ignore) {
                    continue;
                }
                JsonNode cases = node.path("testSuite").path("testCases");
                if (!cases.isArray()) {
                    continue;
                }
                for (JsonNode tc : cases) {
                    String name = safeText(tc, "testcaseName");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    String status = safeText(tc, "status");
                    String reason = safeText(tc, "failureReason");
                    LatestCase cur = latestByName.get(name);
                    if (cur == null || ts > cur.ts) {
                        latestByName.put(name, new LatestCase(name, status, reason, runFolder.getName(), ts));
                    }
                }
            }

            // Group only latest failures
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (LatestCase c : latestByName.values()) {
                String st = c.status == null ? "" : c.status.trim().toUpperCase(Locale.ROOT);
                if (!"FAILED".equals(st)) {
                    continue;
                }
                String r = c.failureReason == null ? "" : c.failureReason.trim();
                if (r.isBlank()) {
                    r = "(No failure message)";
                }
                grouped.computeIfAbsent(r, k -> new ArrayList<>()).add(new LinkedHashMap<>() {{
                    put("testcaseName", c.name);
                    put("runFolder", c.runFolder);
                    put("runTimestampMillis", c.ts);
                }});
            }

            List<Map<String, Object>> reasons = new ArrayList<>();
            grouped.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<Map<String, Object>>> e) -> e.getValue().size()).reversed())
                    .forEach(e -> {
                        Map<String, Object> obj = new LinkedHashMap<>();
                        obj.put("reason", e.getKey());
                        obj.put("count", e.getValue().size());
                        obj.put("testcases", e.getValue());
                        reasons.add(obj);
                    });

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generatedAtMillis", System.currentTimeMillis());
            out.put("failureReasons", reasons);

            File outFile = new File(root, "consolidated-failure-reasons.json");
            String jsonOut = MAPPER.writeValueAsString(out);
            FileUtils.writeFile(outFile.getAbsolutePath(), jsonOut);
        } catch (Exception ignore) {
            // best effort; consolidated view is optional
        }
    }

    private static String safeText(JsonNode obj, String key) {
        if (obj == null) return null;
        JsonNode n = obj.get(key);
        if (n == null || n.isNull()) return null;
        return n.asText();
    }

    private static final class LatestCase {
        final String name;
        final String status;
        final String failureReason;
        final String runFolder;
        final long ts;

        LatestCase(String name, String status, String failureReason, String runFolder, long ts) {
            this.name = name;
            this.status = status;
            this.failureReason = failureReason;
            this.runFolder = runFolder;
            this.ts = ts;
        }
    }
}

