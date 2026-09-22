package org.teche.merv.client.cli;

import com.sun.net.httpserver.HttpServer;
import org.teche.merv.client.utils.MervLocalReportServer;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;

/** Runs {@code show-report} / {@code show-logs} (aligned with {@code npx merv show-report}). */
public final class MervShowReportCommand {

    private static final String DEFAULT_HOST = "127.0.0.1";

    private MervShowReportCommand() {
    }

    public static void run(MervCliArgs args) throws Exception {
        String[] serverArgs = buildServerArgs(args);
        File reportRoot = MervLocalReportServer.resolveReportRoot(serverArgs);
        String host = args.getHost() != null ? args.getHost() : MervLocalReportServer.resolveHost();
        int port = args.getPort() != null ? args.getPort() : MervLocalReportServer.resolvePort(serverArgs);

        HttpServer server = MervLocalReportServer.start(reportRoot, host, port);
        String displayHost = "0.0.0.0".equals(host) || "::".equals(host) ? "127.0.0.1" : host;
        String baseUrl = "http://" + displayHost + ":" + port;
        String openPath = args.getCommand() == MervCliArgs.Command.SHOW_LOGS ? "/merv-logs.html" : "/";

        System.out.println("[Merv] Serving local reports");
        System.out.println("[Merv]   folder: " + reportRoot.getAbsolutePath());
        System.out.println("[Merv]   url:    " + baseUrl + "/");
        if (args.getCommand() == MervCliArgs.Command.SHOW_LOGS) {
            System.out.println("[Merv]   logs:   " + baseUrl + "/merv-logs.html");
        }
        System.out.println("[Merv] Press Ctrl+C to stop.");

        if (args.isOpenBrowser()) {
            openBrowser(baseUrl + openPath);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        Thread.currentThread().join();
    }

    private static String[] buildServerArgs(MervCliArgs args) {
        if (args.getReportDir() == null && args.getPort() == null) {
            return new String[0];
        }
        if (args.getReportDir() != null && args.getPort() != null) {
            return new String[]{args.getReportDir(), String.valueOf(args.getPort())};
        }
        if (args.getReportDir() != null) {
            return new String[]{args.getReportDir()};
        }
        return new String[]{String.valueOf(args.getPort())};
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    System.out.println("[Merv] Opened " + url);
                    return;
                }
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        System.out.println("[Merv] Open in browser: " + url);
    }
}
