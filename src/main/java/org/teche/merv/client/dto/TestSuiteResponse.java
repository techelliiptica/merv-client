package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO for test suite responses from the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestSuiteResponse {
    
    @JsonProperty("id")
    @com.fasterxml.jackson.annotation.JsonAlias("uuid")
    private UUID id;
    
    @JsonProperty("hierarchy_id")
    private UUID hierarchyId;
    
    private String title;
    
    private String alias;
    
    private String environment;
    
    @JsonProperty("releaseName")
    private String releaseName;
    
    private String sprint;
    
    private List<String> tags;
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
