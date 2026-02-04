package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * DTO for creating test cases via the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestCaseRequest {
    
    @NotBlank(message = "Test case name is required")
    @Size(min = 1, max = 255, message = "Test case name must be between 1 and 255 characters")
    @JsonProperty("testcase_name")
    private String testcaseName;
    
    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;
    
    @NotNull(message = "Test suite ID is required")
    @JsonProperty("test_suite_id")
    private UUID testSuiteId;
    
    private List<String> tags;
    
    @JsonProperty("Execution-Machine")
    private List<String> executionMachine;
    
    private TestCaseStatus status;
    
    @JsonProperty("test-management-id")
    private List<String> testManagementId;
    
    private Boolean debug = false;
}
