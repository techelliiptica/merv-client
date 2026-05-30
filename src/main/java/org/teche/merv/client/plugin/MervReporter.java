package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.exception.MervClientException;

import java.io.File;

/**
 * Unified step entrypoint for all runners.
 *
 * <p>Use this in test code instead of handler-specific static methods
 * ({@code MervCucumberHandler.addStep(...)} / {@code MervTestNGHandler.addStep(...)} / etc.).</p>
 *
 * <p>Preferred helpers: {@link #data}, {@link #validation}, {@link #info}.</p>
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

    public static TestStepResponse data(String stepName, String testdata) {
        return DEFAULT.data(stepName, testdata);
    }

    public static TestStepResponse data(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) {
        return DEFAULT.data(stepName, file, fileType, prereq);
    }

    public static TestStepResponse validation(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        return DEFAULT.validation(stepName, expected, actual, testdata, prereq);
    }

    public static TestStepResponse validation(String stepName, String expected, String actual) {
        return DEFAULT.validation(stepName, expected, actual);
    }

    public static TestStepResponse validation(String stepName) {
        return DEFAULT.validation(stepName);
    }

    public static TestStepResponse info(String infoToAdd) {
        return DEFAULT.info(infoToAdd);
    }

    /** @deprecated Use {@link #data(String, String)}. */
    @Deprecated
    public static TestStepResponse addDataStep(String stepName, String testdata) {
        return data(stepName, testdata);
    }

    /** @deprecated Use {@link #data(String, File, org.teche.merv.client.dto.FileType, String)}. */
    @Deprecated
    public static TestStepResponse addDataStep(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) {
        return data(stepName, file, fileType, prereq);
    }

    /** @deprecated Use {@link #validation(String, String, String, String, String)}. */
    @Deprecated
    public static TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) {
        return validation(stepName, expected, actual, testdata, prereq);
    }

    /** @deprecated Use {@link #validation(String, String, String)}. */
    @Deprecated
    public static TestStepResponse addValidationStep(String stepName, String expected, String actual) {
        return validation(stepName, expected, actual);
    }

    /** @deprecated Use {@link #validation(String)}. */
    @Deprecated
    public static TestStepResponse addValidationStep(String stepName) {
        return validation(stepName);
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
        public TestStepResponse data(String stepName, String testdata) {
            try {
                return MervPluginSteps.data(stepName, testdata);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] data failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        public TestStepResponse data(String stepName, File file, org.teche.merv.client.dto.FileType fileType, String prereq)
        {
            try {
                return MervCucumberHandler.data(stepName, file, fileType, prereq);
            } catch (MervClientException e) {
                System.err.println("[MervReporter] data(file) failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        public TestStepResponse validation(String stepName, String expected, String actual, String testdata, String prereq)
        {
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
}
