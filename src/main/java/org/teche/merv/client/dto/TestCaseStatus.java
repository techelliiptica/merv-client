package org.teche.merv.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum representing the status of a test case
 */
public enum TestCaseStatus {
    INPROGRESS,
    SKIPPED,
    PASSED,
    FAILED;
    
    /**
     * Serialize enum to JSON string value expected by the backend
     * Backend now uses PASSED directly (no mapping needed)
     */
    @JsonValue
    public String toValue() {
        return this.name();
    }
    
    /**
     * Deserialize JSON string value from backend to enum
     * Maps COMPLETED from backend to PASSED for backward compatibility with old data
     */
    @JsonCreator
    public static TestCaseStatus fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        String upperValue = value.toUpperCase();
        
        // Map COMPLETED from backend to PASSED for backward compatibility
        // (in case there are old records in database with COMPLETED status)
        if ("COMPLETED".equals(upperValue)) {
            return PASSED;
        }
        
        // Try to match enum values directly
        try {
            return valueOf(upperValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid test case status: " + value + 
                ". Valid values are: INPROGRESS, SKIPPED, PASSED, FAILED");
        }
    }
}

