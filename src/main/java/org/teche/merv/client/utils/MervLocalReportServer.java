package org.teche.merv.client.utils;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.logging.MervLogRecord;
import org.teche.merv.client.logging.MervLogSink;
import org.teche.merv.client.plugin.MervCucumberHandler;
import org.teche.merv.client.report.html.MervOfflineReportZipWriter;
import org.teche.merv.client.report.html.MervReportsIndexHtmlWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Serve a Merv-Local report root over HTTP (static files + Share/Download APIs).
 * <p>
 * Equivalent to {@code npx merv show-report} for Java-generated reports.
 * </p>
 * <pre>
 *   merv-client show-report
 *   merv-client show-report ./merv-reports 6174
 * </pre>
 * Bind: {@code merv.reports.server.host} (default {@code 127.0.0.1}; use {@code 0.0.0.0} for LAN share).
 * Port: arg / {@code merv.reports.server.port} / {@code merv.reports.delete.port} / {@value #DEFAULT_PORT}.
 */
public final class MervLocalReportServer {

    public static final int DEFAULT_PORT = 6174;
    private static final Gson GSON = new Gson();

    private MervLocalReportServer() {
    }

    public static void main(String[] args) throws Exception {
        File reportRoot = resolveReportRoot(args);
        String host = resolveHost();
        int port = resolvePort(args);
        start(reportRoot, host, port);
        System.out.println("[Merv] Serving local reports");
        System.out.println("[Merv]   folder: " + reportRoot.getAbsolutePath());
        System.out.println("[Merv]   url:    http://" + displayHost(host) + ":" + port + "/");
        System.out.println("[Merv] Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    public static HttpServer start(File reportRoot, String host, int port) throws IOException {
        if (reportRoot == null) {
            throw new IOException("Report folder is null");
        }
        if (!reportRoot.exists()) {
            if (!reportRoot.mkdirs()) {
                throw new IOException("Could not create report folder: " + reportRoot.getAbsolutePath());
            }
            System.out.println("[Merv] Created report folder: " + reportRoot.getAbsolutePath());
        } else if (!reportRoot.isDirectory()) {
            throw new IOException("Report path exists but is not a folder: " + reportRoot.getAbsolutePath());
        }
        try {
            MervReportsIndexHtmlWriter.write(reportRoot.getAbsolutePath());
        } catch (Exception ignored) {
            /* best effort */
        }
        try {
            org.teche.merv.client.logging.MervLogSink.ensureMervLogsPage(reportRoot.getAbsolutePath());
        } catch (Exception ignored) {
            /* best effort */
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/reports/delete", new DeleteHandler());
        server.createContext("/api/share-info", new ShareInfoHandler(host, port));
        server.createContext("/api/prepare-download", new PrepareDownloadHandler(reportRoot));
        server.createContext("/api/logs/ingest", new LogsIngestHandler(reportRoot));
        server.createContext("/", new StaticHandler(reportRoot));
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static String displayHost(String host) {
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            return "127.0.0.1";
        }
        return host;
    }

    public static File resolveReportRoot(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a == null || a.startsWith("-")) {
                    continue;
                }
                try {
                    Integer.parseInt(a.trim());
                    continue;
                } catch (NumberFormatException ignored) {
                    File f = new File(a.trim());
                    if (!f.isAbsolute()) {
                        f = new File(System.getProperty("user.dir"), a.trim());
                    }
                    // Accept path even if not created yet — start() will mkdir.
                    if (!f.exists() || f.isDirectory()) {
                        return f.getAbsoluteFile();
                    }
                }
            }
        }
        try {
            String folder = MervConfig.getReportFolder();
            if (folder != null && !folder.trim().isEmpty()) {
                File f = new File(folder.trim());
                if (!f.isAbsolute()) {
                    f = new File(System.getProperty("user.dir"), folder.trim());
                }
                return f.getAbsoluteFile();
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        return new File(System.getProperty("user.dir"), "merv-reports").getAbsoluteFile();
    }

    public static String resolveHost() {
        try {
            File f = new File(System.getProperty("user.dir"), "merv.properties");
            if (f.isFile()) {
                Properties p = new Properties();
                try (InputStream in = new FileInputStream(f)) {
                    p.load(in);
                }
                String v = p.getProperty("merv.reports.server.host");
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        } catch (Exception ignored) {
            /* default */
        }
        return "127.0.0.1";
    }

    public static int resolvePort(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a == null) {
                    continue;
                }
                try {
                    int p = Integer.parseInt(a.trim());
                    if (p >= 1 && p <= 65535) {
                        return p;
                    }
                } catch (NumberFormatException ignored) {
                    /* not a port */
                }
            }
        }
        try {
            File f = new File(System.getProperty("user.dir"), "merv.properties");
            if (f.isFile()) {
                Properties p = new Properties();
                try (InputStream in = new FileInputStream(f)) {
                    p.load(in);
                }
                for (String key : new String[]{"merv.reports.server.port", "merv.reports.delete.port"}) {
                    String v = p.getProperty(key);
                    if (v != null && !v.trim().isEmpty()) {
                        int port = Integer.parseInt(v.trim());
                        if (port >= 1 && port <= 65535) {
                            return port;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            /* default */
        }
        return DEFAULT_PORT;
    }

    private static void setCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        setCors(exchange);
        byte[] json = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) >= 0) {
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static List<String> listLanIpv4Addresses() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            /* empty */
        }
        return new ArrayList<>(out);
    }

    static final class LogsIngestHandler implements HttpHandler {
        private final File reportRoot;

        LogsIngestHandler(File reportRoot) {
            this.reportRoot = reportRoot;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                setCors(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Method not allowed"));
                return;
            }
            try {
                String body = readBody(exchange);
                List<MervLogRecord> records = new ArrayList<>();
                Object parsed = body == null || body.trim().isEmpty() ? null : GSON.fromJson(body, Object.class);
                if (parsed instanceof List) {
                    for (Object item : (List<?>) parsed) {
                        MervLogRecord r = toLogRecord(item);
                        if (r != null) {
                            records.add(r);
                        }
                    }
                } else if (parsed instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    Object list = map.get("records");
                    if (list instanceof List) {
                        for (Object item : (List<?>) list) {
                            MervLogRecord r = toLogRecord(item);
                            if (r != null) {
                                records.add(r);
                            }
                        }
                    } else if (map.get("record") != null) {
                        MervLogRecord r = toLogRecord(map.get("record"));
                        if (r != null) {
                            records.add(r);
                        }
                    } else {
                        MervLogRecord r = toLogRecord(map);
                        if (r != null) {
                            records.add(r);
                        }
                    }
                }
                if (records.isEmpty()) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "Expected { records: [...] }"));
                    return;
                }
                for (MervLogRecord r : records) {
                    MervLogSink.appendMervLogRecordLocal(r, reportRoot.getAbsolutePath());
                }
                sendJson(exchange, 200, Map.of("ok", true, "accepted", records.size()));
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of(
                        "ok", false,
                        "error", e.getMessage() != null ? e.getMessage() : "ingest failed"));
            }
        }

        @SuppressWarnings("unchecked")
        private static MervLogRecord toLogRecord(Object raw) {
            if (!(raw instanceof Map)) {
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            MervLogRecord r = new MervLogRecord();
            Object ts = m.get("ts");
            r.setTs(ts != null ? String.valueOf(ts) : java.time.Instant.now().toString());
            Object level = m.get("level");
            r.setLevel(level != null ? String.valueOf(level) : "INFO");
            Object name = m.get("name");
            r.setName(name != null ? String.valueOf(name) : "Merv");
            Object msg = m.get("msg");
            r.setMsg(msg != null ? String.valueOf(msg) : "");
            if (m.get("stack") != null) {
                r.setStack(String.valueOf(m.get("stack")));
            }
            if (m.get("suite") != null) {
                r.setSuite(String.valueOf(m.get("suite")));
            }
            if (m.get("testcase") != null) {
                r.setTestcase(String.valueOf(m.get("testcase")));
            }
            if (m.get("screenshot") != null) {
                r.setScreenshot(String.valueOf(m.get("screenshot")));
            }
            if (m.get("project") != null) {
                r.setProject(String.valueOf(m.get("project")));
            }
            return r;
        }
    }

    static final class ShareInfoHandler implements HttpHandler {
        private final String bindHost;
        private final int port;

        ShareInfoHandler(String bindHost, int port) {
            this.bindHost = bindHost;
            this.port = port;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                setCors(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            List<String> lan = listLanIpv4Addresses();
            boolean localOnly = "127.0.0.1".equals(bindHost)
                    || "localhost".equalsIgnoreCase(bindHost)
                    || "::1".equals(bindHost);
            boolean lanReachable = "0.0.0.0".equals(bindHost)
                    || "::".equals(bindHost)
                    || lan.contains(bindHost);
            Map<String, Object> res = new HashMap<>();
            res.put("ok", true);
            res.put("bindHost", bindHost);
            res.put("port", port);
            res.put("lanAddresses", lan);
            res.put("lanReachable", lanReachable);
            res.put("suggestedHost", lan.isEmpty() ? bindHost : lan.get(0));
            res.put("localOnly", localOnly);
            sendJson(exchange, 200, res);
        }
    }

    static final class PrepareDownloadHandler implements HttpHandler {
        private final File reportRoot;

        PrepareDownloadHandler(File reportRoot) {
            this.reportRoot = reportRoot;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                setCors(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String folder = "";
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = body == null || body.trim().isEmpty()
                        ? Collections.emptyMap()
                        : GSON.fromJson(body, Map.class);
                if (map != null && map.get("folder") != null) {
                    folder = String.valueOf(map.get("folder")).trim();
                }
            } else {
                String q = exchange.getRequestURI().getRawQuery();
                if (q != null) {
                    for (String part : q.split("&")) {
                        int eq = part.indexOf('=');
                        if (eq > 0 && "folder".equals(URLDecoder.decode(part.substring(0, eq), "UTF-8"))) {
                            folder = URLDecoder.decode(part.substring(eq + 1), "UTF-8").trim();
                        }
                    }
                }
            }
            if (folder.isEmpty() || folder.contains("..") || folder.contains("/") || folder.contains("\\")) {
                Map<String, Object> err = new HashMap<>();
                err.put("ok", false);
                err.put("error", "Invalid folder name");
                sendJson(exchange, 400, err);
                return;
            }
            File runDir = new File(reportRoot, folder);
            if (!runDir.isDirectory()) {
                Map<String, Object> err = new HashMap<>();
                err.put("ok", false);
                err.put("error", "Folder not found: " + folder);
                sendJson(exchange, 404, err);
                return;
            }
            try {
                MervOfflineReportZipWriter.Result result = MervOfflineReportZipWriter.packageOfflineReportZip(runDir);
                String enc = java.net.URLEncoder.encode(folder, StandardCharsets.UTF_8.name()).replace("+", "%20");
                Map<String, Object> res = new HashMap<>();
                res.put("ok", true);
                res.put("folder", folder);
                res.put("zipFileName", MervOfflineReportZipWriter.OFFLINE_ZIP_NAME);
                res.put("zipUrl", "/" + enc + "/" + MervOfflineReportZipWriter.OFFLINE_ZIP_NAME);
                res.put("zipBytes", result.getZipBytes());
                res.put("zipSizeLabel", result.getZipSizeLabel());
                res.put("emailableFileName", "emailable-report.html");
                res.put("emailableUrl", "/" + enc + "/emailable-report.html");
                res.put("emailableBytes", result.getEmailableBytes());
                res.put("emailableSizeLabel", result.getEmailableSizeLabel());
                sendJson(exchange, 200, res);
            } catch (Exception e) {
                Map<String, Object> err = new HashMap<>();
                err.put("ok", false);
                err.put("error", e.getMessage() != null ? e.getMessage() : "Prepare download failed");
                sendJson(exchange, 500, err);
            }
        }
    }

    static final class DeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            try {
                String body = readBody(exchange);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = body == null || body.trim().isEmpty()
                        ? Collections.emptyMap()
                        : GSON.fromJson(body, Map.class);
                String folder = map != null && map.get("folder") != null
                        ? String.valueOf(map.get("folder")).trim()
                        : null;
                String err = MervCucumberHandler.deleteReportRunFolder(folder);
                Map<String, Object> res = new HashMap<>();
                if (err == null) {
                    res.put("ok", true);
                } else {
                    res.put("ok", false);
                    res.put("error", err);
                }
                sendJson(exchange, err == null ? 200 : 400, res);
            } catch (Exception e) {
                Map<String, Object> res = new HashMap<>();
                res.put("ok", false);
                res.put("error", e.getMessage() != null ? e.getMessage() : "Delete failed");
                sendJson(exchange, 500, res);
            }
        }
    }

    static final class StaticHandler implements HttpHandler {
        private final Path root;

        StaticHandler(File reportRoot) {
            this.root = reportRoot.toPath().toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String raw = exchange.getRequestURI().getRawPath();
            if (raw == null || raw.isEmpty() || "/".equals(raw)) {
                raw = "/index.html";
            }
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
            Path target = root.resolve(decoded.replaceFirst("^/+", "")).normalize();
            if (!target.startsWith(root)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            if (Files.isDirectory(target)) {
                target = target.resolve("index.html");
            }
            if (!Files.isRegularFile(target)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = Files.readAllBytes(target);
            String ct = contentType(target.getFileName().toString());
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, body.length);
            if (!"HEAD".equals(exchange.getRequestMethod())) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else {
                exchange.close();
            }
        }

        private static String contentType(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                return "text/html; charset=utf-8";
            }
            if (lower.endsWith(".js")) {
                return "text/javascript; charset=utf-8";
            }
            if (lower.endsWith(".css")) {
                return "text/css; charset=utf-8";
            }
            if (lower.endsWith(".json")) {
                return "application/json; charset=utf-8";
            }
            if (lower.endsWith(".png")) {
                return "image/png";
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            if (lower.endsWith(".gif")) {
                return "image/gif";
            }
            if (lower.endsWith(".webp")) {
                return "image/webp";
            }
            if (lower.endsWith(".zip")) {
                return "application/zip";
            }
            if (lower.endsWith(".webm")) {
                return "video/webm";
            }
            if (lower.endsWith(".mp4")) {
                return "video/mp4";
            }
            return "application/octet-stream";
        }
    }
}
