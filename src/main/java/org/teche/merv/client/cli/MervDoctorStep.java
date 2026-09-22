package org.teche.merv.client.cli;

/** One line in the doctor setup report. */
public final class MervDoctorStep {

    public enum Action {
        CREATED,
        UPDATED,
        SKIPPED,
        FAILED
    }

    private final String step;
    private final Action action;
    private final String path;
    private final String detail;

    public MervDoctorStep(String step, Action action, String path, String detail) {
        this.step = step;
        this.action = action;
        this.path = path;
        this.detail = detail;
    }

    public String getStep() {
        return step;
    }

    public Action getAction() {
        return action;
    }

    public String getPath() {
        return path;
    }

    public String getDetail() {
        return detail;
    }
}
