package org.teche.merv.client.logging;

/**
 * One structured Merv-Logs line (NDJSON under {@code {reportRoot}/log/}).
 * Compatible with the JS {@code MervLogRecord} / {@code merv-logs.html} contract.
 */
public final class MervLogRecord {

    private String ts;
    private String level;
    private String name;
    private String msg;
    private String stack;
    private String suite;
    private String testcase;
    private String screenshot;
    /** Optional originating project ({@code merv.log.source}) for shared log servers. */
    private String project;

    public MervLogRecord() {}

    public String getTs() {
        return ts;
    }

    public void setTs(String ts) {
        this.ts = ts;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(String stack) {
        this.stack = stack;
    }

    public String getSuite() {
        return suite;
    }

    public void setSuite(String suite) {
        this.suite = suite;
    }

    public String getTestcase() {
        return testcase;
    }

    public void setTestcase(String testcase) {
        this.testcase = testcase;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public void setScreenshot(String screenshot) {
        this.screenshot = screenshot;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }
}
