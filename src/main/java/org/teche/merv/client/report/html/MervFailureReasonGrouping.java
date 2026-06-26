package org.teche.merv.client.report.html;

/**
 * Normalizes Playwright / automation failure text for suite-level grouping (KPI, consolidated JSON).
 * Keeps failure title and description; strips Call log and stack frames so one reason maps to many testcases.
 */
public final class MervFailureReasonGrouping {

    private MervFailureReasonGrouping() {}

    /** Grouping key for failure-reason panels (not the full detail shown on a testcase). */
    public static String groupingKey(String text) {
        if (text == null) {
            return "(No failure message)";
        }
        String t = stripAnsi(text).trim();
        if (t.isEmpty()) {
            return "(No failure message)";
        }
        int callLogIdx = indexOfCallLog(t);
        if (callLogIdx >= 0) {
            t = t.substring(0, callLogIdx).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\n", -1)) {
            if (line.trim().startsWith("at ")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        t = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        return t.isEmpty() ? "(No failure message)" : t;
    }

    private static int indexOfCallLog(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\n\\s*Call log:\\s*", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        return m.find() ? m.start() : -1;
    }

    private static String stripAnsi(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
