package org.teche.merv.client.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** Finds {@code merv.properties} by walking up from {@code user.dir} (IDE-safe). */
public final class MervPropertiesLocator {

    private MervPropertiesLocator() {
    }

    public static File findMervPropertiesFile() {
        String fromEnv = System.getenv("MERV_PROJECT_ROOT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            File envFile = new File(fromEnv.trim(), "merv.properties");
            if (envFile.isFile()) {
                return envFile;
            }
        }
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null) {
            File candidate = new File(dir, "merv.properties");
            if (candidate.isFile()) {
                return candidate;
            }
            File parent = dir.getParentFile();
            if (parent == null || parent.equals(dir)) {
                break;
            }
            dir = parent;
        }
        return null;
    }

    public static Properties loadMervProperties() {
        File file = findMervPropertiesFile();
        if (file == null) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
            return props;
        } catch (Exception e) {
            return null;
        }
    }
}
