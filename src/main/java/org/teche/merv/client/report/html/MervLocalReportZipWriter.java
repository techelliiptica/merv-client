package org.teche.merv.client.report.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.utils.MervPropertyFlags;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds {@code merv-report-upload.zip} for MERV UI import ({@code json/merv-report.json} + screenshots).
 */
public final class MervLocalReportZipWriter {

    private static final String ZIP_NAME = "merv-report-upload.zip";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MervLocalReportZipWriter() {
    }

    public static boolean isZipExportEnabled() {
        String raw = MervConfig.getProperty("merv.zip.export", "true");
        if (raw == null) {
            return true;
        }
        return MervPropertyFlags.isTruthyValue(raw);
    }

    /**
     * @param runFolder run directory containing {@code json/merv-report.json}
     * @return path to the created zip, or {@code null} when export is disabled or JSON is missing
     */
    public static Path writeUploadZipIfEnabled(File runFolder) throws IOException {
        if (!isZipExportEnabled() || runFolder == null || !runFolder.isDirectory()) {
            return null;
        }
        Path json = runFolder.toPath().resolve("json").resolve("merv-report.json");
        if (!Files.isRegularFile(json)) {
            return null;
        }
        Path zip = runFolder.toPath().resolve(ZIP_NAME);
        writeUploadZip(runFolder.toPath(), zip);
        System.out.println("MERV upload zip: " + zip.toAbsolutePath());
        return zip;
    }

    public static void writeUploadZip(Path runFolder, Path zipFile) throws IOException {
        Path json = runFolder.resolve("json").resolve("merv-report.json");
        if (!Files.isRegularFile(json)) {
            throw new IOException("MERV zip: missing " + json);
        }

        Set<String> added = new HashSet<>();
        Files.createDirectories(zipFile.getParent() != null ? zipFile.getParent() : runFolder);

        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(zipFile));
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            addFile(zos, json, "json/merv-report.json", added);
            collectRunImages(runFolder, runFolder, zos, added);
            collectReferencedScreenshots(json, runFolder, zos, added);
        }
    }

    private static void collectRunImages(Path runFolder, Path dir, ZipOutputStream zos, Set<String> added)
            throws IOException {
        File[] children = dir.toFile().listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            Path childPath = child.toPath();
            String rel = runFolder.relativize(childPath).toString().replace('\\', '/');
            if (child.isDirectory()) {
                if (shouldSkip(rel + "/")) {
                    continue;
                }
                collectRunImages(runFolder, childPath, zos, added);
            } else if (isImage(child.getName())) {
                if (!shouldSkip(rel)) {
                    addFile(zos, childPath, rel, added);
                }
            }
        }
    }

    private static void collectReferencedScreenshots(Path json, Path runFolder, ZipOutputStream zos, Set<String> added)
            throws IOException {
        JsonNode root = MAPPER.readTree(json.toFile());
        JsonNode cases = root.path("testSuite").path("testCases");
        if (!cases.isArray()) {
            return;
        }
        for (JsonNode tc : cases) {
            JsonNode steps = tc.path("testSteps");
            if (!steps.isArray()) {
                continue;
            }
            for (JsonNode step : steps) {
                JsonNode shots = step.path("screenshots");
                if (!shots.isArray()) {
                    continue;
                }
                for (JsonNode shot : shots) {
                    if (!shot.isTextual()) {
                        continue;
                    }
                    String rel = shot.asText().trim().replace('\\', '/');
                    if (rel.isEmpty()) {
                        continue;
                    }
                    while (rel.startsWith("/")) {
                        rel = rel.substring(1);
                    }
                    Path abs = runFolder.resolve(rel).normalize();
                    addFile(zos, abs, rel, added);
                    if (!rel.contains("/")) {
                        String inScreenshots = "screenshots/" + new File(rel).getName();
                        addFile(zos, runFolder.resolve(inScreenshots), inScreenshots, added);
                    }
                }
            }
        }
    }

    private static boolean shouldSkip(String normalizedRel) {
        String lower = normalizedRel.toLowerCase();
        if ("json/merv-report.json".equals(lower)) {
            return false;
        }
        return lower.startsWith("json/") || lower.startsWith("html/")
                || lower.contains("/json/") || lower.contains("/html/");
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    private static void addFile(ZipOutputStream zos, Path file, String entryName, Set<String> added)
            throws IOException {
        String key = entryName.replace('\\', '/');
        if (added.contains(key) || shouldSkip(key) || !Files.isRegularFile(file)) {
            return;
        }
        added.add(key);
        zos.putNextEntry(new ZipEntry(key));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file.toFile()))) {
            in.transferTo(zos);
        }
        zos.closeEntry();
    }
}
