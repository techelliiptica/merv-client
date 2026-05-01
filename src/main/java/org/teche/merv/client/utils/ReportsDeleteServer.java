package org.teche.merv.client.utils;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.teche.merv.client.plugin.MervCucumberHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Minimal localhost HTTP API so {@code reports/index.html} can delete report folders on disk.
 * <p>
 * Binds to {@code 127.0.0.1} only. Not tied to Step Editor (port 6174/9090).
 * </p>
 * <pre>
 *   mvn -q exec:java -Dexec.classpathScope=test -Dexec.mainClass="org.teche.merv.client.utils.ReportsDeleteServer"
 * </pre>
 * Port: first command-line argument, or {@code merv.reports.delete.port} in {@code merv.properties}, or {@value #DEFAULT_PORT}.
 */
public final class ReportsDeleteServer {

    private static final int DEFAULT_PORT = 9191;
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/reports/delete", new DeleteHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Local reports delete API (this JVM only, 127.0.0.1):");
        System.out.println("  POST http://127.0.0.1:" + port + "/api/reports/delete  body: {\"folder\":\"<report-folder-name>\"}");
        System.out.println("Leave this process running while using Delete on reports/index.html. Ctrl+C to stop.");
    }

    public static int resolvePort(String[] args) {
        if (args != null && args.length > 0) {
            try {
                int p = Integer.parseInt(args[0].trim());
                if (p >= 1 && p <= 65535) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            File f = new File(System.getProperty("user.dir"), "merv.properties");
            if (f.isFile()) {
                Properties p = new Properties();
                try (InputStream in = new FileInputStream(f)) {
                    p.load(in);
                }
                String v = p.getProperty("merv.reports.delete.port");
                if (v != null && !v.trim().isEmpty()) {
                    int port = Integer.parseInt(v.trim());
                    if (port >= 1 && port <= 65535) {
                        return port;
                    }
                }
            }
        } catch (Exception ignored) {
            // default
        }
        return DEFAULT_PORT;
    }

    private static void setCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
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

    static final class DeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCors(exchange);
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.getResponseBody().close();
                return;
            }
            if (!"POST".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                exchange.getResponseBody().close();
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
                byte[] json = GSON.toJson(res).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(err == null ? 200 : 400, json.length);
                OutputStream os = exchange.getResponseBody();
                os.write(json);
                os.close();
                if (err == null) {
                    System.out.println("Deleted report folder: " + folder);
                }
            } catch (Exception e) {
                Map<String, Object> res = new HashMap<>();
                res.put("ok", false);
                res.put("error", e.getMessage() != null ? e.getMessage() : "Delete failed");
                byte[] json = GSON.toJson(res).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(500, json.length);
                OutputStream os = exchange.getResponseBody();
                os.write(json);
                os.close();
            }
        }
    }
}
