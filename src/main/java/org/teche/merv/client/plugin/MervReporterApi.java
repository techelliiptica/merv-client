package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepResponse;

import java.io.File;

/**
 * Runner-agnostic step API.
 *
 * <p>All framework handlers (Cucumber/TestNG/JUnit5) bind execution context to {@link MervPluginSteps}.
 * Test code should call this interface (via {@link MervReporter}) instead of handler-specific static methods.</p>
 *
 * <p>Preferred step helpers: {@link #data}, {@link #validation}, {@link #info} (aligned with merv-client-js).</p>
 */
public interface MervReporterApi {
    TestStepResponse addStep(
            String stepName,
            String stepType,
            String expected,
            String actual,
            String testdata,
            String prereq);

    TestStepResponse addStep(String stepName, String stepType);

    TestStepResponse data(String stepName, String testdata);

    TestStepResponse data(String stepName, File file, org.teche.merv.client.dto.FileType fileType, String prereq);

    TestStepResponse validation(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq);

    TestStepResponse validation(String stepName, String expected, String actual);

    TestStepResponse validation(String stepName);

    TestStepResponse info(String infoToAdd);

    void skipStep();

    void skipStep(boolean viewInReport);

    /** @deprecated Use {@link #data(String, String)}. */
    @Deprecated
    default TestStepResponse addDataStep(String stepName, String testdata) {
        return data(stepName, testdata);
    }

    /** @deprecated Use {@link #data(String, File, org.teche.merv.client.dto.FileType, String)}. */
    @Deprecated
    default TestStepResponse addDataStep(String stepName, File file, org.teche.merv.client.dto.FileType fileType, String prereq) {
        return data(stepName, file, fileType, prereq);
    }

    /** @deprecated Use {@link #validation(String, String, String, String, String)}. */
    @Deprecated
    default TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        return validation(stepName, expected, actual, testdata, prereq);
    }

    /** @deprecated Use {@link #validation(String, String, String)}. */
    @Deprecated
    default TestStepResponse addValidationStep(String stepName, String expected, String actual) {
        return validation(stepName, expected, actual);
    }

    /** @deprecated Use {@link #validation(String)}. */
    @Deprecated
    default TestStepResponse addValidationStep(String stepName) {
        return validation(stepName);
    }
}
