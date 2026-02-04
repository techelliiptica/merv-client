package org.teche.merv.client.dto;

/**
 * Enum representing step types for test steps
 */
public enum StepType {
    TESTDATA("testdata", "TEST_DATA"),
    ASSERTION("assertion", "ASSERTION"),
    INFORMATION("information", "PREREQUISITE");

    private final String value;
    private final String apiValue;

    StepType(String value, String apiValue) {
        this.value = value;
        this.apiValue = apiValue;
    }

    /**
     * Get the step type string value (lowercase format)
     * 
     * @return The step type string
     */
    public String getValue() {
        return value;
    }

    /**
     * Get the API-compatible step type string (uppercase format)
     * 
     * @return The API step type string
     */
    public String getApiValue() {
        return apiValue;
    }

    /**
     * Get StepType from string value (case-insensitive)
     * 
     * @param value The step type string (e.g., "testdata", "assertion", "information")
     * @return The corresponding StepType
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static StepType fromString(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Step type cannot be null or empty");
        }
        
        String normalizedValue = value.toLowerCase().trim();
        return switch (normalizedValue) {
            case "testdata", "test_data" -> TESTDATA;
            case "assertion" -> ASSERTION;
            case "information", "info" -> INFORMATION;
            default -> throw new IllegalArgumentException("Unknown step type: " + value + ". Valid values are: testdata, assertion, information");
        };
    }
}

