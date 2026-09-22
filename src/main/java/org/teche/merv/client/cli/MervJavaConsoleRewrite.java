package org.teche.merv.client.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code set}: rewrite {@code System.out.*} → {@code MervSystem.*} and SLF4J/Log4j/JUL
 * loggers → {@link org.teche.merv.client.logging.MervLogger}. {@code unset}: revert
 * doctor-managed lines only (tagged {@value #MARKER}).
 */
public final class MervJavaConsoleRewrite {

    public static final String MARKER = "merv-doctor-console";

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".idea", "target", "build", "out", "node_modules",
            "merv-reports", "dist", "coverage", ".mvn");

    private static final Pattern SYSTEM_OUT = Pattern.compile("System\\.out\\.(println|print|printf)\\s*\\(");

    private static final Pattern LOGGER_FIELD_SLF4J = Pattern.compile(
            "(?m)^(?<indent>\\s*)(?<mods>(?:(?:private|public|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?)"
                    + "Logger\\s+(?<var>\\w+)\\s*=\\s*LoggerFactory\\.getLogger\\((?<arg>[^)]+)\\)\\s*;");

    private static final Pattern LOGGER_FIELD_LOG4J2 = Pattern.compile(
            "(?m)^(?<indent>\\s*)(?<mods>(?:(?:private|public|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?)"
                    + "Logger\\s+(?<var>\\w+)\\s*=\\s*LogManager\\.getLogger\\((?<arg>[^)]+)\\)\\s*;");

    private static final Pattern LOGGER_FIELD_LOG4J1 = Pattern.compile(
            "(?m)^(?<indent>\\s*)(?<mods>(?:(?:private|public|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?)"
                    + "Logger\\s+(?<var>\\w+)\\s*=\\s*Logger\\.getLogger\\((?<arg>[^)]+)\\)\\s*;");

    private static final Pattern LOGGER_FIELD_JUL = Pattern.compile(
            "(?m)^(?<indent>\\s*)(?<mods>(?:(?:private|public|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?)"
                    + "Logger\\s+(?<var>\\w+)\\s*=\\s*Logger\\.getLogger\\((?<arg>[^)]+)\\)\\s*;",
            Pattern.MULTILINE);

    private MervJavaConsoleRewrite() {
    }

    public enum Mode {
        SET, UNSET
    }

    public static final class Summary {
        public int scanned;
        public final List<String> added = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();
        public int skipped;
    }

    public static Summary rewrite(Path projectRoot, Mode mode) throws IOException {
        Summary summary = new Summary();
        List<Path> files = listJavaFiles(projectRoot);
        summary.scanned = files.size();

        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String rel = projectRoot.relativize(file).toString().replace('\\', '/');

            if (mode == Mode.UNSET) {
                String next = unset(source);
                if (!next.equals(source)) {
                    Files.writeString(file, next, StandardCharsets.UTF_8);
                    summary.removed.add(rel);
                } else {
                    summary.skipped++;
                }
                continue;
            }

            if (!needsRewrite(source)) {
                summary.skipped++;
                continue;
            }

            String next = applySet(source);
            if (!next.equals(source)) {
                Files.writeString(file, next, StandardCharsets.UTF_8);
                summary.added.add(rel);
            } else {
                summary.skipped++;
            }
        }
        return summary;
    }

    static boolean hasDoctorMarkers(String source) {
        return source.contains(MARKER);
    }

    static boolean needsRewrite(String source) {
        String stripped = stripComments(source);
        if (SYSTEM_OUT.matcher(stripped).find()) {
            return true;
        }
        return detectLoggerKind(stripped) != LoggerKind.NONE;
    }

    static String applySet(String source) {
        LoggerKind kind = detectLoggerKind(stripComments(source));
        String out = source;

        if (kind != LoggerKind.NONE) {
            out = removeImports(out, kind.importsToRemove());
            out = replaceLoggerField(out, kind);
            out = ensureImports(out, kind.mervImportsNeeded(), true);
        }

        if (SYSTEM_OUT.matcher(stripComments(out)).find()) {
            out = replaceSystemOutLines(out);
            out = ensureImports(out, List.of(
                    "import org.teche.merv.client.logging.MervSystem;"), true);
        }

        return out;
    }

    static String unset(String source) {
        String out = source;

        // Revert logger fields before removing imports (markers on field lines carry kind)
        out = revertLoggerFields(out);

        // Revert System.out lines (doctor-tagged and user-added MervSystem)
        out = revertSystemOutLines(out);

        // Remove doctor merv imports
        out = removeDoctorImportLines(out);

        // Remove user-added MervSystem import (doctor import may already be removed above)
        out = removeMervSystemImports(out);

        return out;
    }

    private static String replaceSystemOutLines(String source) {
        String[] lines = source.split("\\R", -1);
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                sb.append(line).append(eol);
                continue;
            }
            if (line.contains(MARKER)) {
                sb.append(line).append(eol);
                continue;
            }
            Matcher m = SYSTEM_OUT.matcher(line);
            if (m.find()) {
                String replaced = m.replaceAll("MervSystem.$1(");
                if (!replaced.contains(MARKER)) {
                    replaced = appendMarker(replaced, "system-out");
                }
                sb.append(replaced).append(eol);
            } else {
                sb.append(line).append(eol);
            }
        }
        if (!source.endsWith("\n") && !source.endsWith("\r\n") && sb.length() >= eol.length()) {
            sb.setLength(sb.length() - eol.length());
        }
        return sb.toString();
    }

    private static String revertSystemOutLines(String source) {
        String[] lines = source.split("\\R", -1);
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                sb.append(line).append(eol);
                continue;
            }
            if (line.contains("MervSystem.")) {
                String reverted = line
                        .replaceAll("MervSystem\\.(println|print|printf)\\s*\\(", "System.out.$1(")
                        .replaceAll("\\s*//\\s*" + MARKER + ":system-out\\s*$", "");
                sb.append(reverted).append(eol);
            } else {
                sb.append(line).append(eol);
            }
        }
        if (!source.endsWith("\n") && !source.endsWith("\r\n") && sb.length() >= eol.length()) {
            sb.setLength(sb.length() - eol.length());
        }
        return sb.toString();
    }

    private static String replaceLoggerField(String source, LoggerKind kind) {
        Pattern p = kind.fieldPattern();
        Matcher m = p.matcher(source);
        if (!m.find()) {
            return source;
        }
        String replacement = m.group("indent") + m.group("mods") + "MervLogger " + m.group("var")
                + " = MervLoggerFactory.getLogger(" + m.group("arg") + "); // " + MARKER + ":" + kind.id();
        return m.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String revertLoggerFields(String source) {
        String out = source;
        for (LoggerKind kind : LoggerKind.values()) {
            if (kind == LoggerKind.NONE) {
                continue;
            }
            Pattern p = Pattern.compile(
                    "(?m)^(?<indent>\\s*)(?<mods>(?:(?:private|public|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?)"
                            + "MervLogger\\s+(?<var>\\w+)\\s*=\\s*MervLoggerFactory\\.getLogger\\((?<arg>[^)]+)\\)\\s*;"
                            + "\\s*//\\s*" + MARKER + ":" + kind.id() + "\\s*$");
            Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            boolean found = false;
            while (m.find()) {
                found = true;
                String field = m.group("indent") + m.group("mods") + kind.loggerTypeName() + " " + m.group("var")
                        + " = " + kind.factoryCall(m.group("arg")) + ";";
                m.appendReplacement(sb, Matcher.quoteReplacement(field));
            }
            m.appendTail(sb);
            if (found) {
                out = sb.toString();
                out = ensureImports(out, kind.importsToRestore(), false);
            }
        }
        return out;
    }

    private static String removeDoctorImportLines(String source) {
        String[] lines = source.split("\\R", -1);
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.contains(MARKER) && line.contains("merv-import")) {
                continue;
            }
            sb.append(line).append(eol);
        }
        if (!source.endsWith("\n") && !source.endsWith("\r\n") && sb.length() >= eol.length()) {
            sb.setLength(sb.length() - eol.length());
        }
        return sb.toString();
    }

    private static String removeMervSystemImports(String source) {
        return source.replaceAll(
                "(?m)^\\s*import\\s+org\\.teche\\.merv\\.client\\.logging\\.MervSystem\\s*;(?:\\s*//.*)?\\s*\\r?\\n",
                "");
    }

    private static String removeImports(String source, List<String> imports) {
        String out = source;
        for (String imp : imports) {
            out = out.replaceAll("(?m)^\\s*" + Pattern.quote(imp) + "\\s*\\r?\\n", "");
        }
        return out;
    }

    private static String ensureImports(String source, List<String> imports, boolean doctorTag) {
        Set<String> existing = new LinkedHashSet<>();
        for (String line : source.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("import ")) {
                existing.add(t.replaceAll("\\s*//.*$", "").trim());
            }
        }
        List<String> toAdd = new ArrayList<>();
        for (String imp : imports) {
            String bare = imp.trim();
            if (!existing.contains(bare)) {
                toAdd.add(doctorTag ? bare + " // " + MARKER + ":merv-import" : bare);
            }
        }
        if (toAdd.isEmpty()) {
            return source;
        }
        int insertAt = findImportInsertIndex(source);
        String[] lines = source.split("\\R", -1);
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == insertAt) {
                for (String imp : toAdd) {
                    sb.append(imp).append(eol);
                }
            }
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append(eol);
            }
        }
        return sb.toString();
    }

    private static int findImportInsertIndex(String source) {
        String[] lines = source.split("\\R", -1);
        int packageLine = -1;
        int lastImport = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("package ")) {
                packageLine = i;
            } else if (t.startsWith("import ")) {
                lastImport = i;
            } else if (!t.isEmpty() && !t.startsWith("//") && lastImport >= 0) {
                break;
            }
        }
        if (lastImport >= 0) {
            return lastImport + 1;
        }
        return packageLine >= 0 ? packageLine + 1 : 0;
    }

    private static String appendMarker(String line, String kind) {
        String trimmed = line.stripTrailing();
        if (trimmed.endsWith(";")) {
            return trimmed + " // " + MARKER + ":" + kind;
        }
        return trimmed + " // " + MARKER + ":" + kind;
    }

    private static String stripComments(String source) {
        String s = source.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        s = s.replaceAll("(?m)//.*$", "");
        return s;
    }

    static LoggerKind detectLoggerKind(String stripped) {
        if (stripped.contains("LoggerFactory.getLogger") || stripped.contains("org.slf4j")) {
            return LoggerKind.SLF4J;
        }
        if (stripped.contains("LogManager.getLogger") || stripped.contains("org.apache.logging.log4j")) {
            return LoggerKind.LOG4J2;
        }
        if (stripped.contains("org.apache.log4j.Logger") || stripped.contains("org.apache.log4j")) {
            return LoggerKind.LOG4J1;
        }
        if (stripped.contains("java.util.logging.Logger")) {
            return LoggerKind.JUL;
        }
        return LoggerKind.NONE;
    }

    enum LoggerKind {
        NONE("none", null, List.of(), List.of(), List.of()) {
            @Override Pattern fieldPattern() { return Pattern.compile("(?!)"); }
            @Override String loggerTypeName() { return "Logger"; }
            @Override String factoryCall(String arg) { return ""; }
        },
        SLF4J("slf4j", LOGGER_FIELD_SLF4J,
                List.of("import org.slf4j.Logger;", "import org.slf4j.LoggerFactory;"),
                List.of("import org.teche.merv.client.logging.MervLogger;",
                        "import org.teche.merv.client.logging.MervLoggerFactory;"),
                List.of("import org.slf4j.Logger;", "import org.slf4j.LoggerFactory;")) {
            @Override String loggerTypeName() { return "Logger"; }
            @Override String factoryCall(String arg) { return "LoggerFactory.getLogger(" + arg + ")"; }
        },
        LOG4J2("log4j2", LOGGER_FIELD_LOG4J2,
                List.of("import org.apache.logging.log4j.Logger;",
                        "import org.apache.logging.log4j.LogManager;"),
                List.of("import org.teche.merv.client.logging.MervLogger;",
                        "import org.teche.merv.client.logging.MervLoggerFactory;"),
                List.of("import org.apache.logging.log4j.Logger;",
                        "import org.apache.logging.log4j.LogManager;")) {
            @Override String loggerTypeName() { return "Logger"; }
            @Override String factoryCall(String arg) { return "LogManager.getLogger(" + arg + ")"; }
        },
        LOG4J1("log4j", LOGGER_FIELD_LOG4J1,
                List.of("import org.apache.log4j.Logger;"),
                List.of("import org.teche.merv.client.logging.MervLogger;",
                        "import org.teche.merv.client.logging.MervLoggerFactory;"),
                List.of("import org.apache.log4j.Logger;")) {
            @Override String loggerTypeName() { return "Logger"; }
            @Override String factoryCall(String arg) { return "Logger.getLogger(" + arg + ")"; }
        },
        JUL("jul", LOGGER_FIELD_JUL,
                List.of("import java.util.logging.Logger;"),
                List.of("import org.teche.merv.client.logging.MervLogger;",
                        "import org.teche.merv.client.logging.MervLoggerFactory;"),
                List.of("import java.util.logging.Logger;")) {
            @Override String loggerTypeName() { return "Logger"; }
            @Override String factoryCall(String arg) { return "Logger.getLogger(" + arg + ")"; }
        };

        private final String id;
        private final Pattern pattern;
        private final List<String> removeImports;
        private final List<String> mervImports;
        private final List<String> restoreImports;

        LoggerKind(String id, Pattern pattern, List<String> removeImports,
                   List<String> mervImports, List<String> restoreImports) {
            this.id = id;
            this.pattern = pattern;
            this.removeImports = removeImports;
            this.mervImports = mervImports;
            this.restoreImports = restoreImports;
        }

        String id() { return id; }
        Pattern fieldPattern() { return pattern; }
        List<String> importsToRemove() { return removeImports; }
        List<String> mervImportsNeeded() { return mervImports; }
        List<String> importsToRestore() { return restoreImports; }
        abstract String loggerTypeName();
        abstract String factoryCall(String arg);
    }

    private static List<Path> listJavaFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (!dir.equals(root) && SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    String fn = file.getFileName().toString();
                    if (!"MervSystem.java".equals(fn) && !fn.startsWith("MervJavaConsoleRewrite")) {
                        out.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}
