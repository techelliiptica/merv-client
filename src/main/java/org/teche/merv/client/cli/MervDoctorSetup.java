package org.teche.merv.client.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java/Maven doctor — creates or heals {@code merv.properties} and checks {@code pom.xml}.
 * Aligned with {@code npx merv-client doctor setup} / {@code setup-log} (without JS-only files).
 */
public final class MervDoctorSetup {

    private static final String MERV_CLIENT_VERSION = resolveClientVersion();

    private MervDoctorSetup() {
    }

    public static List<MervDoctorStep> runSetup(Path projectRoot, boolean logsOnly, boolean force, String suiteTitle) {
        List<MervDoctorStep> steps = new ArrayList<>();
        String title = resolveSuiteTitle(projectRoot, suiteTitle);

        steps.add(ensureMervProperties(projectRoot, title, force, logsOnly));
        steps.add(ensureProperty(projectRoot, "merv.logger.file", "true"));
        steps.add(ensureProperty(projectRoot, "merv.logger.level", "INFO"));
        steps.add(ensureProperty(projectRoot, "merv.project", title));
        steps.add(ensureProperty(projectRoot, "merv.log.source", title));
        steps.add(MervDoctorLogbackSetup.ensureLogbackSpring(projectRoot, force));
        steps.add(MervDoctorMavenSetup.ensureDependency(projectRoot, MERV_CLIENT_VERSION));

        steps.add(new MervDoctorStep(
                "next steps",
                MervDoctorStep.Action.SKIPPED,
                null,
                logsOnly
                        ? "Run app/tests, then: merv-client show-logs"
                        : "Run tests, then: merv-client show-report · Logs: merv-client show-logs"));
        return steps;
    }

    private static String resolveClientVersion() {
        Package pkg = MervDoctorSetup.class.getPackage();
        if (pkg != null) {
            String impl = pkg.getImplementationVersion();
            if (impl != null && !impl.isBlank()) {
                return impl.trim();
            }
        }
        return "4.0.23";
    }

    private static String resolveSuiteTitle(Path projectRoot, String suiteTitle) {
        if (suiteTitle != null && !suiteTitle.isBlank()) {
            return suiteTitle.trim();
        }
        String name = projectRoot.getFileName() != null ? projectRoot.getFileName().toString() : "MERV Suite";
        return name.isBlank() ? "MERV Suite" : name;
    }

