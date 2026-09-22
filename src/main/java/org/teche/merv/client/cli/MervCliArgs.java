package org.teche.merv.client.cli;

import java.util.ArrayList;
import java.util.List;

/** Parsed {@code merv-client} CLI arguments (aligned with npm {@code merv-client}). */
public final class MervCliArgs {

    public enum Command {
        HELP,
        SHOW_REPORT,
        SHOW_LOGS,
        DOCTOR_SETUP,
        DOCTOR_SETUP_LOG,
        DOCTOR_SET_CONSOLE,
        DOCTOR_UNSET_CONSOLE
    }

    private final Command command;
    private final String reportDir;
    private final String host;
    private final Integer port;
    private final boolean openBrowser;
    private final boolean force;
    private final String suiteTitle;
    private final boolean help;

    private MervCliArgs(
            Command command,
            String reportDir,
            String host,
            Integer port,
            boolean openBrowser,
            boolean force,
            String suiteTitle,
            boolean help) {
        this.command = command;
        this.reportDir = reportDir;
        this.host = host;
        this.port = port;
        this.openBrowser = openBrowser;
        this.force = force;
        this.suiteTitle = suiteTitle;
        this.help = help;
    }

    public Command getCommand() {
        return command;
    }

    public String getReportDir() {
        return reportDir;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public boolean isOpenBrowser() {
        return openBrowser;
    }

    public boolean isForce() {
        return force;
    }

    public String getSuiteTitle() {
        return suiteTitle;
    }

    public boolean isHelp() {
        return help;
    }

    public static MervCliArgs parse(String[] argv) {
        List<String> args = new ArrayList<>();
        for (String a : argv) {
            if (a != null) {
                args.add(a);
            }
        }

        if (args.isEmpty() || containsHelp(args)) {
            return new MervCliArgs(Command.HELP, null, null, null, true, false, null, true);
        }

        String first = args.get(0);
        if ("doctor".equals(first)) {
            return parseDoctor(args);
        }
        if ("show-report".equals(first) || "show-logs".equals(first)) {
            return parseShowReport(args, "show-logs".equals(first) ? Command.SHOW_LOGS : Command.SHOW_REPORT);
        }

        return new MervCliArgs(Command.HELP, null, null, null, true, false, null, true);
    }

    private static boolean containsHelp(List<String> args) {
        for (String a : args) {
            if ("-h".equals(a) || "--help".equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static MervCliArgs parseDoctor(List<String> args) {
        if (args.size() < 2 || containsHelp(args)) {
            return new MervCliArgs(Command.HELP, null, null, null, true, false, null, true);
        }
        String sub = args.get(1);
        Command cmd;
        int optStart;
        if ("setup".equals(sub)) {
            cmd = Command.DOCTOR_SETUP;
            optStart = 2;
        } else if ("setup-log".equals(sub) || "setup-logs".equals(sub)) {
            cmd = Command.DOCTOR_SETUP_LOG;
            optStart = 2;
        } else if ("set".equals(sub) && args.size() > 2 && "console".equals(args.get(2))) {
            cmd = Command.DOCTOR_SET_CONSOLE;
            optStart = 3;
        } else if ("unset".equals(sub) && args.size() > 2 && "console".equals(args.get(2))) {
            cmd = Command.DOCTOR_UNSET_CONSOLE;
            optStart = 3;
        } else {
            throw new IllegalArgumentException("Unknown doctor subcommand: " + sub
                    + " (expected setup, setup-log, set console, or unset console)");
        }

        boolean force = false;
        String suiteTitle = null;
        for (int i = optStart; i < args.size(); i++) {
            String a = args.get(i);
            if ("--force".equals(a)) {
                force = true;
            } else if ("--suite-title".equals(a)) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("Missing value for --suite-title");
                }
                suiteTitle = args.get(++i);
            } else if (a.startsWith("-")) {
                throw new IllegalArgumentException("Unknown option: " + a);
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + a);
            }
        }
        return new MervCliArgs(cmd, null, null, null, true, force, suiteTitle, false);
    }

    private static MervCliArgs parseShowReport(List<String> args, Command command) {
        if (containsHelp(args)) {
            return new MervCliArgs(Command.HELP, null, null, null, true, false, null, true);
        }

        String reportDir = null;
        String host = null;
        Integer port = null;
        boolean openBrowser = true;

        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if ("--no-open".equals(a)) {
                openBrowser = false;
            } else if ("--port".equals(a)) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("Missing value for --port");
                }
                try {
                    int p = Integer.parseInt(args.get(++i).trim());
                    if (p < 1 || p > 65535) {
                        throw new IllegalArgumentException("Invalid --port: " + p);
                    }
                    port = p;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid --port: " + args.get(i));
                }
            } else if ("--host".equals(a)) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("Missing value for --host");
                }
                host = args.get(++i).trim();
                if (host.isEmpty()) {
                    throw new IllegalArgumentException("Missing value for --host");
                }
            } else if (a.startsWith("-")) {
                throw new IllegalArgumentException("Unknown option: " + a);
            } else if (reportDir == null) {
                reportDir = a;
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + a);
            }
        }

        return new MervCliArgs(command, reportDir, host, port, openBrowser, false, null, false);
    }
}
