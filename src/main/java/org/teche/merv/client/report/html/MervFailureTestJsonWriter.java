package org.teche.merv.client.report.html;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.utils.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code failure-test.json} alongside {@code merv-report.json} for failed testcases and TEST_DATA steps.
 */
public final class MervFailureTestJsonWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MervFailureTestJsonWriter() {
    }

    /**
     * Builds and writes failure-test.json under {@code {reportFolder}/json/}, and copies to run + report root when complete.
     */
    @SuppressWarnings("unchecked")
    public static void writeFromJsonReport(String reportFolderPath, Map<String, Object> jsonReport) {
        if (reportFolderPath == null || jsonReport == null) {
            return;
        }
        try {
            Map<String, Object> payload = buildFailureTestJson(jsonReport);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            String jsonDir = reportFolderPath + "json" + File.separator;
            new File(jsonDir).mkdirs();
            FileUtils.writeFile(jsonDir + "failure-test.json", json);

            boolean running = Boolean.TRUE.equals(jsonReport.get("running"));
            if (!running) {
                FileUtils.writeFile(reportFolderPath + "failure-test.json", json);
                String reportRoot = MervConfig.getReportFolder();
                if (reportRoot != null && !reportRoot.trim().isEmpty()) {
                    if (!reportRoot.endsWith(File.separator)) {
                        reportRoot = reportRoot + File.separator;
                    }
                    new File(reportRoot).mkdirs();
                    FileUtils.writeFile(reportRoot + "failure-test.json", json);
                }
            }
        } catch (Exception e) {
            System.err.println("Error writing failure-test.json: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> buildFailureTestJson(Map<String, Object> jsonReport) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", jsonReport.getOrDefault("version", "1.0"));
        out.put("exportDate", jsonReport.get("exportDate"));

        Object suiteObj = jsonReport.get("testSuite");
        Map<String, Object> suite = suiteObj instanceof Map
                ? (Map<String, Object>) suiteObj
                : MAPPER.convertValue(suiteObj, Map.class);

        String title = suite != null ? String.valueOf(suite.getOrDefault("title", "")).trim() : "";
        if (title.isEmpty()) {
            title = "Merv Test Suite";
        }
        out.put("suiteTitle", title);
        out.put("running", Boolean.TRUE.equals(jsonReport.get("running")));

        List<Map<String, Object>> failures = new ArrayList<>();
        if (suite != null) {
            Object casesObj = suite.get("testCases");
            List<Map<String, Object>> cases = casesObj instanceof List
                    ? (List<Map<String, Object>>) casesObj
                    : MAPPER.convertValue(casesObj, List.class);
            if (cases != null) {
                for (Map<String, Object> tc : cases) {
                    if (tc == null) {
                        continue;
                    }
                    if ("FAILED".equalsIgnoreCase(normalizeStatus(tc.get("status")))) {
                        failures.add(mapFailedTestcase(tc));
                    }
                }
            }
        }
        out.put("failureCount", failures.size());
        out.put("failures", failures);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapFailedTestcase(Map<String, Object> tc) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String name = String.valueOf(tc.getOrDefault("testcaseName", "")).trim();
        entry.put("testcaseName", name.isEmpty() ? "Unnamed testcase" : name);
        entry.put("status", normalizeStatus(tc.get("status")));

        String reason = tc.get("failureReason") != null
                ? stripAnsi(String.valueOf(tc.get("failureReason"))).trim()
                : null;
        entry.put("failureReason", reason == null || reason.isEmpty() ? null : reason);

        List<String> tags = new ArrayList<>();
        Object tagsObj = tc.get("tags");
        if (tagsObj instanceof List) {
            for (Object t : (List<?>) tagsObj) {
                String tx = String.valueOf(t != null ? t : "").trim();
                if (!tx.isEmpty()) {
                    tags.add(tx);
                }
            }
        }
        entry.put("tags", tags);
        entry.put("startTime", tc.get("startTime"));
        entry.put("endTime", tc.get("endTime"));
        entry.put("executionMachine", tc.get("executionMachine"));

        List<Map<String, Object>> testdataSteps = new ArrayList<>();
        Object stepsObj = tc.get("testSteps");
        if (stepsObj instanceof List) {
            for (Object stepObj : (List<?>) stepsObj) {
                if (!(stepObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> step = (Map<String, Object>) stepObj;
                if (isTestdataStep(step)) {
                    testdataSteps.add(mapTestdataStep(step));
                }
            }
        }
        entry.put("testdataSteps", testdataSteps);
        return entry;
    }

    private static Map<String, Object> mapTestdataStep(Map<String, Object> step) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teststepName", String.valueOf(step.getOrDefault("teststepName", "")));
        out.put("stepType", String.valueOf(step.getOrDefault("stepType", "")));
        out.put("status", normalizeStatus(step.get("status")));
        out.put("testdata", stringOrNull(step.get("testdata")));
        out.put("expected", stringOrNull(step.get("expected")));
        out.put("actual", stringOrNull(step.get("actual")));
        out.put("prereq", stringOrNull(step.get("prereq")));
        Object err = step.get("errorMessage");
        out.put("errorMessage", err != null ? stripAnsi(String.valueOf(err)) : null);
        out.put("startTime", step.get("startTime"));
        out.put("endTime", step.get("endTime"));

        List<String> screenshots = new ArrayList<>();
        Object shots = step.get("screenshots");
        if (shots instanceof List) {
            for (Object s : (List<?>) shots) {
                String p = String.valueOf(s != null ? s : "").trim();
                if (!p.isEmpty()) {
                    screenshots.add(p);
                }
            }
        }
        out.put("screenshots", screenshots);
        return out;
    }

    private static boolean isTestdataStep(Map<String, Object> step) {
        String type = String.valueOf(step.getOrDefault("stepType", "")).toUpperCase();
        if ("TEST_DATA".equals(type) || "DATA".equals(type)) {
            return true;
        }
        Object td = step.get("testdata");
        return td != null && !String.valueOf(td).trim().isEmpty();
    }

    private static String normalizeStatus(Object status) {
        if (status == null) {
            return "IN_PROGRESS";
        }
        String s = String.valueOf(status).trim().toUpperCase().replace(' ', '_');
        if ("PASSED".equals(s) || "FAILED".equals(s) || "SKIPPED".equals(s) || "IN_PROGRESS".equals(s)) {
            return s;
        }
        return "IN_PROGRESS";
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
