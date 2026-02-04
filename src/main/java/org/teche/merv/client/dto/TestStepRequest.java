package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * DTO for creating test steps via the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestStepRequest {

    @NotBlank(message = "Test step name is required")
    @Size(min = 1, max = 255, message = "Test step name must be between 1 and 255 characters")
    @JsonProperty("teststep_name")
    private String teststepName;

    @NotNull(message = "Test case ID is required")
    @JsonProperty("testcase_id")
    private UUID testcaseId;

    @Size(max = 2000, message = "Expected result must not exceed 2000 characters")
    private String expected;

    @Size(max = 2000, message = "Actual result must not exceed 2000 characters")
    private String actual;

    @Size(max = 1000, message = "Prerequisites must not exceed 1000 characters")
    private String prereq;

    @Size(max = 1000, message = "Test data must not exceed 1000 characters")
    private String testdata;

    @JsonProperty("step_type")
    private String stepType;

    private String status;
}
