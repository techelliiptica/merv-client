package org.teche.merv.client.plugin;

import org.junit.jupiter.api.Assertions;

/**
 * JUnit 5 assertion wrappers that also publish Merv validation steps.
 *
 * <p>Why this exists: using {@code import static org.junit.jupiter.api.Assertions.*} runs assertions
 * but does not create Merv "steps". Merv can only display steps that are explicitly recorded via
 * {@link MervReporter} / {@link MervPluginSteps}. These helpers record a validation step and then
 * delegate to JUnit assertions so tests still fail normally.</p>
 */
public final class MervAssertions {

    private MervAssertions() {}

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, "assertEquals");
    }

    public static void assertEquals(Object expected, Object actual, String stepName) {
        // Record first so the step is visible even if the assertion throws.
        MervReporter.validation(stepName != null ? stepName : "assertEquals",
                String.valueOf(expected),
                String.valueOf(actual));
        Assertions.assertEquals(expected, actual);
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "assertTrue");
    }

    public static void assertTrue(boolean condition, String stepName) {
        MervReporter.validation(stepName != null ? stepName : "assertTrue", "true", String.valueOf(condition));
        Assertions.assertTrue(condition);
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, "assertFalse");
    }

    public static void assertFalse(boolean condition, String stepName) {
        MervReporter.validation(stepName != null ? stepName : "assertFalse", "false", String.valueOf(condition));
        Assertions.assertFalse(condition);
    }

    public static void assertNotNull(Object value) {
        assertNotNull(value, "assertNotNull");
    }

    public static void assertNotNull(Object value, String stepName) {
        MervReporter.validation(stepName != null ? stepName : "assertNotNull", "non-null", String.valueOf(value));
        Assertions.assertNotNull(value);
    }
}

