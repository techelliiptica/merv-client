package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.FileType;
import org.teche.merv.client.dto.TestStepResponse;

import java.io.File;

/**
 * Custom MERV report steps for test code (Cucumber, TestNG, JUnit 5).
 *
 * <ul>
 *   <li>{@link #info(String)}</li>
 *   <li>{@link #testdata(String, String)} / {@link #testdata(String, File)}</li>
 *   <li>{@link #validation(String, String, String)} / {@link #validation(String, String, String, boolean)}</li>
 * </ul>
 */
public final class MervReporter {

    private static final MervReporterBackend BACKEND = MervReporterBackend.INSTANCE;

    private MervReporter() {}

    public static TestStepResponse info(String str) {
        return BACKEND.info(str);
    }

    public static TestStepResponse testdata(String stepName, String data) {
        return BACKEND.data(stepName, data);
    }

    public static TestStepResponse testdata(String stepName, File testData) {
        return BACKEND.data(stepName, testData, FileType.OTHERS, null);
    }

    public static TestStepResponse validation(String stepName, String expected, String actual) {
        return BACKEND.validation(stepName, expected, actual);
    }

    public static TestStepResponse validation(
            String stepName,
            String expected,
            String actual,
            boolean screenshot) {
        return BACKEND.validation(stepName, expected, actual);
    }
}
