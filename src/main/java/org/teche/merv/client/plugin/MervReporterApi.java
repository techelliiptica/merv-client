package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepResponse;

import java.io.File;

/**
 * Runner-agnostic step API (internal). Test code should use {@link MervReporter}.
 */
interface MervReporterApi {
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
}
