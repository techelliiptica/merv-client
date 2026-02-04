package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for test step responses from the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestStepResponse {
    
    @JsonProperty("id")
    @com.fasterxml.jackson.annotation.JsonAlias("uuid")
    private UUID id;
    
    @JsonProperty("teststep_name")
    private String teststepName;
    
    @JsonProperty("testcase_id")
    private UUID testcaseId;
    
    private String expected;
    
    private String actual;
    
    private String prereq;
    
    private String testdata;
    
    private String status;
    
    @JsonProperty("screenshot_filename")
    private String screenshotFilename;
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
