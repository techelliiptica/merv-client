package org.teche.merv.client.report.html;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds an offline-shareable {@code merv-report-offline.zip} of a run folder
 * (root {@code index.html} → suite HTML + emailable summary).
 */
public final class MervOfflineReportZipWriter {

    public static final String OFFLINE_ZIP_NAME = "merv-report-offline.zip";
    private static final String UPLOAD_ZIP_NAME = "merv-report-upload.zip";

    private static final Pattern SIDEBAR_LOCAL_LABEL = Pattern.compile(
            "<a\\b[^>]*\\bclass=\"[^\"]*\\bsidebar-local-label\\b[^\"]*\"[^>]*>[\\s\\S]*?</a>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_DASH = Pattern.compile(
            "<a\\b[^>]*\\bclass=\"[^\"]*\\blocal-dash\\b[^\"]*\"[^>]*>[\\s\\S]*?</a>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_HREF = Pattern.compile(
            "\\bhref=\"(?:\\.\\./)+index\\.html\"",
            Pattern.CASE_INSENSITIVE);

    private MervOfflineReportZipWriter() {
    }

    public static String formatByteSize(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double n = bytes;
        int i = -1;
        do {
            n /= 1024.0;
            i += 1;
        } while (n >= 1024 && i < units.length - 1);
        int digits = n >= 100 || i == 0 ? 0 : n >= 10 ? 1 : 2;
        return String.format(Locale.ROOT, "%." + digits + "f %s", n, units[i]);
    }

    /**
     * Package a run folder for offline sharing.
     *
     * @param runFolder directory containing {@code json/merv-report.json}
     */
    public static Result packageOfflineReportZip(File runFolder) throws IOException {
        return packageOfflineReportZip(runFolder, null);
    }

