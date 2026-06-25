package org.teche.merv.client.plugin;

import org.teche.merv.client.dto.TestStepPatchRequest;
import org.teche.merv.client.dto.TestStepResponse;

import java.util.Locale;

/** Shared rules for finalizing server-mode steps created by custom reporters. */
final class MervServerStepFinalizeSupport {

    private MervServerStepFinalizeSupport() {}

    static boolean isInformationalStepType(String stepType) {
        if (stepType == null) {
            return false;
        }
        String t = stepType.trim().toUpperCase(Locale.ROOT);
        return "TEST_DATA".equals(t) || "PREREQUISITE".equals(t);
    }

    /**
     * TEST_DATA / PREREQUISITE rows are recorded as complete when the reporter creates them;
     * do not downgrade open states to SKIPPED at testcase end.
     */
    static TestStepPatchRequest patchForOpenStep(TestStepResponse step) {
        if (step == null || step.getStatus() == null) {
            return null;
        }
        String stepStatus = step.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!"IN_PROGRESS".equals(stepStatus) && !"PENDING".equals(stepStatus)) {
            return null;
        }
        TestStepPatchRequest patch = new TestStepPatchRequest();
        if (isInformationalStepType(step.getStepType())) {
            patch.setStatus("PASSED");
        } else {
            patch.setStatus("SKIPPED");
        }
        return patch;
    }
}
