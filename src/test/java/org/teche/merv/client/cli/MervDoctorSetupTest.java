package org.teche.merv.client.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MervDoctorSetupTest {

    @Test
    void setupLogCreatesSpringBootLogback(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <parent>
                    <artifactId>spring-boot-starter-parent</artifactId>
                  </parent>
                  <dependencies></dependencies>
                </project>
                """);

        List<MervDoctorStep> steps = MervDoctorSetup.runSetup(dir, true, false, "demo-service");

        assertStep(steps, "merv.properties", MervDoctorStep.Action.CREATED);
        assertStep(steps, "logback-spring.xml", MervDoctorStep.Action.CREATED);
        assertStep(steps, "pom.xml", MervDoctorStep.Action.UPDATED);

        String logback = Files.readString(dir.resolve("src/main/resources/logback-spring.xml"));
        assertTrue(logback.contains("MervLogbackAppender"));
        assertTrue(logback.contains("merv-doctor-logback"));

        String pom = Files.readString(dir.resolve("pom.xml"));
        assertTrue(pom.contains("merv-client-api"));

        String props = Files.readString(dir.resolve("merv.properties"));
        assertTrue(props.contains("merv.logger.file=true"));
        assertTrue(props.contains("merv.project=demo-service"));
        assertTrue(props.contains("merv.log.source=demo-service"));
    }

    @Test
    void setupLogSkipsLogbackForNonSpring(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><dependencies></dependencies></project>
                """);

        List<MervDoctorStep> steps = MervDoctorSetup.runSetup(dir, true, false, null);

        assertStep(steps, "logback-spring.xml", MervDoctorStep.Action.SKIPPED);
        assertTrue(steps.stream().anyMatch(s ->
                "logback-spring.xml".equals(s.getStep())
                        && s.getDetail() != null
                        && s.getDetail().contains("not a Spring Boot")));
    }

    @Test
    void setupLogSkipsExistingLogback(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><artifactId>merv-client-api</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        Path resources = dir.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("logback-spring.xml"), MervDoctorLogbackSetup.template());

        List<MervDoctorStep> steps = MervDoctorSetup.runSetup(dir, true, false, "x");

        assertStep(steps, "logback-spring.xml", MervDoctorStep.Action.SKIPPED);
        assertStep(steps, "pom.xml", MervDoctorStep.Action.SKIPPED);
    }

    private static void assertStep(List<MervDoctorStep> steps, String name, MervDoctorStep.Action action) {
        boolean found = steps.stream().anyMatch(s -> name.equals(s.getStep()) && s.getAction() == action);
        if (!found) {
            throw new AssertionError("Expected step " + name + " action " + action + " but got: " + steps);
        }
    }
}
