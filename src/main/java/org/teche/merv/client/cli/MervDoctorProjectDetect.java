package org.teche.merv.client.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Detects project type from {@code pom.xml}. */
final class MervDoctorProjectDetect {

    private MervDoctorProjectDetect() {
    }

    static boolean isSpringBoot(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            return text.contains("spring-boot-starter")
                    || text.contains("spring-boot-starter-parent");
        } catch (IOException e) {
            return false;
        }
    }

    static boolean hasMavenPom(Path projectRoot) {
        return Files.isRegularFile(projectRoot.resolve("pom.xml"));
    }
}
