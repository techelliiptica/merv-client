package org.teche.merv.client.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Async POST of log lines to {@code merv.log.server}; drains pending sends on JVM exit. */
final class MervLogRemoteForwarder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT);
    private static final AtomicInteger PENDING = new AtomicInteger(0);
    private static final int SHUTDOWN_WAIT_MS = 4000;
    private static volatile boolean shutdownHookRegistered;

    private MervLogRemoteForwarder() {
    }

    static void forward(MervLogRecord record, String serverBase) {
        if (record == null || serverBase == null || serverBase.isBlank()) {
            return;
        }
        ensureShutdownHook();
        PENDING.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                post(record, serverBase);
            } finally {
                PENDING.decrementAndGet();
            }
        }, "merv-log-forward");
        t.setDaemon(true);
        t.start();
    }

    private static void post(MervLogRecord record, String serverBase) {
        try {
            String base = serverBase.trim().replaceAll("/+$", "");
            HttpURLConnection conn = (HttpURLConnection) new URI(base + "/api/logs/ingest").toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("records", List.of(record));
            byte[] body = MAPPER.writeValueAsBytes(payload);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
            /* never break the test run */
        }
    }

    private static void ensureShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        synchronized (MervLogRemoteForwarder.class) {
            if (shutdownHookRegistered) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS;
                while (PENDING.get() > 0 && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "merv-log-forward-drain"));
            shutdownHookRegistered = true;
        }
    }
}
