package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.StepType;
import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.exception.MervClientException;

/**
 * Shared step APIs usable from any runner plugin (Cucumber, TestNG, ...).
 *
 * <p>Each plugin binds a per-thread adapter while a test case is active.</p>
 */
public final class MervPluginSteps {

    private MervPluginSteps() {}

    /**
     * Per-thread adapter bound by the active runner plugin.
     */
    private static final ThreadLocal<Adapter> ADAPTER = new ThreadLocal<>();

    static void bind(Adapter adapter) {
        ADAPTER.set(adapter);
    }

    static void clear() {
        ADAPTER.remove();
    }

    public static TestStepResponse addStep(
            String stepName,
            String stepType,
            String expected,
            String actual,
            String testdata,
            String prereq) throws MervClientException {

        Adapter adapter = ADAPTER.get();
        if (adapter == null) {
            throw new MervClientException("No active Merv plugin context. Call step APIs during an active test execution.");
        }
        if (stepName == null || stepName.trim().isEmpty()) {
            throw new MervClientException("Step name is required and cannot be empty.");
        }

        StepType st;
        try {
            st = StepType.fromString(stepType);
        } catch (IllegalArgumentException e) {
            throw new MervClientException("Invalid step type: " + stepType + ". Valid values are: testdata, assertion, information", e);
        }

        StepPayload payload = new StepPayload(stepName, st, expected, actual, testdata, prereq);
        if (adapter.isLocalMode()) {
            adapter.addLocalStep(payload);
            return null;
        }
        return adapter.addServerStep(payload);
    }

    public static TestStepResponse addStep(String stepName, String stepType) throws MervClientException {
        return addStep(stepName, stepType, null, null, null, null);
    }

    public static TestStepResponse addDataStep(String stepName, String testdata) throws MervClientException {
        return addStep(stepName, StepType.TESTDATA.getValue(), null, null, testdata, null);
    }

    public static TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) throws MervClientException {
        return addStep(stepName, StepType.ASSERTION.getValue(), expected, actual, testdata, prereq);
    }

    public static TestStepResponse addValidationStep(String stepName, String expected, String actual) throws MervClientException {
        return addValidationStep(stepName, expected, actual, null, null);
    }

    public static TestStepResponse addValidationStep(String stepName) throws MervClientException {
        return addValidationStep(stepName, null, null, null, null);
    }

    public static TestStepResponse info(String infoToAdd) throws MervClientException {
        return addStep("Info", StepType.INFORMATION.getValue(), null, null, null, infoToAdd);
    }

    interface Adapter {
        boolean isLocalMode();
        void addLocalStep(StepPayload payload) throws MervClientException;
        TestStepResponse addServerStep(StepPayload payload) throws MervClientException;
    }

    static final class StepPayload {
        final String name;
        final StepType type;
        final String expected;
        final String actual;
        final String testdata;
        final String prereq;
        final String status;
        final String errorMessage;

        StepPayload(String name, StepType type, String expected, String actual, String testdata, String prereq) {
            this.name = name;
            this.type = type;
            this.expected = expected;
            this.actual = actual;
            this.testdata = testdata;
            this.prereq = prereq;
            String computedStatus = "PASSED";
            String computedErr = null;
            if (type == StepType.ASSERTION && expected != null && actual != null) {
                if (!expected.equals(actual)) {
                    computedStatus = "FAILED";
                    computedErr = "Validation failed: expected [" + expected + "] but got [" + actual + "]";
                }
            }
            this.status = computedStatus;
            this.errorMessage = computedErr;
        }
    }
}

