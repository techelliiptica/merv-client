package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * DTO for creating test suites via the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestSuiteRequest {
    
    @JsonProperty("hierarchy_id")
    private UUID hierarchyId;
    
    @NotBlank(message = "Title is required")
    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    private String title;
    
    @Size(max = 255, message = "Alias must not exceed 255 characters")
    private String alias;
    
    private String environment;
    
    @JsonProperty("releaseName")
    private String releaseName;
    
    private String sprint;
    
    private List<String> tags;
}
