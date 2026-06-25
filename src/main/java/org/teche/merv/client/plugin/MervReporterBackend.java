package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.exception.MervClientException;

import java.io.File;

/** Package-private implementation for {@link MervReporter} and framework handlers. */
final class MervReporterBackend implements MervReporterApi {

    static final MervReporterBackend INSTANCE = new MervReporterBackend();

    private MervReporterBackend() {}

    @Override
    public TestStepResponse addStep(
            String stepName,
            String stepType,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        try {
            return MervPluginSteps.addStep(stepName, stepType, expected, actual, testdata, prereq);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] addStep failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse addStep(String stepName, String stepType) {
        try {
            return MervPluginSteps.addStep(stepName, stepType);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] addStep failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse data(String stepName, String testdata) {
        try {
            return MervPluginSteps.data(stepName, testdata);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] testdata failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse data(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) {
        try {
            return MervPluginSteps.data(stepName, file, fileType, prereq);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] testdata(file) failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse validation(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        try {
            return MervPluginSteps.validation(stepName, expected, actual, testdata, prereq);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] validation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse validation(String stepName, String expected, String actual) {
        try {
            return MervPluginSteps.validation(stepName, expected, actual);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] validation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse validation(String stepName) {
        try {
            return MervPluginSteps.validation(stepName);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] validation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TestStepResponse info(String infoToAdd) {
        try {
            return MervPluginSteps.info(infoToAdd);
        } catch (MervClientException e) {
            System.err.println("[MervReporter] info failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void skipStep() {
        MervCucumberHandler.skipStep();
    }

    @Override
    public void skipStep(boolean viewInReport) {
        MervCucumberHandler.skipStep(viewInReport);
    }
}
