package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepResponse;

import java.io.File;

/**
 * Runner-agnostic step API.
 *
 * <p>All framework handlers (Cucumber/TestNG/JUnit5) bind execution context to {@link MervPluginSteps}.
 * Test code should call this interface (via {@link MervReporter}) instead of handler-specific static methods.</p>
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

    TestStepResponse addDataStep(String stepName, String testdata);

    TestStepResponse addDataStep(String stepName, File file, org.teche.merv.client.dto.FileType fileType, String prereq)
            ;

    TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq);

    TestStepResponse addValidationStep(String stepName, String expected, String actual);

    TestStepResponse addValidationStep(String stepName);

    TestStepResponse info(String infoToAdd);

    void skipStep();

    void skipStep(boolean viewInReport);
}

