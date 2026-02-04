package org.teche.merv.client.utils;

import org.teche.merv.client.dto.TestCaseRequest;
import org.teche.merv.client.dto.TestCaseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder utility class for creating TestCaseRequest objects
 */
public class TestCaseBuilder {
    
    private String testcaseName;
    private String description;
    private UUID testSuiteId;
    private List<String> tags = new ArrayList<>();
    private List<String> executionMachine = new ArrayList<>();
    private TestCaseStatus status;
    private List<String> testManagementId = new ArrayList<>();
    private Boolean debug = false;
    
    public TestCaseBuilder testcaseName(String testcaseName) {
        this.testcaseName = testcaseName;
        return this;
    }
    
    public TestCaseBuilder description(String description) {
        this.description = description;
        return this;
    }
    
    public TestCaseBuilder testSuiteId(UUID testSuiteId) {
        this.testSuiteId = testSuiteId;
        return this;
    }
    
    public TestCaseBuilder addTag(String tag) {
        this.tags.add(tag);
        return this;
    }
    
    public TestCaseBuilder tags(List<String> tags) {
        this.tags = new ArrayList<>(tags);
        return this;
    }
    
    public TestCaseBuilder addExecutionMachine(String machine) {
        this.executionMachine.add(machine);
        return this;
    }
    
    public TestCaseBuilder executionMachine(List<String> executionMachine) {
        this.executionMachine = new ArrayList<>(executionMachine);
        return this;
    }
    
    public TestCaseBuilder status(TestCaseStatus status) {
        this.status = status;
        return this;
    }
    
    /**
     * Convenience method to set status from string (for backward compatibility)
     */
    public TestCaseBuilder status(String status) {
        if (status != null && !status.trim().isEmpty()) {
            this.status = TestCaseStatus.fromValue(status);
        }
        return this;
    }
    
    public TestCaseBuilder addTestManagementId(String id) {
        this.testManagementId.add(id);
        return this;
    }
    
    public TestCaseBuilder testManagementId(List<String> testManagementId) {
        this.testManagementId = new ArrayList<>(testManagementId);
        return this;
    }
    
    public TestCaseBuilder debug(Boolean debug) {
        this.debug = debug;
        return this;
    }
    
    public TestCaseRequest build() {
        return new TestCaseRequest(testcaseName, description, testSuiteId, tags, executionMachine, status, testManagementId, debug);
    }
    
    public static TestCaseBuilder create() {
        return new TestCaseBuilder();
    }
}
