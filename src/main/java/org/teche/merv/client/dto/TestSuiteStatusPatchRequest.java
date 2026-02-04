package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for updating test suite status via PATCH operation
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestSuiteStatusPatchRequest {
    
    @NotBlank(message = "Suite status is required")
    @JsonProperty("suite_status")
    private String suiteStatus;
}

