package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.exception.MervClientException;

import java.io.File;

/**
 * Unified step entrypoint for all runners.
 *
 * <p>Use this in test code instead of handler-specific static methods
 * ({@code MervCucumberHandler.addStep(...)} / {@code MervTestNGHandler.addStep(...)} / etc.).</p>
 */
public final class MervReporter {

    private static final MervReporterApi DEFAULT = new DefaultMervReporterApi();

    private MervReporter() {}

    public static MervReporterApi api() {
        return DEFAULT;
    }

    public static TestStepResponse addStep(
            String stepName,
            String stepType,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        return DEFAULT.addStep(stepName, stepType, expected, actual, testdata, prereq);
    }

    public static TestStepResponse addStep(String stepName, String stepType) {
        return DEFAULT.addStep(stepName, stepType);
    }

    public static TestStepResponse addDataStep(String stepName, String testdata) {
        return DEFAULT.addDataStep(stepName, testdata);
    }

    public static TestStepResponse addDataStep(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) {
        return DEFAULT.addDataStep(stepName, file, fileType, prereq);
    }

    public static TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        return DEFAULT.addValidationStep(stepName, expected, actual, testdata, prereq);
    }

    public static TestStepResponse addValidationStep(String stepName, String expected, String actual) {
        return DEFAULT.addValidationStep(stepName, expected, actual);
    }

    public static TestStepResponse addValidationStep(String stepName) {
        return DEFAULT.addValidationStep(stepName);
    }

    public static TestStepResponse info(String infoToAdd) {
        return DEFAULT.info(infoToAdd);
    }

    public static void skipStep() {
        DEFAULT.skipStep();
    }

    public static void skipStep(boolean viewInReport) {
        DEFAULT.skipStep(viewInReport);
    }

    private static final class DefaultMervReporterApi implements MervReporterApi {
        @Override
        public TestStepResponse addStep(String stepName, String stepType, String expected, String actual, String testdata, String prereq)
        {
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
        public TestStepResponse addDataStep(String stepName, String testdata) {
            try {
                return MervPluginSteps.addDataStep(stepName, testdata);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] addDataStep failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        public TestStepResponse addDataStep(String stepName, File file, org.teche.merv.client.dto.FileType fileType, String prereq)
        {
            try {
                // Delegate to the existing implementation that supports file uploads in server mode.
                return MervCucumberHandler.addDataStep(stepName, file, fileType, prereq);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] addDataStep(file) failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        public TestStepResponse addValidationStep(String stepName, String expected, String actual, String testdata, String prereq)
        {
            try {
                return MervPluginSteps.addValidationStep(stepName, expected, actual, testdata, prereq);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] addValidationStep failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        public TestStepResponse addValidationStep(String stepName, String expected, String actual) {
            try {
                return MervPluginSteps.addValidationStep(stepName, expected, actual);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] addValidationStep failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        public TestStepResponse addValidationStep(String stepName) {
            try {
                return MervPluginSteps.addValidationStep(stepName);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] addValidationStep failed: " + e.getMessage());
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
}

