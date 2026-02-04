package org.teche.merv.client.utils;

import org.teche.merv.client.dto.TestSuiteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder utility class for creating TestSuiteRequest objects
 */
public class TestSuiteBuilder {
    
    private UUID hierarchyId;
    private String title;
    private String alias;
    private String environment;
    private String releaseName;
    private String sprint;
    private List<String> tags = new ArrayList<>();
    
    public TestSuiteBuilder hierarchyId(UUID hierarchyId) {
        this.hierarchyId = hierarchyId;
        return this;
    }
    
    public TestSuiteBuilder title(String title) {
        this.title = title;
        return this;
    }
    
    public TestSuiteBuilder alias(String alias) {
        this.alias = alias;
        return this;
    }
    
    public TestSuiteBuilder environment(String environment) {
        this.environment = environment;
        return this;
    }
    
    public TestSuiteBuilder releaseName(String releaseName) {
        this.releaseName = releaseName;
        return this;
    }
    
    public TestSuiteBuilder sprint(String sprint) {
        this.sprint = sprint;
        return this;
    }
    
    public TestSuiteBuilder addTag(String tag) {
        this.tags.add(tag);
        return this;
    }
    
    public TestSuiteBuilder tags(List<String> tags) {
        this.tags = new ArrayList<>(tags);
        return this;
    }
    
    public TestSuiteRequest build() {
        TestSuiteRequest request = new TestSuiteRequest();
        request.setHierarchyId(hierarchyId);
        request.setTitle(title);
        request.setAlias(alias);
        request.setEnvironment(environment);
        request.setReleaseName(releaseName);
        request.setSprint(sprint);
        request.setTags(tags);
        return request;
    }
    
    public static TestSuiteBuilder create() {
        return new TestSuiteBuilder();
    }
}
