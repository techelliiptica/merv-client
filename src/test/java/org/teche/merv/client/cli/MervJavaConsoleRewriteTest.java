package org.teche.merv.client.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MervJavaConsoleRewriteTest {

    @Test
    void setReplacesSystemOut() {
        String src = """
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """;
        String out = MervJavaConsoleRewrite.applySet(src);
        assertTrue(out.contains("MervSystem.println"));
        assertTrue(out.contains("merv-doctor-console"));
        assertFalse(out.contains("System.out.println"));
    }

    @Test
    void unsetRevertsSystemOut() {
        String src = """
                import org.teche.merv.client.logging.MervSystem; // merv-doctor-console:merv-import
                public class Demo {
                    public static void main(String[] args) {
                        MervSystem.println("hello"); // merv-doctor-console:system-out
                    }
                }
                """;
        String out = MervJavaConsoleRewrite.unset(src);
        assertTrue(out.contains("System.out.println"));
        assertFalse(out.contains("MervSystem"));
        assertFalse(out.contains("merv-doctor-console"));
    }

    @Test
    void unsetRevertsUserAddedMervSystem() {
        String src = """
                import org.teche.merv.client.logging.MervSystem;
                public class Demo {
                    public static void main(String[] args) {
                        MervSystem.println("hello");
                    }
                }
                """;
        String out = MervJavaConsoleRewrite.unset(src);
        assertTrue(out.contains("System.out.println"));
        assertFalse(out.contains("MervSystem"));
        assertFalse(out.contains("org.teche.merv.client.logging.MervSystem"));
    }

    @Test
    void setReplacesSlf4jLogger() {
        String src = """
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                public class Demo {
                    private static final Logger log = LoggerFactory.getLogger(Demo.class);
                    void run() { log.info("hi"); }
                }
                """;
        String out = MervJavaConsoleRewrite.applySet(src);
        assertTrue(out.contains("MervLogger"));
        assertTrue(out.contains("MervLoggerFactory.getLogger"));
        assertTrue(out.contains("merv-doctor-console:slf4j"));
        assertFalse(out.contains("org.slf4j.Logger"));
    }

    @Test
    void unsetRevertsSlf4jLogger() throws Exception {
        String src = """
                import org.teche.merv.client.logging.MervLogger; // merv-doctor-console:merv-import
                import org.teche.merv.client.logging.MervLoggerFactory; // merv-doctor-console:merv-import
                public class Demo {
                    private static final MervLogger log = MervLoggerFactory.getLogger(Demo.class); // merv-doctor-console:slf4j
                    void run() { log.info("hi"); }
                }
                """;
        String out = MervJavaConsoleRewrite.unset(src);
        assertTrue(out.contains("org.slf4j.Logger"));
        assertTrue(out.contains("LoggerFactory.getLogger"));
        assertFalse(out.contains("MervLogger"));
    }

    @Test
    void rewriteOnDisk(@TempDir Path dir) throws Exception {
        Path java = dir.resolve("src/Demo.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("x");
                    }
                }
                """);
        MervJavaConsoleRewrite.Summary s = MervJavaConsoleRewrite.rewrite(dir, MervJavaConsoleRewrite.Mode.SET);
        assertEquals(1, s.added.size());
        String written = Files.readString(java);
        assertTrue(written.contains("MervSystem.println"));

        MervJavaConsoleRewrite.Summary secondSet = MervJavaConsoleRewrite.rewrite(dir, MervJavaConsoleRewrite.Mode.SET);
        assertEquals(0, secondSet.added.size());
        assertEquals(0, secondSet.removed.size());
        written = Files.readString(java);
        assertTrue(written.contains("MervSystem.println"));

        MervJavaConsoleRewrite.rewrite(dir, MervJavaConsoleRewrite.Mode.UNSET);
        written = Files.readString(java);
        assertTrue(written.contains("System.out.println"));
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
