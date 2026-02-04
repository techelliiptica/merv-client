package org.teche.merv.client.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Custom deserializer for TestCaseStatus that handles mapping
 * "COMPLETED" from backend to PASSED enum value
 */
public class TestCaseStatusDeserializer extends JsonDeserializer<TestCaseStatus> {
    
    @Override
    public TestCaseStatus deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getValueAsString();
        return TestCaseStatus.fromValue(value);
    }
}

