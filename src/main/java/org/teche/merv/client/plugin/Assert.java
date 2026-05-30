package org.teche.merv.client.plugin;

/**
 * TestNG-style assertions that also publish Merv validation steps.
 *
 * <p>Usage (TestNG):</p>
 * <pre>{@code
 * import static org.teche.merv.client.plugin.Assert.*;
 *
 * assertEquals(actual, expected, "Verify total");
 * assertTrue(isVisible, "Cart icon visible");
 * }</pre>
 *
 * <p>Note: this is <strong>not</strong> {@code org.testng.Assert}. It's a Merv wrapper that records steps
 * via {@link MervReporter} and then delegates to TestNG assertions so tests still fail normally.</p>
 */
public final class Assert {

    private Assert() {}

    public static void assertEquals(Object actual, Object expected) {
        assertEquals(actual, expected, "assertEquals");
    }

    public static void assertEquals(Object actual, Object expected, String stepName) {
        // Record first so the step is visible even if Assert throws.
        MervReporter.validation(stepName != null ? stepName : "assertEquals",
                String.valueOf(expected),
                String.valueOf(actual));
        org.testng.Assert.assertEquals(actual, expected);
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "assertTrue");
    }

    public static void assertTrue(boolean condition, String stepName) {
        MervReporter.validation(stepName != null ? stepName : "assertTrue", "true", String.valueOf(condition));
        org.testng.Assert.assertTrue(condition);
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, "assertFalse");
    }

    public static void assertFalse(boolean condition, String stepName) {
        MervReporter.validation(stepName != null ? stepName : "assertFalse", "false", String.valueOf(condition));
        org.testng.Assert.assertFalse(condition);
    }

    public static void assertNotNull(Object value) {
        assertNotNull(value, "assertNotNull");
    }

    public static void assertNotNull(Object value, String stepName) {
        MervReporter.validation(stepName != null ? stepName : "assertNotNull", "non-null", String.valueOf(value));
        org.testng.Assert.assertNotNull(value);
    }
}

