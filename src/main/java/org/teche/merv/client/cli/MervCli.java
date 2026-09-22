package org.teche.merv.client.cli;

import org.teche.merv.client.utils.MervLocalReportServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MERV Java CLI — same commands as {@code npx merv-client} for local reports.
 *
 * <pre>
 *   merv-client doctor setup
 *   merv-client doctor setup-log
 *   merv-client show-report [reportDir] [--host 0.0.0.0] [--port 6174] [--no-open]
 *   merv-client show-logs [reportDir] [options]
 * </pre>
 */
public final class MervCli {

    private MervCli() {
    }

    public static void main(String[] argv) {
        int exit = run(argv);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] argv) {
        MervCliArgs parsed;
        try {
            parsed = MervCliArgs.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("[merv-client] " + e.getMessage());
            printHelp();
            return 1;
        }

        if (parsed.isHelp() || parsed.getCommand() == MervCliArgs.Command.HELP) {
            printHelp();
            return 0;
        }

        try {
            return switch (parsed.getCommand()) {
                case SHOW_REPORT, SHOW_LOGS -> {
                    MervShowReportCommand.run(parsed);
                    yield 0;
                }
                case DOCTOR_SETUP -> runDoctor(parsed, false);
                case DOCTOR_SETUP_LOG -> runDoctor(parsed, true);
                case DOCTOR_SET_CONSOLE -> runConsoleDoctor(MervJavaConsoleRewrite.Mode.SET);
                case DOCTOR_UNSET_CONSOLE -> runConsoleDoctor(MervJavaConsoleRewrite.Mode.UNSET);
                default -> {
                    System.err.println("[merv-client] Unknown command");
                    printHelp();
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.err.println("[merv-client] " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private static int runConsoleDoctor(MervJavaConsoleRewrite.Mode mode) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        MervJavaConsoleRewrite.Summary summary = MervJavaConsoleRewrite.rewrite(root, mode);
        List<MervDoctorStep> steps = new ArrayList<>();
        for (String rel : summary.added) {
            steps.add(new MervDoctorStep(rel, MervDoctorStep.Action.UPDATED, rel,
                    "added MervSystem / MervLogger (merv-doctor-console)"));
        }
        for (String rel : summary.removed) {
            steps.add(new MervDoctorStep(rel, MervDoctorStep.Action.UPDATED, rel,
                    "reverted to System.out / original logger"));
        }
        steps.add(new MervDoctorStep(
                "summary",
                MervDoctorStep.Action.SKIPPED,
                null,
                mode == MervJavaConsoleRewrite.Mode.SET
                        ? String.format("scanned %d: added %d, removed %d, skipped %d",
                        summary.scanned, summary.added.size(), summary.removed.size(), summary.skipped)
                        : String.format("scanned %d: removed %d, skipped %d",
                        summary.scanned, summary.removed.size(), summary.skipped)));
        String title = mode == MervJavaConsoleRewrite.Mode.SET
                ? "merv-client doctor set console"
                : "merv-client doctor unset console";
        String subtitle = mode == MervJavaConsoleRewrite.Mode.SET
                ? "System.out → MervSystem; SLF4J/Log4j/JUL → MervLogger (idempotent; use unset to revert)"
                : "Revert doctor-managed MervSystem / MervLogger lines only";
        MervDoctorConsole.printReport(title, subtitle, steps);
        return 0;
    }

    private static int runDoctor(MervCliArgs parsed, boolean logsOnly) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<MervDoctorStep> steps = MervDoctorSetup.runSetup(
                root,
                logsOnly,
                parsed.isForce(),
                parsed.getSuiteTitle());
        String title = logsOnly ? "merv-client doctor setup-log" : "merv-client doctor setup";
        String subtitle = logsOnly
                ? "Merv-Logs — merv.properties, MervLogbackAppender (Spring Boot), pom dependency"
                : "Java/Maven scaffold — merv.properties, logback-spring.xml, pom.xml";
        MervDoctorConsole.printReport(title, subtitle, steps);
        for (MervDoctorStep s : steps) {
            if (s.getAction() == MervDoctorStep.Action.FAILED) {
                return 1;
            }
        }
        return 0;
    }

    static void printHelp() {
        System.out.println("""
                Usage:
                  merv-client doctor setup [options]
                  merv-client doctor setup-log [options]
                  merv-client doctor set console
                  merv-client doctor unset console
                  merv-client show-report [reportDir] [options]
                  merv-client show-logs [reportDir] [options]

                Commands:
                  doctor setup         Create/heal merv.properties, pom.xml, Spring Boot logback (MervLogAppender)
                  doctor setup-log     Merv-Logs only (merv.properties + logger + logback when Spring Boot)
                  doctor set console   System.out → MervSystem; SLF4J/Log4j/JUL → MervLogger
                  doctor unset console Revert doctor-managed console/logger wiring
                  show-report          Serve local MERV HTML reports (default http://127.0.0.1:%d/)
                  show-logs            Same server, opens Merv-Logs (…/merv-logs.html)

                doctor options:
                  --force
                  --suite-title <name>

                show-report / show-logs options:
                  --port <n>             Port (default: %d)
                  --host <host>          Bind address (default: 127.0.0.1; use 0.0.0.0 for LAN)
                  --no-open              Do not open a browser

                Examples:
                  merv-client doctor setup
                  merv-client doctor setup-log
                  merv-client doctor set console
                  merv-client doctor unset console
                  merv-client show-report
                  merv-client show-report --host 0.0.0.0
                  merv-client show-logs
                """.formatted(MervLocalReportServer.DEFAULT_PORT, MervLocalReportServer.DEFAULT_PORT));
    }
}
