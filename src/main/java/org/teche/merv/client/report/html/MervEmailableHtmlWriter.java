package org.teche.merv.client.report.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.teche.merv.client.config.MervConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes interactive {@code emailable-report.html} from {@code json/merv-report.json}.
 * Gated by {@code merv.emailable.html=true}; download paths always call {@link #writeFromDisk}.
 */
public final class MervEmailableHtmlWriter {

    public static final String EMAILABLE_NAME = "emailable-report.html";

    private static final String TEMPLATE_RESOURCE =
            "org/teche/merv/client/report/html/emailable-report.template.html";
    private static final String JSON_PLACEHOLDER = "/*__MERV_EMAILABLE_JSON__*/null";
    private static final String LOGO_PLACEHOLDER = "/*__MERV_EMAILABLE_LOGO__*/\"\"";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile String cachedTemplate;

    private MervEmailableHtmlWriter() {
    }

    /**
     * When {@code merv.emailable.html} is truthy, writes emailable HTML for the run
     * (and copies to the report root). No-ops otherwise.
     */
    public static Path writeIfEnabled(File runFolder) {
        if (!MervConfig.isEmailableHtmlEnabled() || runFolder == null || !runFolder.isDirectory()) {
            return null;
        }
        try {
            Path written = writeFromDisk(runFolder);
            System.out.println("MERV emailable report: " + written.toAbsolutePath());
            return written;
        } catch (Exception e) {
            System.err.println("MERV emailable report failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Always regenerates {@code emailable-report.html} from disk JSON (used by download / offline zip).
     *
     * @param runFolder run directory containing {@code json/merv-report.json}
     * @return path to the emailable file under the run folder
     */
    public static Path writeFromDisk(File runFolder) throws IOException {
        Objects.requireNonNull(runFolder, "runFolder");
        Path runDir = runFolder.toPath().toAbsolutePath().normalize();
        Path jsonPath = runDir.resolve("json").resolve("merv-report.json");
        if (!Files.isRegularFile(jsonPath)) {
            throw new IOException("MERV emailable: missing " + jsonPath);
        }

        JsonNode report = MAPPER.readTree(jsonPath.toFile());
        String html = buildHtml(report);

        Path runOut = runDir.resolve(EMAILABLE_NAME);
        Files.writeString(runOut, html, StandardCharsets.UTF_8);

        File parent = runFolder.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
            Files.writeString(parent.toPath().resolve(EMAILABLE_NAME), html, StandardCharsets.UTF_8);
        }
        return runOut;
    }

    static String buildHtml(JsonNode report) throws IOException {
        String template = loadTemplate();
        String json = MAPPER.writeValueAsString(report);
        // Prevent </script> breakout when embedding JSON in a script tag.
        json = json.replace("</", "<\\/");
        String logoJson = MAPPER.writeValueAsString(MervReportBranding.LOGO_URL);
        return template
                .replace(JSON_PLACEHOLDER, json)
                .replace(LOGO_PLACEHOLDER, logoJson);
    }

    private static String loadTemplate() throws IOException {
        String cached = cachedTemplate;
        if (cached != null) {
            return cached;
        }
        synchronized (MervEmailableHtmlWriter.class) {
            if (cachedTemplate != null) {
                return cachedTemplate;
            }
            try (InputStream in = MervEmailableHtmlWriter.class.getClassLoader()
                    .getResourceAsStream(TEMPLATE_RESOURCE)) {
                if (in == null) {
                    throw new IOException("Missing classpath resource: " + TEMPLATE_RESOURCE);
                }
                cachedTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return cachedTemplate;
            }
        }
    }
}
