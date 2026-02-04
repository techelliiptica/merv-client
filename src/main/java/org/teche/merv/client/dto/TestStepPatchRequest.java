package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO for partial updates to a test step (PATCH operation)
 * All fields are optional - only provided fields will be updated
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestStepPatchRequest {

    @Size(min = 1, max = 255, message = "Test step name must be between 1 and 255 characters")
    @JsonProperty("teststep_name")
    private String teststepName;

    @Size(max = 2000, message = "Expected result must not exceed 2000 characters")
    private String expected;

    @Size(max = 2000, message = "Actual result must not exceed 2000 characters")
    private String actual;

    @Size(max = 1000, message = "Prerequisites must not exceed 1000 characters")
    private String prereq;

    @Size(max = 1000, message = "Test data must not exceed 1000 characters")
    private String testdata;

    @JsonProperty("step_type")
    @Pattern(regexp = "^(ASSERTION|TEST_DATA|PREREQUISITE)$",
             message = "Step type must be one of: ASSERTION, TEST_DATA, PREREQUISITE")
    private String stepType;

    @Pattern(regexp = "^(PENDING|IN_PROGRESS|PASSED|FAILED|SKIPPED|BLOCKED)$",
             message = "Status must be one of: PENDING, IN_PROGRESS, PASSED, FAILED, SKIPPED, BLOCKED")
    private String status;
}

