package org.teche.merv.client.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adds {@code merv-client-api} to {@code pom.xml} when missing. */
final class MervDoctorMavenSetup {

    static final String GROUP_ID = "io.github.techelliiptica";
    static final String ARTIFACT_ID = "merv-client-api";

    private MervDoctorMavenSetup() {
    }

    static MervDoctorStep ensureDependency(Path projectRoot, String version) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return new MervDoctorStep(
                    "pom.xml",
                    MervDoctorStep.Action.SKIPPED,
                    null,
                    "no pom.xml — add " + GROUP_ID + ":" + ARTIFACT_ID + " manually");
        }
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            if (text.contains(ARTIFACT_ID)) {
                return new MervDoctorStep(
                        "pom.xml",
                        MervDoctorStep.Action.SKIPPED,
                        pom.toString(),
                        "merv-client-api dependency already present");
            }
            int idx = text.indexOf("</dependencies>");
            if (idx < 0) {
                return new MervDoctorStep(
                        "pom.xml",
                        MervDoctorStep.Action.FAILED,
                        pom.toString(),
                        "could not find </dependencies> in pom.xml");
            }
            String block = dependencyBlock(version);
            String updated = text.substring(0, idx) + block + text.substring(idx);
            Files.writeString(pom, updated, StandardCharsets.UTF_8);
            return new MervDoctorStep(
                    "pom.xml",
                    MervDoctorStep.Action.UPDATED,
                    pom.toString(),
                    "added merv-client-api:" + version);
        } catch (IOException e) {
            return new MervDoctorStep(
                    "pom.xml",
                    MervDoctorStep.Action.FAILED,
                    pom.toString(),
                    e.getMessage());
        }
    }

    static String dependencyBlock(String version) {
        return """
                    <dependency>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                        <version>%s</version>
                    </dependency>
                """.formatted(GROUP_ID, ARTIFACT_ID, version);
    }
}