    public static Result packageOfflineReportZip(File runFolder, Path outputPath) throws IOException {
        Objects.requireNonNull(runFolder, "runFolder");
        Path resolvedRunDir = runFolder.toPath().toAbsolutePath().normalize();
        Path jsonPath = resolvedRunDir.resolve("json").resolve("merv-report.json");
        if (!Files.isRegularFile(jsonPath)) {
            throw new IOException("MERV offline zip: missing " + jsonPath);
        }

        Path emailablePath = MervEmailableHtmlWriter.writeFromDisk(resolvedRunDir.toFile());

        Path htmlDir = resolvedRunDir.resolve("html");
        Path finalHtml = htmlDir.resolve("merv-report.html");
        Path liveHtml = htmlDir.resolve("merv-report-live.html");
        if (!Files.isRegularFile(finalHtml) && Files.isRegularFile(liveHtml)) {
            Files.createDirectories(htmlDir);
            Files.copy(liveHtml, finalHtml, StandardCopyOption.REPLACE_EXISTING);
        }

        Path zipPath = outputPath != null
                ? outputPath.toAbsolutePath().normalize()
                : resolvedRunDir.resolve(OFFLINE_ZIP_NAME);

        List<ZipSource> entries = new ArrayList<>();
        entries.add(ZipSource.ofData("index.html", offlineIndexHtml()));

        Set<String> seen = new HashSet<>();
        seen.add("index.html");
        collectFiles(resolvedRunDir.toFile(), "", entries, seen);

        Files.createDirectories(zipPath.getParent() != null ? zipPath.getParent() : resolvedRunDir);
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(zipPath));
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (ZipSource entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.zipEntry));
                if (entry.data != null) {
                    zos.write(entry.data);
                } else if (entry.abs != null) {
                    try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(entry.abs))) {
                        in.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }
        }

        long zipBytes = Files.size(zipPath);
        long emailableBytes = Files.size(emailablePath);
        return new Result(
                zipPath,
                zipBytes,
                formatByteSize(zipBytes),
                emailablePath,
                emailableBytes,
                formatByteSize(emailableBytes));
    }

    private static String offlineIndexHtml() {
        String name = MervEmailableHtmlWriter.EMAILABLE_NAME;
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"utf-8\" />\n"
                + "  <meta http-equiv=\"refresh\" content=\"0; url=html/merv-report.html\" />\n"
                + "  <title>MERV Report</title>\n"
                + "  <script>location.replace('html/merv-report.html');</script>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <p>Opening report… If nothing happens, open "
                + "<a href=\"html/merv-report.html\">html/merv-report.html</a>.</p>\n"
                + "  <p>Summary: <a href=\"" + MervHtmlEscape.escapeHtml(name) + "\">"
                + MervHtmlEscape.escapeHtml(name) + "</a></p>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /** Offline zip has no report-root dashboard — drop Merv Local / Local Dashboard links. */
    static String stripMervLocalLinks(String html) {
        if (html == null) {
            return "";
        }
        String out = SIDEBAR_LOCAL_LABEL.matcher(html)
                .replaceAll("<span class=\"sidebar-local-label\">Merv</span>");
        out = LOCAL_DASH.matcher(out).replaceAll("");
        out = INDEX_HREF.matcher(out).replaceAll("href=\"#\"");
        return out;
    }

    private static boolean shouldSkip(String rel) {
        String lower = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = lower;
        int slash = lower.lastIndexOf('/');
        if (slash >= 0) {
            base = lower.substring(slash + 1);
        }
        if (OFFLINE_ZIP_NAME.equals(base) || UPLOAD_ZIP_NAME.equals(base) || ".ds_store".equals(base)) {
            return true;
        }
        return lower.endsWith(".zip");
    }

    private static void collectFiles(File dir, String prefix, List<ZipSource> out, Set<String> seen) {
        File[] names = dir.listFiles();
        if (names == null) {
            return;
        }
        for (File child : names) {
            String rel = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            String zipEntry = rel.replace('\\', '/');
            if (shouldSkip(zipEntry)) {
                continue;
            }
            if (child.isDirectory()) {
                collectFiles(child, zipEntry, out, seen);
            } else if (child.isFile()) {
                if (!seen.add(zipEntry)) {
                    continue;
                }
                String lower = zipEntry.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                    try {
                        String raw = Files.readString(child.toPath(), StandardCharsets.UTF_8);
                        out.add(ZipSource.ofData(zipEntry, stripMervLocalLinks(raw)));
                        continue;
                    } catch (IOException ignored) {
                        // fall through to raw file
                    }
                }
                out.add(ZipSource.ofFile(zipEntry, child));
            }
        }
    }

    private static final class ZipSource {
        final String zipEntry;
        final File abs;
        final byte[] data;

        private ZipSource(String zipEntry, File abs, byte[] data) {
            this.zipEntry = zipEntry;
            this.abs = abs;
            this.data = data;
        }

        static ZipSource ofFile(String zipEntry, File abs) {
            return new ZipSource(zipEntry, abs, null);
        }

        static ZipSource ofData(String zipEntry, String text) {
            return new ZipSource(zipEntry, null, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Result of packaging an offline report zip. */
    public static final class Result {
        private final Path zipPath;
        private final long zipBytes;
        private final String zipSizeLabel;
        private final Path emailablePath;
        private final long emailableBytes;
        private final String emailableSizeLabel;

        public Result(
                Path zipPath,
                long zipBytes,
                String zipSizeLabel,
                Path emailablePath,
                long emailableBytes,
                String emailableSizeLabel) {
            this.zipPath = zipPath;
            this.zipBytes = zipBytes;
            this.zipSizeLabel = zipSizeLabel;
            this.emailablePath = emailablePath;
            this.emailableBytes = emailableBytes;
            this.emailableSizeLabel = emailableSizeLabel;
        }

        public Path getZipPath() {
            return zipPath;
        }

        public long getZipBytes() {
            return zipBytes;
        }

        public String getZipSizeLabel() {
            return zipSizeLabel;
        }

        public Path getEmailablePath() {
            return emailablePath;
        }

        public long getEmailableBytes() {
            return emailableBytes;
        }

        public String getEmailableSizeLabel() {
            return emailableSizeLabel;
        }
    }
}
