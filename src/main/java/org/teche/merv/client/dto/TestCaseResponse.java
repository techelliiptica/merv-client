package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.teche.merv.client.dto.TestCaseStatusDeserializer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO for test case responses from the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestCaseResponse {
    
    @JsonProperty("id")
    @com.fasterxml.jackson.annotation.JsonAlias("uuid")
    private UUID id;
    
    @JsonProperty("testcase_name")
    private String testcaseName;
    
    private String description;
    
    @JsonProperty("test_suite_id")
    private UUID testSuiteId;
    
    private List<String> tags;
    
    @JsonProperty("Execution-Machine")
    private List<String> executionMachine;
    
    @JsonDeserialize(using = TestCaseStatusDeserializer.class)
    private TestCaseStatus status;
    
    @JsonProperty("test-management-id")
    private List<String> testManagementId;
    
    private Boolean debug;
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("start_time")
    private LocalDateTime startTime;
    
    @JsonProperty("end_time")
    private LocalDateTime endTime;
    
    @JsonProperty("execution_duration_seconds")
    private Long executionDurationSeconds;
}
