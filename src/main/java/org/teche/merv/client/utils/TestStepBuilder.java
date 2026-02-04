package org.teche.merv.client.utils;

import org.teche.merv.client.dto.TestStepRequest;

import java.util.UUID;

/**
 * Builder utility class for creating TestStepRequest objects
 */
public class TestStepBuilder {
    
    private String teststepName;
    private UUID testcaseId;
    private String expected;
    private String actual;
    private String prereq;
    private String testdata;
    private String stepType;
    private String status;
    
    public TestStepBuilder teststepName(String teststepName) {
        this.teststepName = teststepName;
        return this;
    }
    
    public TestStepBuilder testcaseId(UUID testcaseId) {
        this.testcaseId = testcaseId;
        return this;
    }
    
    public TestStepBuilder expected(String expected) {
        this.expected = expected;
        return this;
    }
    
    public TestStepBuilder actual(String actual) {
        this.actual = actual;
        return this;
    }
    
    public TestStepBuilder prereq(String prereq) {
        this.prereq = prereq;
        return this;
    }
    
    public TestStepBuilder testdata(String testdata) {
        this.testdata = testdata;
        return this;
    }
    
    public TestStepBuilder stepType(String stepType) {
        this.stepType = stepType;
        return this;
    }
    
    public TestStepBuilder status(String status) {
        this.status = status;
        return this;
    }
    
    public TestStepRequest build() {
        TestStepRequest request = new TestStepRequest();
        request.setTeststepName(teststepName);
        request.setTestcaseId(testcaseId);
        request.setExpected(expected);
        request.setActual(actual);
        request.setPrereq(prereq);
        request.setTestdata(testdata);
        request.setStepType(stepType);
        request.setStatus(status);
        return request;
    }
    
    public static TestStepBuilder create() {
        return new TestStepBuilder();
    }
}
