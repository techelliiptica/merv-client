package org.teche.merv.client.logging;

/**
 * Thread-local suite / testcase labels attached to Merv-Logs NDJSON lines.
 * Handlers (Cucumber / TestNG / JUnit) can set these while a case is running.
 */
public final class MervLogContext {

    private static final ThreadLocal<String> SUITE = new ThreadLocal<>();
    private static final ThreadLocal<String> TESTCASE = new ThreadLocal<>();

    private MervLogContext() {}

    public static void setSuite(String suite) {
        if (suite == null || suite.isBlank()) {
            SUITE.remove();
        } else {
            SUITE.set(suite.trim());
        }
    }

    public static void setTestcase(String testcase) {
        if (testcase == null || testcase.isBlank()) {
            TESTCASE.remove();
        } else {
            TESTCASE.set(testcase.trim());
        }
    }

    public static String getSuite() {
        return SUITE.get();
    }

    public static String getTestcase() {
        return TESTCASE.get();
    }

    public static void clear() {
        SUITE.remove();
        TESTCASE.remove();
    }
}