    private static MervDoctorStep ensureMervProperties(Path projectRoot, String suiteTitle, boolean force, boolean logsOnly) {
        Path file = projectRoot.resolve("merv.properties");
        String fresh = buildFreshProperties(suiteTitle, logsOnly);

        if (!Files.isRegularFile(file)) {
            try {
                Files.writeString(file, fresh, StandardCharsets.UTF_8);
                return new MervDoctorStep("merv.properties", MervDoctorStep.Action.CREATED, file.toString(), null);
            } catch (IOException e) {
                return new MervDoctorStep("merv.properties", MervDoctorStep.Action.FAILED, file.toString(), e.getMessage());
            }
        }

        if (force) {
            try {
                Files.writeString(file, fresh, StandardCharsets.UTF_8);
                return new MervDoctorStep("merv.properties", MervDoctorStep.Action.UPDATED, file.toString(), "rewrote (--force)");
            } catch (IOException e) {
                return new MervDoctorStep("merv.properties", MervDoctorStep.Action.FAILED, file.toString(), e.getMessage());
            }
        }

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> map = parseProperties(text);
            List<String> added = new ArrayList<>();
            addIfMissing(map, added, "merv.local", "true");
            addIfMissing(map, added, "merv.report.folder", "./merv-reports/");
            addIfMissing(map, added, "merv.regression_suite", suiteTitle);
            if (!logsOnly) {
                addIfMissing(map, added, "merv.screenshot", "false");
            }
            addIfMissing(map, added, "merv.emailable.html", "false");
            addIfMissing(map, added, "merv.logger.file", "true");
            addIfMissing(map, added, "merv.logger.level", "INFO");
            addIfMissing(map, added, "merv.project", suiteTitle);
            addIfMissing(map, added, "merv.log.source", suiteTitle);

            if (added.isEmpty()) {
                return new MervDoctorStep(
                        "merv.properties",
                        MervDoctorStep.Action.SKIPPED,
                        file.toString(),
                        "required keys already present");
            }

            String merged = mergeProperties(text, added, defaultValues(suiteTitle));
            Files.writeString(file, merged, StandardCharsets.UTF_8);
            return new MervDoctorStep(
                    "merv.properties",
                    MervDoctorStep.Action.UPDATED,
                    file.toString(),
                    "added missing keys: " + String.join(", ", added));
        } catch (IOException e) {
            return new MervDoctorStep("merv.properties", MervDoctorStep.Action.FAILED, file.toString(), e.getMessage());
        }
    }

    private static void addIfMissing(Map<String, String> map, List<String> added, String key, String value) {
        if (!map.containsKey(key) || map.get(key) == null || map.get(key).isBlank()) {
            map.put(key, value);
            added.add(key);
        }
    }

    private static MervDoctorStep ensureProperty(Path projectRoot, String key, String value) {
        Path file = projectRoot.resolve("merv.properties");
        if (!Files.isRegularFile(file)) {
            return new MervDoctorStep(key, MervDoctorStep.Action.SKIPPED, file.toString(), "merv.properties missing");
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> map = parseProperties(text);
            if (map.containsKey(key) && map.get(key) != null && !map.get(key).isBlank()) {
                return new MervDoctorStep(key, MervDoctorStep.Action.SKIPPED, file.toString(), "already set");
            }
            String merged = mergeProperties(text, List.of(key), Map.of(key, value));
            Files.writeString(file, merged, StandardCharsets.UTF_8);
            return new MervDoctorStep(key, MervDoctorStep.Action.UPDATED, file.toString(), "added " + key + "=" + value);
        } catch (IOException e) {
            return new MervDoctorStep(key, MervDoctorStep.Action.FAILED, file.toString(), e.getMessage());
        }
    }

    private static String buildFreshProperties(String suiteTitle, boolean logsOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by merv-client doctor setup").append(System.lineSeparator());
        sb.append("merv.local=true").append(System.lineSeparator());
        sb.append("merv.regression_suite=").append(suiteTitle).append(System.lineSeparator());
        sb.append("merv.report.folder=./merv-reports/").append(System.lineSeparator());
        if (!logsOnly) {
            sb.append("merv.screenshot=false").append(System.lineSeparator());
        }
        sb.append("merv.emailable.html=false").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("# Merv-Logs (NDJSON under merv-reports/log/)").append(System.lineSeparator());
        sb.append("merv.logger.file=true").append(System.lineSeparator());
        sb.append("merv.logger.level=INFO").append(System.lineSeparator());
        sb.append("merv.project=").append(suiteTitle).append(System.lineSeparator());
        sb.append("merv.log.source=").append(suiteTitle).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("# Serve reports: merv-client show-report --host 0.0.0.0").append(System.lineSeparator());
        sb.append("# Forward logs to a hub: merv.log.server=http://127.0.0.1:6174").append(System.lineSeparator());
        sb.append("# Spring Boot: doctor adds logback-spring.xml with MervLogbackAppender").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        return sb.toString();
    }

    private static Map<String, String> parseProperties(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx < 0) {
                continue;
            }
            map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return map;
    }

    private static Map<String, String> defaultValues(String suiteTitle) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("merv.local", "true");
        d.put("merv.report.folder", "./merv-reports/");
        d.put("merv.regression_suite", suiteTitle);
        d.put("merv.screenshot", "false");
        d.put("merv.emailable.html", "false");
        d.put("merv.logger.file", "true");
        d.put("merv.logger.level", "INFO");
        d.put("merv.project", suiteTitle);
        d.put("merv.log.source", suiteTitle);
        return d;
    }

    private static String mergeProperties(String text, List<String> keysToAdd, Map<String, String> values) {
        Map<String, String> map = parseProperties(text);
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\R", -1)) {
            out.add(raw);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        List<String> added = new ArrayList<>();
        for (String key : keysToAdd) {
            if (!map.containsKey(key) || map.get(key) == null || map.get(key).isBlank()) {
                added.add(key);
            }
        }
        if (!added.isEmpty()) {
            out.add("");
            out.add("# Added by merv-client doctor");
            for (String key : added) {
                out.add(key + "=" + values.getOrDefault(key, ""));
            }
        }
        out.add("");
        return String.join(System.lineSeparator(), out);
    }
}
