package org.teche.merv.client.plugin;

import org.teche.merv.client.MervClient;
import org.teche.merv.client.dto.FileType;
import org.teche.merv.client.dto.TestStepPatchRequest;
import org.teche.merv.client.dto.TestStepRequest;
import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.report.html.MervTestDataFileHtml;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Shared helpers for {@link MervPluginSteps} file testdata across runners. */
final class MervPluginFileDataSupport {

    private MervPluginFileDataSupport() {}

    static void validate(String stepName, File file) throws MervClientException {
        if (stepName == null || stepName.trim().isEmpty()) {
            throw new MervClientException("Step name is required and cannot be empty.");
        }
        if (file == null || !file.exists()) {
            throw new MervClientException("File does not exist: " + (file != null ? file.getPath() : "null"));
        }
    }

    static String fallbackTestdata(File file, FileType fileType) {
        return "File: " + file.getName() + " (Type: "
                + (fileType != null ? fileType.getType() : "unknown") + ")";
    }

    static List<MervTestDataFileHtml.AttachedFile> saveAttachedFiles(
            File file,
            String reportFolderPath) {
        MervTestDataFileHtml.AttachedFile attached =
                MervTestDataFileHtml.saveTestDataFile(file, reportFolderPath);
        return attached != null ? Collections.singletonList(attached) : null;
    }

    static TestStepResponse createServerFileStep(
            MervClient client,
            UUID testCaseId,
            String stepName,
            File file,
            FileType fileType,
            String prereq) throws MervClientException {
        if (client == null) {
            throw new MervClientException("MervClient is not initialized.");
        }
        if (testCaseId == null) {
            throw new MervClientException(
                    "No active test case found. Step creation must be called during an active test case execution.");
        }
        validate(stepName, file);

        TestStepRequest request = new TestStepRequest();
        request.setTeststepName(stepName);
        request.setTestcaseId(testCaseId);
        request.setStepType("TEST_DATA");
        request.setStatus("PASSED");
        if (prereq != null) {
            request.setPrereq(prereq);
        }

        if (MervTestDataFileHtml.isOversized(file)) {
            request.setTestdata(MervTestDataFileHtml.oversizeTestdata(file));
            return client.createTestStep(request);
        }

        TestStepResponse stepResponse = client.createTestStep(request);
        try {
            client.uploadFile(stepResponse.getId(), file, "Test data file");
        } catch (MervClientException e) {
            System.err.println("Warning: Failed to attach file to test step: " + e.getMessage());
            try {
                TestStepPatchRequest patch = new TestStepPatchRequest();
                patch.setTestdata(fallbackTestdata(file, fileType));
                client.patchTestStep(stepResponse.getId(), patch);
            } catch (MervClientException patchError) {
                System.err.println("Warning: Could not record file metadata on step: "
                        + patchError.getMessage());
            }
        }
        return stepResponse;
    }
}
