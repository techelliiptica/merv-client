package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * DTO for partial updates to a test suite (PATCH operation)
 * All fields are optional - only provided fields will be updated
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestSuitePatchRequest {

    @JsonProperty("hierarchy_id")
    private UUID hierarchyId;

    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    private String title;
    
    @Size(max = 255, message = "Alias must not exceed 255 characters")
    private String alias;
    
    private String environment;

    @JsonProperty("releaseName")
    private String releaseName;

    private String sprint;

    private List<String> tags;

    @JsonProperty("suite_status")
    private String suiteStatus;
}

