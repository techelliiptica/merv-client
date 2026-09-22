package org.teche.merv.client.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates or heals {@code src/main/resources/logback-spring.xml} with {@code MervLogbackAppender}
 * for Spring Boot projects (SLF4J → Merv-Logs via {@code merv.properties}).
 */
final class MervDoctorLogbackSetup {

    static final String MARKER = "merv-doctor-logback";
    static final String APPENDER_CLASS = "org.teche.merv.client.logging.appender.MervLogbackAppender";

    private MervDoctorLogbackSetup() {
    }

    static MervDoctorStep ensureLogbackSpring(Path projectRoot, boolean force) {
        if (!MervDoctorProjectDetect.isSpringBoot(projectRoot)) {
            return new MervDoctorStep(
                    "logback-spring.xml",
                    MervDoctorStep.Action.SKIPPED,
                    null,
                    "not a Spring Boot project — add MervLogbackAppender to your logging config manually");
        }

        Path file = projectRoot.resolve("src/main/resources/logback-spring.xml");
        try {
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, template(), StandardCharsets.UTF_8);
                return new MervDoctorStep(
                        "logback-spring.xml",
                        MervDoctorStep.Action.CREATED,
                        file.toString(),
                        "MervLogbackAppender wired for SLF4J → Merv-Logs");
            }

            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains(APPENDER_CLASS) || text.contains(MARKER)) {
                if (force) {
                    Files.writeString(file, template(), StandardCharsets.UTF_8);
                    return new MervDoctorStep(
                            "logback-spring.xml",
                            MervDoctorStep.Action.UPDATED,
                            file.toString(),
                            "rewrote with MervLogbackAppender (--force)");
                }
                return new MervDoctorStep(
                        "logback-spring.xml",
                        MervDoctorStep.Action.SKIPPED,
                        file.toString(),
                        "MervLogbackAppender already configured");
            }

            if (force) {
                Files.writeString(file, template(), StandardCharsets.UTF_8);
                return new MervDoctorStep(
                        "logback-spring.xml",
                        MervDoctorStep.Action.UPDATED,
                        file.toString(),
                        "rewrote custom file with doctor template (--force)");
            }

            return new MervDoctorStep(
                    "logback-spring.xml",
                    MervDoctorStep.Action.SKIPPED,
                    file.toString(),
                    "custom logback-spring.xml — add MERV appender manually (see merv.online docs)");
        } catch (IOException e) {
            return new MervDoctorStep(
                    "logback-spring.xml",
                    MervDoctorStep.Action.FAILED,
                    file.toString(),
                    e.getMessage());
        }
    }

    static String template() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration>
                    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                    <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
                    <include resource="org/springframework/boot/logging/logback/file-appender.xml"/>

                    <!-- %s: MervLogbackAppender — reads merv.properties -->
                    <appender name="MERV" class="%s"/>

                    <root level="INFO">
                        <appender-ref ref="CONSOLE"/>
                        <appender-ref ref="FILE"/>
                        <appender-ref ref="MERV"/>
                    </root>
                </configuration>
                """.formatted(MARKER, APPENDER_CLASS);
    }
}
