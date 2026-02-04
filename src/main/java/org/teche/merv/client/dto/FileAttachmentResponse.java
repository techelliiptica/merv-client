package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for file attachment responses from the client API
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileAttachmentResponse {
    private UUID id;
    
    @JsonProperty("filename")
    private String filename;
    
    @JsonProperty("original_filename")
    private String originalFilename;
    
    @JsonProperty("file_type")
    private String fileType;
    
    @JsonProperty("file_extension")
    private String fileExtension;
    
    @JsonProperty("file_size")
    private Long fileSize;
    
    @JsonProperty("file_path")
    private String filePath;
    
    @JsonProperty("mime_type")
    private String mimeType;
    
    @JsonProperty("test_step_id")
    private UUID testStepId;
    
    private String description;
    
    @JsonProperty("created_at")
    private Instant createdAt;
    
    @JsonProperty("updated_at")
    private Instant updatedAt;
}

