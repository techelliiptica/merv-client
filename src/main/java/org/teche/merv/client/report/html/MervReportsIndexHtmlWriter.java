package org.teche.merv.client.report.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.utils.FileUtils;
import org.teche.merv.client.utils.ReportsDeleteServer;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds {@code reports/index.html}: local dashboard listing runs, KPI/consolidated views, and Chart.js analytics.
 * Call {@link #write} after any run produces or updates JSON under the report root so the dashboard stays current.
 * Other runners (e.g. TestNG/JUnit) can reuse this without duplicating HTML as long as they emit compatible {@code merv-report.json}.
 */
public final class MervReportsIndexHtmlWriter {

    private MervReportsIndexHtmlWriter() {}

    private static final ObjectMapper INDEX_OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static String urlPathEncode(String folderName) {
        return URLEncoder.encode(folderName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String jsDoubleQuoted(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    /**
     * Port for {@link ReportsDeleteServer}; embedded in generated {@code reports/index.html} for Delete.
     */
    private static int readReportsDeleteApiPort() {
        return ReportsDeleteServer.resolvePort(new String[0]);
    }

    private static final class ReportSummary {
        String title = "Test execution";
        String env = "Local";
        String release = "—";
        String sprint = "—";
        String tagsDisplay = "";
        List<String> tagPills = new ArrayList<>();
        int total;
        int pass;
        int fail;
        int skip;
        boolean runningFromJson;
    }

    private static final class ReportIndexEntry {
        final String folderName;
        final long lastModified;
        final long sortKey;
        final boolean hasLive;
        final boolean hasFinal;
        final ReportSummary summary;

        ReportIndexEntry(String folderName, long lastModified, long sortKey, boolean hasLive, boolean hasFinal, ReportSummary summary) {
            this.folderName = folderName;
            this.lastModified = lastModified;
            this.sortKey = sortKey;
            this.hasLive = hasLive;
            this.hasFinal = hasFinal;
            this.summary = summary;
        }
    }

    private static ReportSummary loadReportSummaryFromJson(File jsonFile, boolean hasFinal, boolean hasLive) {
        ReportSummary s = new ReportSummary();
        s.runningFromJson = hasLive && !hasFinal;
        if (!jsonFile.isFile()) {
            return s;
        }
        try {
            JsonNode root = INDEX_OBJECT_MAPPER.readTree(jsonFile);
            if (root.has("running")) {
                s.runningFromJson = root.path("running").asBoolean(false);
            }
            JsonNode suite = root.path("testSuite");
            String t = suite.path("title").asText(null);
            if (t != null && !t.isEmpty()) {
                s.title = t;
            }
            JsonNode cases = suite.path("testCases");
            if (cases.isArray()) {
                LinkedHashSet<String> tagSet = new LinkedHashSet<>();
                for (JsonNode tc : cases) {
                    s.total++;
                    String st = tc.path("status").asText("");
                    if ("PASSED".equals(st)) {
                        s.pass++;
                    } else if ("FAILED".equals(st)) {
                        s.fail++;
                    } else if ("SKIPPED".equals(st)) {
                        s.skip++;
                    }
                    JsonNode tags = tc.path("tags");
                    if (tags.isArray()) {
                        for (JsonNode tg : tags) {
                            String tgStr = tg.asText("");
                            if (!tgStr.isEmpty() && tagSet.size() < 16) {
                                tagSet.add(tgStr);
                            }
                        }
                    }
                }
                s.tagPills = new ArrayList<>(tagSet);
                s.tagsDisplay = tagSet.stream().map(String::toLowerCase).collect(Collectors.joining(" "));
            }
        } catch (Exception ignored) {
            // keep defaults
        }
        return s;
    }

    private static String donutConicStyle(int pass, int fail, int skip) {
        int t = pass + fail + skip;
        if (t <= 0) {
            return "background:#e9ecef;";
        }
        double pEnd = 360.0 * pass / t;
        double fEnd = pEnd + 360.0 * fail / t;
        return String.format(Locale.US,
                "background:conic-gradient(#28a745 0deg %.6fdeg, #dc3545 %.6fdeg %.6fdeg, #ffc107 %.6fdeg 360deg);",
                pEnd, pEnd, fEnd, fEnd);
    }

    private static String relativeTimeAgo(long epochMs) {
        long diff = Math.max(0L, System.currentTimeMillis() - epochMs);
        long sec = diff / 1000L;
        if (sec < 45) {
            return "just now";
        }
        long min = sec / 60L;
        if (min < 60) {
            return min == 1 ? "1 min ago" : min + " mins ago";
        }
        long hr = min / 60L;
        if (hr < 24) {
            return hr == 1 ? "1 hour ago" : hr + " hours ago";
        }
        long day = hr / 24L;
        if (day < 30) {
            return day == 1 ? "1 day ago" : day + " days ago";
        }
        long mo = day / 30L;
        if (mo < 12) {
            return mo == 1 ? "1 month ago" : mo + " months ago";
        }
        long yr = mo / 12L;
        return yr == 1 ? "1 year ago" : yr + " years ago";
    }

    public static void write(String baseReportPath) throws Exception {
        String base = baseReportPath.endsWith(File.separator) ? baseReportPath : baseReportPath + File.separator;
        File root = new File(base);
        if (!root.isDirectory()) {
            root.mkdirs();
        }
        File[] subs = root.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        List<ReportIndexEntry> entries = new ArrayList<>();
        SimpleDateFormat folderTs = new SimpleDateFormat("dd-MM-yyyy HH-mm-ss");
        if (subs != null) {
            for (File folder : subs) {
                File finalReport = new File(folder, "html" + File.separator + "merv-report.html");
                File liveReport = new File(folder, "html" + File.separator + "merv-report-live.html");
                boolean hasFinal = finalReport.isFile();
                boolean hasLive = liveReport.isFile();
                if (!hasFinal && !hasLive) {
                    continue;
                }
                long lm = folder.lastModified();
                if (hasFinal) {
                    lm = Math.max(lm, finalReport.lastModified());
                }
                if (hasLive) {
                    lm = Math.max(lm, liveReport.lastModified());
                }
                long sortKey = reportFolderSortKey(folder, folderTs, lm);
                File jsonFile = new File(folder, "json" + File.separator + "merv-report.json");
                ReportSummary sum = loadReportSummaryFromJson(jsonFile, hasFinal, hasLive);
                entries.add(new ReportIndexEntry(folder.getName(), lm, sortKey, hasLive, hasFinal, sum));
            }
        }
        entries.sort((a, b) -> Long.compare(b.sortKey, a.sortKey));

        SimpleDateFormat footFmt = new SimpleDateFormat("dd-MMM-yyyy (EEE) hh:mma", Locale.ENGLISH);
        String grad = MervReportBranding.GRADIENT_CSS;
        String logo = MervReportBranding.LOGO_URL;
        String deleteApiUrl = "http://127.0.0.1:" + readReportsDeleteApiPort() + "/api/reports/delete";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<script>window.MERV_REPORTS_DELETE_API=").append(jsDoubleQuoted(deleteApiUrl)).append(";</script>\n");
        html.append("<script>window.MERV_REPORT_FOLDERS=");
        if (entries.isEmpty()) {
            html.append("[]");
        } else {
            html.append("[");
            for (int fi = 0; fi < entries.size(); fi++) {
                if (fi > 0) {
                    html.append(',');
                }
                html.append(jsDoubleQuoted(urlPathEncode(entries.get(fi).folderName)));
            }
            html.append("]");
        }
        html.append(";</script>\n");
        html.append("<title>Merv — Test Suites</title>\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n");
        html.append("<link href=\"https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n");
        html.append("<style>\n");
        html.append(":root{--merv-red:#e2433a;--merv-grad:").append(grad).append(";--sidebar:#f2f3f5;--border:#e6e8ec;--text:#1a1a1a;--muted:#5c5f66;}\n");
        html.append("*{box-sizing:border-box;}html{font-family:'Roboto',system-ui,-apple-system,sans-serif;}body{margin:0;font-family:'Roboto',system-ui,-apple-system,sans-serif;background:#fff;color:var(--text);min-height:100vh;}button,input,select,textarea{font-family:inherit;}\n");
        html.append("h1,h2,h3,h4,h5,h6{letter-spacing:0.5px;}\n");
        html.append(".dash{display:flex;min-height:100vh;}\n");
        html.append(".sidebar{width:260px;background:var(--sidebar);border-right:1px solid var(--border);padding:20px 0 0;flex-shrink:0;position:sticky;top:0;align-self:flex-start;min-height:100vh;display:flex;flex-direction:column;}\n");
        html.append(".sidebar-brand{text-align:center;padding:0 16px 12px;border-bottom:1px solid var(--border);margin-bottom:0;}\n");
        html.append(".sidebar-brand img{max-width:140px;height:auto;display:block;margin:0 auto;}\n");
        html.append(".sidebar-local-label{text-align:center;margin:0;padding:12px 16px 14px;border-bottom:1px solid var(--border);font-size:13px;font-weight:700;color:var(--merv-red);letter-spacing:.04em;}\n");
        html.append(".nav{margin:8px 0;padding:0 12px;flex:1;min-height:0;}\n");
        html.append(".sidebar-footer{margin-top:auto;padding:18px 14px 22px;border-top:1px solid var(--border);text-align:center;}\n");
        html.append(".sidebar-footer a.sidebar-merv-online{display:block;font-size:13px;font-weight:600;color:#0d6efd;text-decoration:none;margin-bottom:14px;}\n");
        html.append(".sidebar-footer a.sidebar-merv-online:hover{text-decoration:underline;}\n");
        html.append(".sidebar-powered{margin:0 0 10px;font-size:11px;font-weight:600;color:var(--muted);letter-spacing:.03em;text-transform:uppercase;}\n");
        html.append(".sidebar-footer a.sidebar-techelliptica{display:inline-block;line-height:0;}\n");
        html.append(".sidebar-footer a.sidebar-techelliptica img{max-width:150px;height:auto;display:block;margin:0 auto;}\n");
        html.append(".nav a{letter-spacing:0.5px;display:block;padding:12px 14px;border-radius:8px;color:#4a4a4a;text-decoration:none;font-size:14px;font-weight:500;margin:2px 0;}\n");
        html.append(".nav a:hover{background:rgba(0,0,0,.04);}\n");
        html.append(".nav a.active{background:#fdeaea;color:var(--merv-red);font-weight:600;border-left:3px solid var(--merv-red);padding-left:11px;}\n");
        html.append(".nav-section{margin:14px 0 10px;padding-top:12px;border-top:1px solid var(--border);}\n");
        html.append(".nav-section-title{font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#868e96;margin:0 0 8px;padding:0 4px;}\n");
        html.append(".nav-sub-link{font-size:13px!important;font-weight:500!important;padding:8px 12px 8px 14px!important;margin:2px 0!important;border-left:3px solid transparent!important;}\n");
        html.append(".nav-sub-link:hover{background:rgba(0,0,0,.03)!important;}\n");
        html.append(".nav-sub-link.active{background:#fdeaea!important;color:var(--merv-red)!important;font-weight:600!important;border-left-color:var(--merv-red)!important;padding-left:11px!important;}\n");
        html.append("[id^=\"kpi-\"],[id^=\"cons-subpanel\"]{scroll-margin-top:20px;}\n");
        html.append(".main{flex:1;display:flex;flex-direction:column;min-width:0;background:#fafbfc;}\n");
        html.append(".topbar{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:16px;padding:18px 28px;background:#fff;border-bottom:1px solid var(--border);}\n");
        html.append(".search-wrap{flex:1;min-width:200px;max-width:420px;}\n");
        html.append("#suite-search{width:100%;padding:11px 16px;border:1px solid #ddd;border-radius:10px;font-size:14px;}\n");
        html.append("#suite-search:focus{outline:2px solid rgba(226,67,54,.2);border-color:var(--merv-red);}\n");
        html.append(".content{padding:24px 28px 48px;}\n");
        html.append(".content-view{display:none;}\n");
        html.append(".content-view.active{display:block;}\n");
        html.append(".content h2{margin:0 0 18px 0;font-size:1.1rem;font-weight:700;color:#333;}\n");
        html.append(".suite-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(360px,1fr));gap:20px;}\n");
        html.append(".suite-card{background:#fff;border:1px solid #e8eaed;border-radius:12px;padding:20px 20px 16px;box-shadow:0 1px 4px rgba(0,0,0,.05);display:flex;flex-direction:column;}\n");
        html.append(".suite-card.is-latest{box-shadow:0 4px 20px rgba(226,67,54,.12);border-color:rgba(226,67,54,.25);}\n");
        html.append(".suite-top{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;}\n");
        html.append(".suite-title-block{flex:1;min-width:0;}\n");
        html.append(".suite-top .suite-name{margin:0;font-size:1.05rem;font-weight:700;line-height:1.3;word-break:break-word;}\n");
        html.append(".suite-top .suite-folder{margin:6px 0 0;font-size:12px;color:var(--muted);font-weight:500;line-height:1.35;word-break:break-word;}\n");
        html.append(".status-badge{font-size:10px;font-weight:700;letter-spacing:.04em;padding:5px 10px;border-radius:4px;color:#fff;text-transform:uppercase;white-space:nowrap;}\n");
        html.append(".status-badge.done{background:#28a745;}\n");
        html.append(".status-badge.run{background:#ffc107;color:#212529;}\n");
        html.append("@keyframes idx-blink-inprogress{0%,100%{opacity:1;}50%{opacity:.32;}}\n");
        html.append(".status-badge.run{animation:idx-blink-inprogress 1.1s ease-in-out infinite;}\n");
        html.append(".status-badge.abort{background:#6c757d;color:#fff;animation:none !important;}\n");
        html.append(".suite-counts{margin:10px 0 0;font-size:13px;color:var(--muted);}\n");
        html.append(".suite-counts strong{color:#333;font-weight:600;}\n");
        html.append(".suite-meta{margin:10px 0 0;color:var(--muted);}\n");
        html.append(".suite-meta-row{display:flex;justify-content:space-between;align-items:flex-start;gap:14px;}\n");
        html.append(".suite-meta-dl{display:grid;grid-template-columns:auto 1fr;gap:3px 12px;font-size:12px;margin:0;flex:1;min-width:0;}\n");
        html.append(".suite-meta-dl dt{margin:0;font-weight:600;color:#6b6b6b;}\n");
        html.append(".suite-meta-dl dd{margin:0;}\n");
        html.append(".suite-meta-donut{flex-shrink:0;padding-top:2px;}\n");
        html.append(".suite-meta .donut{position:relative;width:76px;height:76px;border-radius:50%;flex-shrink:0;}\n");
        html.append(".suite-meta .donut::after{content:'';position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:42px;height:42px;background:#fff;border-radius:50%;box-shadow:inset 0 0 0 1px rgba(0,0,0,.04);}\n");
        html.append(".suite-tags-block{margin-top:10px;padding-top:10px;border-top:1px solid #f0f1f3;}\n");
        html.append(".suite-tags-label{font-size:10px;font-weight:700;color:#6b6b6b;text-transform:uppercase;letter-spacing:.05em;margin-bottom:6px;display:block;}\n");
        html.append(".tag-row{display:flex;flex-wrap:wrap;gap:6px;align-content:flex-start;min-height:22px;}\n");
        html.append(".tag-pill{background:#eef1f4;color:#495057;padding:3px 10px;border-radius:20px;font-size:11px;}\n");
        html.append(".suite-foot{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px;margin-top:auto;padding-top:14px;border-top:1px solid #f0f1f3;}\n");
        html.append(".suite-when{font-size:12px;color:var(--muted);line-height:1.4;}\n");
        html.append(".suite-when .rel{display:block;font-size:11px;margin-top:2px;opacity:.85;}\n");
        html.append(".suite-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;}\n");
        html.append(".btn-view{display:inline-flex;align-items:center;padding:8px 14px;border:2px solid #0d6efd;border-radius:8px;color:#0d6efd;font-size:13px;font-weight:600;text-decoration:none;background:#fff;}\n");
        html.append(".btn-view:hover{background:#f0f7ff;}\n");
        html.append(".icon-btn{width:36px;height:36px;border:1px solid #dee2e6;border-radius:8px;background:#fff;cursor:pointer;font-size:16px;line-height:1;display:inline-flex;align-items:center;justify-content:center;color:#495057;}\n");
        html.append(".icon-btn:hover{background:#f8f9fa;border-color:#adb5bd;}\n");
        html.append(".btn-delete{padding:8px 12px;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;background:#fff;color:#c82333;border:1px solid #dc3545;}\n");
        html.append(".btn-delete:hover{background:#fff5f5;}\n");
        html.append(".btn-delete:disabled{opacity:.5;cursor:not-allowed;}\n");
        html.append(".empty{max-width:520px;margin:48px auto;text-align:center;padding:40px;color:var(--muted);}\n");
        html.append(".chart-panel{background:#fff;border:1px solid #e8eaed;border-radius:14px;padding:20px 22px;margin-bottom:28px;box-shadow:0 2px 12px rgba(0,0,0,.06);}\n");
        html.append(".chart-head{display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:10px;margin-bottom:12px;width:100%;}\n");
        html.append(".chart-head h2{margin:0;font-size:1.08rem;font-weight:700;color:#1a1a1a;letter-spacing:-.02em;}\n");
        html.append(".chart-live{font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#28a745;}\n");
        html.append(".chart-canvas-wrap{height:280px;position:relative;max-width:100%;background:#fff;border-radius:8px;}\n");
        html.append(".chart-note{margin:14px 0 0;font-size:12px;color:#5c5f66;line-height:1.5;}\n");
        html.append(".chart-head-inner{display:flex;flex-direction:column;gap:10px;flex:1;min-width:0;}\n");
        html.append(".chart-head-row{display:flex;flex-wrap:wrap;align-items:flex-start;justify-content:space-between;gap:12px;width:100%;}\n");
        html.append(".chart-title-wrap{flex:1;min-width:180px;}\n");
        html.append(".chart-controls{display:flex;flex-wrap:wrap;align-items:center;gap:14px 18px;}\n");
        html.append(".chart-range-field{display:flex;flex-direction:column;gap:8px;min-width:0;flex:1;}\n");
        html.append(".chart-range-label{font-size:10px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:#868e96;line-height:1;}\n");
        html.append(".chart-range-tags{display:flex;flex-wrap:wrap;gap:8px;align-items:center;}\n");
        html.append(".chart-range-btn{height:25px;margin:0;padding:4px 16px;font-size:12px;font-weight:600;font-family:inherit;line-height:1.2;color:#495057;background:#fff;border:1px solid #d8dce3;border-radius:999px;cursor:pointer;transition:background .15s,border-color .15s,color .15s,box-shadow .15s;box-shadow:0 1px 2px rgba(15,23,42,.04);white-space:nowrap;}\n");
        html.append(".chart-range-btn:hover{background:#f8f9fa;border-color:#bdc4ce;color:#212529;}\n");
        html.append(".chart-range-btn:focus{outline:none;box-shadow:0 0 0 3px rgba(194,0,0,.22);}\n");
        html.append(".chart-range-btn.active{background:linear-gradient(180deg,#fff0f0,#fde8e8);color:#9b0000;border-color:#c20000;box-shadow:0 1px 4px rgba(194,0,0,.18),inset 0 1px 0 rgba(255,255,255,.6);}\n");
        html.append(".chart-range-btn.active:hover{filter:brightness(0.98);}\n");
        html.append(".chart-controls .chart-live{flex-shrink:0;padding:8px 14px;border-radius:999px;font-size:10px;letter-spacing:.07em;background:linear-gradient(180deg,#f0fdf4,#e8f5e9);color:#1b5e20;border:1px solid rgba(40,167,69,.35);box-shadow:0 1px 2px rgba(27,94,32,.06);}\n");
        html.append(".chart-custom-range{display:none;flex-wrap:wrap;align-items:center;gap:12px;padding:14px 16px;background:linear-gradient(165deg,#fafbfc 0%,#f4f5f7 100%);border-radius:12px;border:1px solid #e4e7ec;box-shadow:inset 0 1px 0 rgba(255,255,255,.85);}\n");
        html.append(".chart-custom-range.visible{display:flex;}\n");
        html.append(".chart-custom-range label{font-size:12px;color:#495057;display:inline-flex;align-items:center;gap:8px;font-weight:600;}\n");
        html.append(".chart-custom-range input[type=datetime-local]{padding:9px 12px;border:1px solid #ced4da;border-radius:10px;font-size:13px;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.03);transition:border-color .15s,box-shadow .15s;}\n");
        html.append(".chart-custom-range input[type=datetime-local]:focus{outline:none;border-color:#c20000;box-shadow:0 0 0 3px rgba(194,0,0,.12);}\n");
        html.append(".chart-apply-btn{padding:9px 18px;border-radius:10px;border:1px solid #b80000;background:linear-gradient(180deg,#e90101,#c20000);color:#fff;font-size:12px;font-weight:700;letter-spacing:.03em;cursor:pointer;box-shadow:0 1px 3px rgba(194,0,0,.35);transition:filter .15s,transform .1s;}\n");
        html.append(".chart-apply-btn:hover{filter:brightness(1.06);}\n");
        html.append(".chart-apply-btn:active{transform:translateY(1px);}\n");
        html.append(".consolidated-panel{background:#fff;border:1px solid #e8eaed;border-radius:14px;padding:16px 18px;margin:0 0 28px;box-shadow:0 2px 12px rgba(0,0,0,.05);}\n");
        html.append(".consolidated-panel h2{margin:0 0 12px;font-size:1.02rem;color:#1f2937;}\n");
        html.append(".consolidated-wrap{overflow:auto;border:1px solid #edf0f3;border-radius:10px;}\n");
        html.append(".consolidated-table{width:100%;border-collapse:collapse;min-width:980px;background:#fff;}\n");
        html.append(".consolidated-table th,.consolidated-table td{padding:10px 12px;border-bottom:1px solid #f1f3f5;text-align:left;font-size:12px;color:#374151;vertical-align:middle;}\n");
        html.append(".consolidated-table th{background:#f8fafc;font-size:11px;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;font-weight:700;position:sticky;top:0;z-index:1;}\n");
        html.append(".consolidated-table tbody tr:hover{background:#fbfdff;}\n");
        html.append(".cons-name{font-weight:600;color:#1f2937;word-break:break-word;}\n");
        html.append(".cons-suite-row{background:#f8fafc;}\n");
        html.append(".cons-suite-cell{display:flex;align-items:center;gap:8px;}\n");
        html.append(".cons-toggle{border:0;background:transparent;color:#374151;cursor:pointer;font-size:13px;line-height:1;padding:2px 4px;border-radius:4px;}\n");
        html.append(".cons-toggle:hover{background:#eef2f6;}\n");
        html.append(".cons-toggle .arr{display:inline-block;transition:transform .18s ease;}\n");
        html.append(".cons-toggle.expanded .arr{transform:rotate(90deg);}\n");
        html.append(".cons-testcase-row td{background:#fff;}\n");
        html.append(".cons-testcase-name{padding-left:8px;}\n");
        html.append(".cons-suite-detail-row td{background:#fbfdff;}\n");
        html.append(".cons-suite-detail-name{padding-left:40px;font-weight:500;}\n");
        html.append(".cons-status{display:inline-flex;align-items:center;height:22px;padding:0 10px;border-radius:999px;font-size:11px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;border:1px solid #d7dde4;background:#eef2f6;color:#364152;}\n");
        html.append(".cons-status.passed{background:#e8f5e9;color:#1b5e20;border-color:#c8e6c9;}\n");
        html.append(".cons-status.failed{background:#ffebee;color:#b71c1c;border-color:#ffcdd2;}\n");
        html.append(".cons-status.skipped{background:#fff8e1;color:#e65100;border-color:#ffe0b2;}\n");
        html.append(".cons-status.in_progress{background:#e3f2fd;color:#0d47a1;border-color:#bbdefb;}\n");
        html.append(".cons-num{font-variant-numeric:tabular-nums;font-weight:700;}\n");
        html.append(".cons-link{text-indent:20px;color:#0d47a1;font-weight:600;text-decoration:underline;text-underline-offset:2px;}\n");
        html.append(".cons-link:hover{color:#08306f;}\n");
        html.append(".cons-tags{padding-left:40px;display:flex;flex-wrap:wrap;gap:6px;margin-top:6px;}\n");
        html.append(".cons-tag{display:inline-flex;align-items:center;height:18px;padding:0 8px;border-radius:999px;font-size:11px;font-weight:600;border:1px solid #d4def2;background:#eef4ff;color:#163b7a;cursor:pointer;}\n");
        html.append(".cons-tag:hover{background:#e1ecff;border-color:#b7c8ea;}\n");
        html.append(".cons-subtabs{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 14px;padding:4px 0;border-bottom:1px solid #edf0f3;}\n");
        html.append(".cons-subtab{padding:8px 14px;border-radius:10px;border:1px solid #d6dbe2;background:#f8fafc;font-size:13px;font-weight:600;color:#495057;cursor:pointer;line-height:1.2;}\n");
        html.append(".cons-subtab:hover{background:#eef2f7;border-color:#c5cdd8;}\n");
        html.append(".cons-subtab.active{background:#fff;border-color:#c20000;color:#1a1a1a;box-shadow:0 1px 4px rgba(0,0,0,.06);}\n");
        html.append(".cons-subpanel{display:none;margin-top:4px;}\n");
        html.append(".cons-subpanel.active{display:block;}\n");
        html.append(".cons-fail-wrap{display:flex;flex-direction:column;gap:10px;}\n");
        html.append(".cons-fail-group{border:1px solid #edf0f3;border-radius:12px;background:#fff;box-shadow:0 2px 10px rgba(0,0,0,.04);}\n");
        html.append(".cons-fail-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:12px 14px;cursor:pointer;user-select:none;}\n");
        html.append(".cons-fail-reason{font-size:13px;font-weight:800;color:#111827;white-space:pre-wrap;word-break:break-word;}\n");
        html.append(".cons-fail-count{flex:0 0 auto;font-size:12px;font-weight:800;color:#c20000;background:rgba(194,0,0,.09);border:1px solid rgba(194,0,0,.22);padding:4px 8px;border-radius:999px;}\n");
        html.append(".cons-fail-body{display:none;padding:0 14px 14px;}\n");
        html.append(".cons-fail-group.open .cons-fail-body{display:block;}\n");
        html.append(".cons-fail-cases{margin-top:10px;display:flex;flex-direction:column;gap:6px;}\n");
        html.append(".cons-fail-case{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 10px;border:1px solid #f1f3f5;border-radius:10px;background:#fcfcfd;}\n");
        html.append(".cons-fail-case a{color:#0d47a1;text-decoration:none;font-weight:700;font-size:12px;}\n");
        html.append(".cons-fail-case a:hover{text-decoration:underline;}\n");
        html.append(".cons-fail-hint{color:#6b7280;font-size:12px;margin:0 0 8px;}\n");
        html.append(".cons-fail-empty{color:#9ca3af;font-size:13px;margin:0;padding:8px 0;}\n");
        html.append(".consolidated-tag-root{display:flex;flex-direction:column;gap:20px;}\n");
        html.append(".cons-tag-section{margin:0;padding:0;border:1px solid #edf0f3;border-radius:12px;background:#fafbfc;overflow:hidden;}\n");
        html.append(".cons-tag-heading{margin:0;padding:12px 14px;font-size:14px;font-weight:700;color:#1f2937;background:#f1f5f9;border-bottom:1px solid #e8ecf1;}\n");
        html.append(".cons-tag-count{font-weight:600;color:#6b7280;font-size:13px;}\n");
        html.append(".cons-tag-hint{margin:0 0 12px;font-size:13px;color:#6b7280;}\n");
        html.append(".cons-tag-empty{margin:8px 0;color:#9ca3af;font-size:13px;}\n");
        html.append(".consolidated-search-wrap{margin:0 0 10px;max-width:380px;}\n");
        html.append("#consolidated-search{width:100%;padding:10px 12px;border:1px solid #d6dbe2;border-radius:10px;font-size:13px;color:#374151;background:#fff;}\n");
        html.append("#consolidated-search:focus{outline:2px solid rgba(194,0,0,.18);border-color:#c20000;}\n");
        html.append(".kpi-panel{background:#fff;border:1px solid #e8eaed;border-radius:14px;padding:22px 24px;margin:0 0 28px;box-shadow:0 2px 12px rgba(0,0,0,.06);}\n");
        html.append(".kpi-panel h2{margin:0 0 8px;font-size:1.12rem;font-weight:700;color:#1a1a1a;}\n");
        html.append(".kpi-build-scope-bar{display:flex;flex-wrap:wrap;align-items:center;gap:10px 14px;margin:0 0 16px;padding:12px 14px;background:#f8f9fb;border:1px solid #e8eaed;border-radius:10px;}\n");
        html.append(".kpi-build-scope-bar .kpi-custom-settings{display:flex;flex-wrap:wrap;align-items:center;gap:10px 10px;margin:0;padding:0;background:transparent;border:none;border-radius:0;box-shadow:none;}\n");
        html.append(".kpi-build-scope-bar .kpi-custom-settings-title{display:none;}\n");
        html.append(".kpi-build-scope-bar .kpi-tag-filter-label{font-size:13px;font-weight:600;color:#374151;}\n");
        html.append(".kpi-build-scope-bar #kpi-tag-filter{min-width:12rem;border-radius:8px;}\n");
        // (intentionally no kpi-scope-spacer; keep build scope + custom settings adjacent)
        html.append(".kpi-build-scope-label{font-size:13px;font-weight:600;color:#374151;margin:0;}\n");
        html.append(".kpi-build-scope-select{padding:7px 12px;border-radius:8px;border:1px solid #ced4da;font-size:13px;background:#fff;color:#1a1a1a;min-width:11rem;}\n");
        html.append(".kpi-build-custom-wrap{display:none;align-items:center;gap:8px;flex-wrap:wrap;}\n");
        html.append(".kpi-build-custom-wrap.visible{display:inline-flex;}\n");
        html.append(".kpi-build-custom-label{font-size:13px;color:#495057;}\n");
        html.append(".kpi-build-custom-input{width:4.5rem;padding:6px 8px;border:1px solid #ced4da;border-radius:8px;font-size:13px;}\n");
        html.append(".kpi-build-custom-suffix{font-size:13px;color:#6b7280;}\n");
        html.append(".kpi-build-custom-apply{padding:6px 12px;border-radius:8px;border:1px solid #ced4da;background:#fff;font-size:12px;font-weight:600;cursor:pointer;}\n");
        html.append(".kpi-build-custom-apply:hover{background:#f1f3f5;}\n");
        html.append(".kpi-build-scope-status{font-size:12px;color:#6b7280;margin-left:auto;}\n");
        html.append("@media(max-width:640px){.kpi-build-scope-status{width:100%;margin-left:0;margin-top:4px;}}\n");
        html.append(".kpi-custom-settings{display:flex;flex-wrap:wrap;align-items:center;gap:10px 12px;margin:10px 0 14px;padding:12px 14px;background:#fff;border:1px solid #e8eaed;border-radius:12px;box-shadow:0 1px 10px rgba(0,0,0,.04);}\n");
        html.append(".kpi-custom-settings-title{font-size:12px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:#374151;}\n");
        html.append(".kpi-tag-filter-label{font-size:13px;font-weight:600;color:#374151;}\n");
        html.append("#kpi-tag-filter{padding:7px 12px;border-radius:10px;border:1px solid #ced4da;font-size:13px;background:#fff;color:#1a1a1a;min-width:14rem;max-width:100%;}\n");
        html.append("#kpi-tag-filter:focus{outline:2px solid rgba(194,0,0,.18);border-color:#c20000;}\n");
        html.append(".kpi-sub{margin:0 0 20px;font-size:13px;color:var(--muted);line-height:1.45;}\n");
        html.append(".kpi-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(158px,1fr));gap:14px;margin-bottom:22px;}\n");
        html.append(".kpi-card{border-radius:12px;padding:16px 18px;border:1px solid #e8eaed;background:#fafbfc;display:flex;flex-direction:column;gap:6px;min-height:86px;}\n");
        html.append(".kpi-card-total{border-color:rgba(13,110,253,.28);background:linear-gradient(165deg,#fff,#f3f8ff);}\n");
        html.append(".kpi-card-pass{border-color:rgba(40,167,69,.32);background:linear-gradient(165deg,#fff,#f4fdf7);}\n");
        html.append(".kpi-card-fail{border-color:rgba(220,53,69,.3);background:linear-gradient(165deg,#fff,#fff8f8);}\n");
        html.append(".kpi-card-skip{border-color:rgba(255,152,0,.35);background:linear-gradient(165deg,#fff,#fffbf5);}\n");
        html.append(".kpi-card-pct{border-color:#e4e7ec;background:#fff;}\n");
        html.append(".kpi-card-dur{border-color:rgba(23,162,184,.32);background:linear-gradient(165deg,#fff,#f3fcfd);}\n");
        html.append(".kpi-label{font-size:11px;font-weight:700;letter-spacing:.07em;text-transform:uppercase;color:#6b7280;}\n");
        html.append(".kpi-value{font-size:1.72rem;font-weight:800;font-variant-numeric:tabular-nums;color:#1a1a1a;line-height:1.15;}\n");
        html.append(".kpi-value-sm{font-size:1.22rem;font-weight:700;}\n");
        html.append(".kpi-charts-row{display:flex;flex-wrap:wrap;gap:24px;margin-top:14px;align-items:stretch;justify-content:center;width:100%;}\n");
        html.append(".kpi-chart-cell{flex:1;min-width:min(100%,320px);max-width:calc(50% - 12px);height:288px;position:relative;display:flex;flex-direction:column;}\n");
        html.append(".kpi-chart-cell canvas{flex:1;min-height:240px;width:100%!important;}\n");
        html.append("@media(max-width:900px){.kpi-chart-cell{max-width:100%;}}\n");
        html.append(".kpi-trend-section{margin-top:26px;padding-top:22px;border-top:1px solid #e8eaed;}\n");
        html.append(".kpi-trend-heading{margin:0 0 6px;font-size:1.06rem;font-weight:700;color:#1a1a1a;}\n");
        html.append(".kpi-trend-grid{display:grid;grid-template-columns:1fr;gap:22px;width:100%;margin-top:12px;}\n");
        html.append(".kpi-trend-cell{min-height:260px;height:280px;width:100%;max-width:100%;position:relative;}\n");
        html.append(".kpi-trend-cell canvas{max-height:100%!important;width:100%!important;}\n");
        html.append(".kpi-perf-section{margin-top:24px;padding-top:20px;border-top:1px solid #e8eaed;}\n");
        html.append(".kpi-perf-heading{margin:0 0 6px;font-size:1.06rem;font-weight:700;color:#1a1a1a;}\n");
        html.append(".kpi-perf-sub{margin:0 0 14px;font-size:13px;color:#6b7280;line-height:1.45;}\n");
        html.append(".kpi-perf-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;align-items:start;}\n");
        html.append(".kpi-perf-span2{grid-column:1/-1;}\n");
        html.append(".kpi-perf-slow{margin:6px 0 0;padding-left:1.2rem;}\n");
        html.append(".kpi-perf-slow li{margin:5px 0;font-size:13px;line-height:1.4;}\n");
        html.append(".kpi-perf-slow-name{font-weight:600;color:#1a1a1a;}\n");
        html.append(".kpi-perf-slow-dur{color:#0d6efd;font-variant-numeric:tabular-nums;margin-left:6px;}\n");
        html.append(".kpi-perf-slow-empty{list-style:none;margin-left:-1.2rem;color:#9ca3af;font-size:13px;}\n");
        html.append(".kpi-perf-slow-head{display:flex;align-items:flex-start;gap:10px;margin-bottom:6px;}\n");
        html.append(".kpi-perf-slow-toggle{background:transparent;border:none;padding:4px 6px 4px 0;margin:0;cursor:pointer;color:#495057;border-radius:6px;line-height:1;display:inline-flex;align-items:center;flex-shrink:0;}\n");
        html.append(".kpi-perf-slow-toggle:hover{background:rgba(0,0,0,.05);color:#1a1a1a;}\n");
        html.append(".kpi-perf-slow-toggle:focus-visible{outline:2px solid rgba(194,0,0,.28);outline-offset:2px;}\n");
        html.append(".kpi-perf-slow-caret{display:inline-block;font-size:10px;transition:transform .2s ease;}\n");
        html.append(".kpi-perf-slow-toggle[aria-expanded=\"false\"] .kpi-perf-slow-caret{transform:rotate(-90deg);}\n");
        html.append(".kpi-perf-slow-wrap.kpi-perf-slow-collapsed{display:none;}\n");
        html.append(".kpi-perf-charts{display:flex;flex-direction:column;gap:22px;width:100%;margin-top:20px;}\n");
        html.append(".kpi-perf-chart-cell{min-height:260px;height:280px;width:100%;position:relative;}\n");
        html.append(".kpi-perf-chart-cell canvas{max-height:100%!important;width:100%!important;}\n");
        html.append(".kpi-flaky-section{margin-top:26px;padding-top:22px;border-top:1px solid #e8eaed;}\n");
        html.append(".kpi-flaky-heading{margin:0 0 6px;font-size:1.06rem;font-weight:700;color:#1a1a1a;}\n");
        html.append(".kpi-flaky-sub{margin:0 0 12px;font-size:13px;color:#6b7280;line-height:1.45;}\n");
        html.append(".kpi-flaky-summary{margin:0 0 12px;font-size:13px;color:#374151;display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 8px;}\n");
        html.append(".kpi-flaky-summary-num{font-weight:800;font-variant-numeric:tabular-nums;color:#b02a37;}\n");
        html.append(".kpi-flaky-summary-label{color:#495057;}\n");
        html.append(".kpi-flaky-summary-sep{color:#9ca3af;}\n");
        html.append(".kpi-flaky-wrap{overflow-x:auto;border:1px solid #e8eaed;border-radius:10px;background:#fafbfc;}\n");
        html.append(".kpi-flaky-table{width:100%;border-collapse:collapse;font-size:13px;}\n");
        html.append(".kpi-flaky-table th{text-align:left;padding:10px 12px;background:#f1f3f5;font-weight:700;color:#374151;border-bottom:1px solid #e8eaed;}\n");
        html.append(".kpi-flaky-table td{padding:9px 12px;border-bottom:1px solid #eef0f3;vertical-align:top;}\n");
        html.append(".kpi-flaky-table tr:last-child td{border-bottom:none;}\n");
        html.append(".kpi-flaky-table .kpi-flaky-num{text-align:right;font-variant-numeric:tabular-nums;color:#1a1a1a;}\n");
        html.append(".kpi-flaky-name{font-weight:600;color:#1a1a1a;}\n");
        html.append(".kpi-flaky-empty{color:#9ca3af;text-align:center;padding:14px!important;}\n");
        html.append("</style>\n</head>\n<body>\n<div class=\"dash\">\n");
        html.append("<aside class=\"sidebar\"><div class=\"sidebar-brand\"><img src=\"").append(logo).append("\" alt=\"Merv\"></div>\n");
        html.append("<p class=\"sidebar-local-label\">Merv-Local</p>\n");
        html.append("<nav class=\"nav\"><a class=\"nav-main-link active\" href=\"#\" data-view=\"test-suites\">Test Suites</a>");
        html.append("<div class=\"nav-section\"><div class=\"nav-section-title\">Consolidated Report</div>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"consolidated\" data-cons-tab=\"testcase\" data-scroll-target=\"cons-subpanel-testcase\">1 — TestCase Summary</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"consolidated\" data-cons-tab=\"tags\" data-scroll-target=\"cons-subpanel-tags\">2 — Tag/Group Based Summary</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"consolidated\" data-cons-tab=\"failures\" data-scroll-target=\"cons-subpanel-failures\">3 — Failure Summary</a></div>");
        html.append("<div class=\"nav-section\"><div class=\"nav-section-title\">KPIs</div>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-sec-execution-summary\">Test execution summary</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-sec-performance\">Execution Time &amp; Performance</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-sec-slow\">Slow test cases</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-sec-flaky\">Flaky Test Detection</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-sec-trend\">Trend Analysis</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-chart-pass-pct\">Pass % over time</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-chart-failures\">Failures per build</a>");
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"kpis\" data-scroll-target=\"kpi-chart-duration\">Execution time trend</a></div></nav>\n");
        html.append("<footer class=\"sidebar-footer\">\n");
        html.append("<a class=\"sidebar-merv-online\" href=\"https://merv.online\" target=\"_blank\" rel=\"noopener noreferrer\">Merv-Server Report</a>\n");
        html.append("<p class=\"sidebar-powered\">Powered By TechElliptica</p>\n");
        html.append("<a class=\"sidebar-techelliptica\" href=\"https://www.techelliptica.com\" target=\"_blank\" rel=\"noopener noreferrer\" title=\"TechElliptica\">");
        html.append("<img src=\"https://techelliptica.com/images/logo.png\" alt=\"TechElliptica\"></a>\n");
        html.append("</footer></aside>\n<div class=\"main\">\n");
        html.append("<header class=\"topbar\"><div class=\"search-wrap\"><input type=\"search\" id=\"suite-search\" placeholder=\"Search test suites…\" autocomplete=\"off\"></div></header>\n<div class=\"content\">\n");

        if (entries.isEmpty()) {
            html.append("<div id=\"view-test-suites\" class=\"content-view active\"><div class=\"empty\"><p><strong>No reports yet.</strong></p><p>Run your Cucumber suite with Merv local mode to generate a dated report folder here.</p></div></div>\n");
            html.append("<div id=\"view-consolidated\" class=\"content-view\"><section class=\"consolidated-panel\" aria-label=\"Consolidated\"><h2>Consolidated Report</h2><div class=\"cons-subtabs\" role=\"tablist\" aria-label=\"Consolidated report views\"><button type=\"button\" class=\"cons-subtab active\" id=\"cons-tab-testcase\" data-cons-sub=\"testcase\" role=\"tab\" aria-selected=\"true\">TestCase View</button><button type=\"button\" class=\"cons-subtab\" id=\"cons-tab-tags\" data-cons-sub=\"tags\" role=\"tab\" aria-selected=\"false\">Tag based Report</button><button type=\"button\" class=\"cons-subtab\" id=\"cons-tab-failures\" data-cons-sub=\"failures\" role=\"tab\" aria-selected=\"false\">Failure Summary</button></div><div id=\"cons-subpanel-testcase\" class=\"cons-subpanel active\" role=\"tabpanel\" aria-labelledby=\"cons-tab-testcase\"><p class=\"kpi-sub\">No report runs to consolidate.</p></div><div id=\"cons-subpanel-tags\" class=\"cons-subpanel\" role=\"tabpanel\" aria-labelledby=\"cons-tab-tags\"><p class=\"kpi-sub cons-tag-hint\">Test cases grouped by Cucumber tag. A test may appear under more than one tag.</p><div id=\"consolidated-tag-root\" class=\"consolidated-tag-root\"><p class=\"cons-tag-empty\">No data.</p></div></div><div id=\"cons-subpanel-failures\" class=\"cons-subpanel\" role=\"tabpanel\" aria-labelledby=\"cons-tab-failures\"><p class=\"cons-fail-hint\">Failure reasons from the <strong>latest execution per testcase</strong>. Fixed tests disappear automatically.</p><div id=\"cons-fail-root\" class=\"cons-fail-wrap\"><p class=\"cons-fail-empty\">No failure summary yet.</p></div></div></section></div>\n");
            html.append("<div id=\"view-kpis\" class=\"content-view\"><section class=\"kpi-panel\" aria-label=\"KPI reports\"><h2 id=\"kpi-sec-execution-summary\">Test execution summary</h2><div class=\"kpi-build-scope-bar\" id=\"kpi-build-scope-bar\" role=\"region\" aria-label=\"KPI build scope\"><span class=\"kpi-build-scope-label\" id=\"kpi-build-scope-label\">Builds in KPI charts</span><select id=\"kpi-build-scope\" class=\"kpi-build-scope-select\" aria-labelledby=\"kpi-build-scope-label\"><option value=\"20\" selected>Last 20 builds</option><option value=\"30\">Last 30 builds</option><option value=\"50\">Last 50 builds</option><option value=\"all\">All builds</option><option value=\"custom\">Custom…</option></select><span id=\"kpi-build-custom-wrap\" class=\"kpi-build-custom-wrap\"><label for=\"kpi-build-custom\" class=\"kpi-build-custom-label\">Last</label><input type=\"number\" id=\"kpi-build-custom\" class=\"kpi-build-custom-input\" min=\"1\" max=\"999\" value=\"20\" /><span class=\"kpi-build-custom-suffix\">builds</span><button type=\"button\" id=\"kpi-build-custom-apply\" class=\"kpi-build-custom-apply\">Apply</button></span><div class=\"kpi-custom-settings\" role=\"region\" aria-label=\"Custom Setting\"><span class=\"kpi-custom-settings-title\">Custom Setting</span><label class=\"kpi-tag-filter-label\" for=\"kpi-tag-filter\">Tag</label><select id=\"kpi-tag-filter\" aria-label=\"Filter KPI by tag\"><option value=\"\">All tags</option></select></div><span id=\"kpi-build-scope-status\" class=\"kpi-build-scope-status\"></span></div><p class=\"kpi-sub\">Charts and KPI metrics will populate after local runs finish writing JSON reports.</p><div class=\"kpi-grid\"><div class=\"kpi-card kpi-card-total\"><span class=\"kpi-label\">Total test cases</span><span class=\"kpi-value\" id=\"kpi-total-tc\">0</span></div><div class=\"kpi-card kpi-card-pass\"><span class=\"kpi-label\">Passed</span><span class=\"kpi-value\" id=\"kpi-passed\">0</span></div><div class=\"kpi-card kpi-card-fail\"><span class=\"kpi-label\">Failed</span><span class=\"kpi-value\" id=\"kpi-failed\">0</span></div><div class=\"kpi-card kpi-card-skip\"><span class=\"kpi-label\">Skipped</span><span class=\"kpi-value\" id=\"kpi-skipped\">0</span></div><div class=\"kpi-card kpi-card-pct\"><span class=\"kpi-label\">Pass %</span><span class=\"kpi-value\" id=\"kpi-pass-pct\">—</span></div></div><div class=\"kpi-charts-row\"><div class=\"kpi-chart-cell\"><canvas id=\"kpiDonutChart\" aria-label=\"Outcome distribution\"></canvas></div><div class=\"kpi-chart-cell\"><canvas id=\"kpiStackedBarChart\" aria-label=\"Pass and fail counts by execution\"></canvas></div></div><section class=\"kpi-perf-section\" id=\"kpi-sec-performance\" aria-label=\"Execution time and performance\"><h3 class=\"kpi-perf-heading\">Execution Time &amp; Performance</h3><p class=\"kpi-perf-sub\">From test case start/end times in each report. Suite P95/P99 use one total duration per listed run (folder).</p><div class=\"kpi-perf-grid\"><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Total suite execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-total-suite\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Avg test case execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-avg-tc\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Suite duration (P95 / P99)</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-pct\">—</span></div><div class=\"kpi-card kpi-card-dur kpi-perf-span2\" id=\"kpi-sec-slow\"><div class=\"kpi-perf-slow-head\"><button type=\"button\" class=\"kpi-perf-slow-toggle\" id=\"kpi-perf-slow-toggle\" aria-expanded=\"true\" aria-controls=\"kpi-perf-slow-wrap\" title=\"Collapse or expand slowest list\"><span class=\"kpi-perf-slow-caret\" aria-hidden=\"true\">▼</span></button><span class=\"kpi-label\">Slowest test cases (max duration per name)</span></div><div class=\"kpi-perf-slow-wrap\" id=\"kpi-perf-slow-wrap\"><ol class=\"kpi-perf-slow\" id=\"kpi-perf-slow\"><li class=\"kpi-perf-slow-empty\">No timing data yet.</li></ol></div></div></div><div class=\"kpi-perf-charts\"><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSuiteDurChart\" aria-label=\"Suite execution time by run\"></canvas></div><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSlowChart\" aria-label=\"Slowest test cases\"></canvas></div></div></section><section class=\"kpi-flaky-section\" id=\"kpi-sec-flaky\" aria-label=\"Flaky test detection\"><h3 class=\"kpi-flaky-heading\">Flaky Test Detection</h3><p class=\"kpi-flaky-sub\">Tests failing intermittently: must show both <strong>passed</strong> and <strong>failed</strong> across builds in your KPI scope. <strong>Failed runs</strong> = failure outcomes (retry proxy; retries are not stored in JSON). <strong>Stability</strong> = passes ÷ (passes + fails).</p><div class=\"kpi-flaky-summary\"><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-intermittent-count\">0</span><span class=\"kpi-flaky-summary-label\"> intermittent tests</span><span class=\"kpi-flaky-summary-sep\">·</span><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-fail-outcomes\">0</span><span class=\"kpi-flaky-summary-label\"> failed runs (those tests)</span></div><div class=\"kpi-flaky-wrap\"><table class=\"kpi-flaky-table\" id=\"kpi-flaky-table\"><thead><tr><th>Test case</th><th>Failed runs</th><th>Passed runs</th><th>Stability</th><th>Pass/fail flips</th></tr></thead><tbody id=\"kpi-flaky-body\"><tr><td colspan=\"5\" class=\"kpi-flaky-empty\">Need at least two builds in scope to compare outcomes.</td></tr></tbody></table></div></section><section class=\"kpi-trend-section\" id=\"kpi-sec-trend\" aria-label=\"Trend analysis\"><h3 class=\"kpi-trend-heading\">Trend Analysis (Build-wise)</h3><p class=\"kpi-sub\">Pass % over time, failures per build, and execution time trend (oldest folder left, newest right).</p><div class=\"kpi-trend-grid\"><div class=\"kpi-trend-cell\" id=\"kpi-chart-pass-pct\"><canvas id=\"kpiTrendPassPct\" aria-label=\"Pass percent over time\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-failures\"><canvas id=\"kpiTrendFailures\" aria-label=\"Failures per build\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-duration\"><canvas id=\"kpiTrendDuration\" aria-label=\"Execution time trend\"></canvas></div></div></section></section></div>\n");
        } else {
            html.append("<div id=\"view-test-suites\" class=\"content-view active\">\n");
            html.append("<section class=\"chart-panel\" aria-label=\"Execution trend\">\n");
            html.append("<div class=\"chart-head\"><div class=\"chart-head-inner\">\n");
            html.append("<div class=\"chart-head-row\"><div class=\"chart-title-wrap\"><h2 id=\"chart-title\">Test cases executed — last 1 hour</h2></div>\n");
            html.append("<div class=\"chart-controls\"><div class=\"chart-range-field\">");
            html.append("<div class=\"chart-range-tags\" role=\"group\" aria-labelledby=\"chart-range-label\">\n");
            html.append("<button type=\"button\" class=\"chart-range-btn active\" data-range=\"1h\" aria-pressed=\"true\">1 hour</button>\n");
            html.append("<button type=\"button\" class=\"chart-range-btn\" data-range=\"6h\" aria-pressed=\"false\">6 hours</button>\n");
            html.append("<button type=\"button\" class=\"chart-range-btn\" data-range=\"1d\" aria-pressed=\"false\">1 day</button>\n");
            html.append("<button type=\"button\" class=\"chart-range-btn\" data-range=\"1w\" aria-pressed=\"false\">1 week</button>\n");
            html.append("<button type=\"button\" class=\"chart-range-btn\" data-range=\"2w\" aria-pressed=\"false\">2 weeks</button>\n");
            html.append("<button type=\"button\" class=\"chart-range-btn\" data-range=\"1m\" aria-pressed=\"false\">1 month</button>\n");
            html.append("<button type=\"button\" class=\"chart-range-btn\" data-range=\"custom\" aria-pressed=\"false\">Custom</button></div></div>\n");
            html.append("<span id=\"chart-live\" class=\"chart-live\">Live · 5s</span></div></div>\n");
            html.append("<div id=\"chart-custom-wrap\" class=\"chart-custom-range\"><label>From <input type=\"datetime-local\" id=\"chart-custom-from\"></label>\n");
            html.append("<label>To <input type=\"datetime-local\" id=\"chart-custom-to\"></label>\n");
            html.append("<button type=\"button\" id=\"chart-custom-apply\" class=\"chart-apply-btn\">Apply</button></div></div></div>\n");
            html.append("<div class=\"chart-canvas-wrap\"><canvas id=\"suiteExecChart\" aria-label=\"Pass and fail counts over time\"></canvas></div>\n");
            html.append("<p class=\"chart-note\" id=\"chart-note\"><strong>Pass</strong> (green area) and <strong>fail</strong> (red line) &mdash; test cases <strong>finished per minute</strong> in the selected range, from <strong>all listed runs</strong></p>\n");
            html.append("</section>\n");
            html.append("<h2>Test Suites</h2><div class=\"suite-grid\" id=\"suite-grid\">\n");
            for (int i = 0; i < entries.size(); i++) {
                ReportIndexEntry e = entries.get(i);
                String enc = urlPathEncode(e.folderName);
                String viewHref = enc + "/html/" + (e.hasFinal ? "merv-report.html" : "merv-report-live.html");
                String copyPath = e.folderName.replace('\\', '/') + "/html/" + (e.hasFinal ? "merv-report.html" : "merv-report-live.html");
                boolean completed = e.hasFinal;
                ReportSummary s = e.summary;
                int tot = s.total > 0 ? s.total : (s.pass + s.fail + s.skip);
                if (tot <= 0 && (e.hasFinal || e.hasLive)) {
                    tot = s.pass + s.fail + s.skip;
                }
                String dataQ = (e.folderName + " " + s.title + " " + s.tagsDisplay).toLowerCase(Locale.ROOT);
                html.append("<article class=\"suite-card");
                if (i == 0) {
                    html.append(" is-latest");
                }
                html.append("\" data-q=\"").append(MervHtmlEscape.escapeHtml(dataQ)).append("\" data-card-idx=\"").append(i).append("\" data-json=\"").append(enc).append("/json/merv-report.json\" data-folder-name=\"").append(MervHtmlEscape.escapeHtml(e.folderName)).append("\">\n");
                html.append("<div class=\"suite-top\"><div class=\"suite-title-block\"><h3 class=\"suite-name\">").append(MervHtmlEscape.escapeHtml(s.title)).append("</h3>\n");
                html.append("<p class=\"suite-folder\">").append(MervHtmlEscape.escapeHtml(e.folderName)).append("</p></div>\n");
                if (completed) {
                    html.append("<span class=\"status-badge done\">Completed</span>");
                } else {
                    html.append("<span class=\"status-badge run\">In progress</span>");
                }
                html.append("</div>\n");
                html.append("<div class=\"suite-counts\"><strong>Total</strong> <span class=\"cnt-total\">").append(tot).append("</span>");
                html.append(" &nbsp;|&nbsp; <strong>Pass</strong> <span class=\"cnt-pass\">").append(s.pass).append("</span>");
                html.append(" &nbsp;|&nbsp; <strong>Fail</strong> <span class=\"cnt-fail\">").append(s.fail).append("</span>");
                html.append("<span class=\"cnt-skip-seg\"").append(s.skip > 0 ? "" : " style=\"display:none\"").append("> &nbsp;|&nbsp; <strong>Skip</strong> <span class=\"cnt-skip\">").append(s.skip).append("</span></span>");
                html.append("</div>\n");
                html.append("<div class=\"suite-meta\"><div class=\"suite-meta-row\">\n");
                html.append("<dl class=\"suite-meta-dl\"><dt>Environment</dt><dd>").append(MervHtmlEscape.escapeHtml(s.env)).append("</dd>");
                html.append("<dt>Release</dt><dd>").append(MervHtmlEscape.escapeHtml(s.release)).append("</dd>");
                html.append("<dt>Sprint</dt><dd>").append(MervHtmlEscape.escapeHtml(s.sprint)).append("</dd></dl>\n");
                html.append("<div class=\"suite-meta-donut\"><div class=\"donut\" style=\"").append(donutConicStyle(s.pass, s.fail, s.skip)).append("\" title=\"Pass / Fail / Skip\"></div></div>\n");
                html.append("</div>\n<div class=\"suite-tags-block\"><div class=\"tag-row\">");
                if (s.tagPills.isEmpty()) {
                    html.append("<span class=\"tag-pill tag-pill-empty\" style=\"opacity:.5\">—</span>");
                } else {
                    for (String tag : s.tagPills) {
                        html.append("<span class=\"tag-pill\">").append(MervHtmlEscape.escapeHtml(tag)).append("</span>");
                    }
                }
                html.append("</div></div></div>\n");
                html.append("<div class=\"suite-foot\"><div class=\"suite-when\">");
                html.append(MervHtmlEscape.escapeHtml(footFmt.format(new Date(e.lastModified))));
                html.append("<span class=\"rel\">").append(MervHtmlEscape.escapeHtml(relativeTimeAgo(e.lastModified))).append("</span></div>\n");
                html.append("<div class=\"suite-actions\">");
                html.append("<a class=\"btn-view\" href=\"").append(viewHref).append("\">View Cases</a>");
                html.append("<a class=\"icon-btn\" title=\"Open in new tab\" href=\"").append(viewHref).append("\" target=\"_blank\" rel=\"noopener\">↗</a>");
                html.append("<button type=\"button\" class=\"btn-delete\" title=\"Delete this report folder\" data-folder=\"").append(MervHtmlEscape.escapeHtml(e.folderName)).append("\" onclick=\"deleteReport(this)\">Delete</button>");
                html.append("</div></div></article>\n");
            }
            html.append("</div>\n");
            html.append("</div>\n");
            html.append("<div id=\"view-kpis\" class=\"content-view\"><section class=\"kpi-panel\" aria-label=\"KPI reports\"><h2 id=\"kpi-sec-execution-summary\">Test execution summary</h2><div class=\"kpi-build-scope-bar\" id=\"kpi-build-scope-bar\" role=\"region\" aria-label=\"KPI build scope\"><span class=\"kpi-build-scope-label\" id=\"kpi-build-scope-label\">Builds in KPI charts</span><select id=\"kpi-build-scope\" class=\"kpi-build-scope-select\" aria-labelledby=\"kpi-build-scope-label\"><option value=\"20\" selected>Last 20 builds</option><option value=\"30\">Last 30 builds</option><option value=\"50\">Last 50 builds</option><option value=\"all\">All builds</option><option value=\"custom\">Custom…</option></select><span id=\"kpi-build-custom-wrap\" class=\"kpi-build-custom-wrap\"><label for=\"kpi-build-custom\" class=\"kpi-build-custom-label\">Last</label><input type=\"number\" id=\"kpi-build-custom\" class=\"kpi-build-custom-input\" min=\"1\" max=\"999\" value=\"20\" /><span class=\"kpi-build-custom-suffix\">builds</span><button type=\"button\" id=\"kpi-build-custom-apply\" class=\"kpi-build-custom-apply\">Apply</button></span><span class=\"kpi-scope-spacer\" aria-hidden=\"true\"></span><div class=\"kpi-custom-settings\" role=\"region\" aria-label=\"Custom Setting\"><span class=\"kpi-custom-settings-title\">Custom Setting</span><label class=\"kpi-tag-filter-label\" for=\"kpi-tag-filter\">Tag</label><select id=\"kpi-tag-filter\" aria-label=\"Filter KPI by tag\"><option value=\"\">All tags</option></select></div><span id=\"kpi-build-scope-status\" class=\"kpi-build-scope-status\"></span></div><p class=\"kpi-sub\">Charts and KPI metrics aggregated across all listed runs (each run contributes its test cases to totals).</p><div class=\"kpi-grid\"><div class=\"kpi-card kpi-card-total\"><span class=\"kpi-label\">Total test cases</span><span class=\"kpi-value\" id=\"kpi-total-tc\">0</span></div><div class=\"kpi-card kpi-card-pass\"><span class=\"kpi-label\">Passed</span><span class=\"kpi-value\" id=\"kpi-passed\">0</span></div><div class=\"kpi-card kpi-card-fail\"><span class=\"kpi-label\">Failed</span><span class=\"kpi-value\" id=\"kpi-failed\">0</span></div><div class=\"kpi-card kpi-card-skip\"><span class=\"kpi-label\">Skipped</span><span class=\"kpi-value\" id=\"kpi-skipped\">0</span></div><div class=\"kpi-card kpi-card-pct\"><span class=\"kpi-label\">Pass %</span><span class=\"kpi-value\" id=\"kpi-pass-pct\">—</span></div></div><div class=\"kpi-charts-row\"><div class=\"kpi-chart-cell\"><canvas id=\"kpiDonutChart\" aria-label=\"Outcome distribution\"></canvas></div><div class=\"kpi-chart-cell\"><canvas id=\"kpiStackedBarChart\" aria-label=\"Pass and fail counts by execution\"></canvas></div></div><section class=\"kpi-perf-section\" id=\"kpi-sec-performance\" aria-label=\"Execution time and performance\"><h3 class=\"kpi-perf-heading\">Execution Time &amp; Performance</h3><p class=\"kpi-perf-sub\">From test case start/end times in each report. Suite P95/P99 use one total duration per listed run (folder).</p><div class=\"kpi-perf-grid\"><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Total suite execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-total-suite\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Avg test case execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-avg-tc\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Suite duration (P95 / P99)</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-pct\">—</span></div><div class=\"kpi-card kpi-card-dur kpi-perf-span2\" id=\"kpi-sec-slow\"><div class=\"kpi-perf-slow-head\"><button type=\"button\" class=\"kpi-perf-slow-toggle\" id=\"kpi-perf-slow-toggle\" aria-expanded=\"true\" aria-controls=\"kpi-perf-slow-wrap\" title=\"Collapse or expand slowest list\"><span class=\"kpi-perf-slow-caret\" aria-hidden=\"true\">▼</span></button><span class=\"kpi-label\">Slowest test cases (max duration per name)</span></div><div class=\"kpi-perf-slow-wrap\" id=\"kpi-perf-slow-wrap\"><ol class=\"kpi-perf-slow\" id=\"kpi-perf-slow\"><li class=\"kpi-perf-slow-empty\">No timing data yet.</li></ol></div></div></div><div class=\"kpi-perf-charts\"><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSuiteDurChart\" aria-label=\"Suite execution time by run\"></canvas></div><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSlowChart\" aria-label=\"Slowest test cases\"></canvas></div></div></section><section class=\"kpi-flaky-section\" id=\"kpi-sec-flaky\" aria-label=\"Flaky test detection\"><h3 class=\"kpi-flaky-heading\">Flaky Test Detection</h3><p class=\"kpi-flaky-sub\">Tests failing intermittently: must show both <strong>passed</strong> and <strong>failed</strong> across builds in your KPI scope. <strong>Failed runs</strong> = failure outcomes (retry proxy; retries are not stored in JSON). <strong>Stability</strong> = passes ÷ (passes + fails).</p><div class=\"kpi-flaky-summary\"><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-intermittent-count\">0</span><span class=\"kpi-flaky-summary-label\"> intermittent tests</span><span class=\"kpi-flaky-summary-sep\">·</span><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-fail-outcomes\">0</span><span class=\"kpi-flaky-summary-label\"> failed runs (those tests)</span></div><div class=\"kpi-flaky-wrap\"><table class=\"kpi-flaky-table\" id=\"kpi-flaky-table\"><thead><tr><th>Test case</th><th>Failed runs</th><th>Passed runs</th><th>Stability</th><th>Pass/fail flips</th></tr></thead><tbody id=\"kpi-flaky-body\"><tr><td colspan=\"5\" class=\"kpi-flaky-empty\">Need at least two builds in scope to compare outcomes.</td></tr></tbody></table></div></section><section class=\"kpi-trend-section\" id=\"kpi-sec-trend\" aria-label=\"Trend analysis\"><h3 class=\"kpi-trend-heading\">Trend Analysis (Build-wise)</h3><p class=\"kpi-sub\">Pass % over time, failures per build, and execution time trend (oldest folder left, newest right).</p><div class=\"kpi-trend-grid\"><div class=\"kpi-trend-cell\" id=\"kpi-chart-pass-pct\"><canvas id=\"kpiTrendPassPct\" aria-label=\"Pass percent over time\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-failures\"><canvas id=\"kpiTrendFailures\" aria-label=\"Failures per build\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-duration\"><canvas id=\"kpiTrendDuration\" aria-label=\"Execution time trend\"></canvas></div></div></section></section></div>\n");
            html.append("<div id=\"view-consolidated\" class=\"content-view\">\n");
            html.append("<section class=\"consolidated-panel\" aria-label=\"Consolidated testcase report\">\n");
            html.append("<h2>Consolidated Report</h2>\n");
            html.append("<div class=\"cons-subtabs\" role=\"tablist\" aria-label=\"Consolidated report views\"><button type=\"button\" class=\"cons-subtab active\" id=\"cons-tab-testcase\" data-cons-sub=\"testcase\" role=\"tab\" aria-selected=\"true\">TestCase View</button><button type=\"button\" class=\"cons-subtab\" id=\"cons-tab-tags\" data-cons-sub=\"tags\" role=\"tab\" aria-selected=\"false\">Tag based Report</button><button type=\"button\" class=\"cons-subtab\" id=\"cons-tab-failures\" data-cons-sub=\"failures\" role=\"tab\" aria-selected=\"false\">Failure Summary</button></div>\n");
            html.append("<div id=\"cons-subpanel-testcase\" class=\"cons-subpanel active\" role=\"tabpanel\" aria-labelledby=\"cons-tab-testcase\"><div class=\"consolidated-search-wrap\"><input type=\"search\" id=\"consolidated-search\" placeholder=\"Search testcase or tag…\" autocomplete=\"off\"></div><div class=\"consolidated-wrap\">\n");
            html.append("<table class=\"consolidated-table\" id=\"consolidated-table\"><thead><tr><th>Testcase Name</th><th>Current Status<BR/> (Last Run)</th><th>Last 5 Run <BR/>Status</th><th>Last Passed <BR/>Time</th><th>Last Failed <BR/>Time</th><th>Total Pass</th><th>Total Fail</th></tr></thead><tbody id=\"consolidated-body\"><tr><td colspan=\"7\">Loading consolidated data…</td></tr></tbody></table>\n");
            html.append("</div></div>\n");
            html.append("<div id=\"cons-subpanel-tags\" class=\"cons-subpanel\" role=\"tabpanel\" aria-labelledby=\"cons-tab-tags\"><p class=\"kpi-sub cons-tag-hint\">Full testcase rows under each tag (same columns as TestCase View). Tests with multiple tags are listed under each tag. Expand a row for per-suite runs.</p><div id=\"consolidated-tag-root\" class=\"consolidated-tag-root\"></div></div>\n");
            html.append("<div id=\"cons-subpanel-failures\" class=\"cons-subpanel\" role=\"tabpanel\" aria-labelledby=\"cons-tab-failures\"><p class=\"cons-fail-hint\">Failure reasons from the <strong>latest execution per testcase</strong>. Fixed tests disappear automatically.</p><div id=\"cons-fail-root\" class=\"cons-fail-wrap\"><p class=\"cons-fail-empty\">Loading failure summary…</p></div></div>\n");
            html.append("</section>\n");
            html.append("</div>\n");
        }

        html.append("</div></div></div>\n");
        html.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js\" crossorigin=\"anonymous\"></script>\n");
        html.append("<script>\n");
        html.append("function copyPath(btn){var p=btn.getAttribute('data-copy');if(!p)return;(navigator.clipboard&&navigator.clipboard.writeText?navigator.clipboard.writeText(p):Promise.reject()).catch(function(){var t=document.createElement('textarea');t.value=p;document.body.appendChild(t);t.select();try{document.execCommand('copy');}finally{document.body.removeChild(t);}});}\n");
        html.append("function deleteReport(btn){var folder=btn.getAttribute('data-folder');if(!folder)return;if(!confirm('Delete report folder \"'+folder+'\" permanently? This cannot be undone.'))return;var url=window.MERV_REPORTS_DELETE_API||'';if(!url){alert('Delete API is not configured.');return;}btn.disabled=true;fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({folder:folder})}).then(function(r){return r.text().then(function(t){try{return JSON.parse(t);}catch(e){return{ok:false,error:t||'Bad response'};}});}).then(function(j){if(j&&j.ok)location.reload();else{alert((j&&j.error)||'Delete failed');btn.disabled=false;}}).catch(function(){alert('Could not reach the local delete API. From project root run: mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=\"org.teche.merv.client.utils.ReportsDeleteServer\" (port in merv.properties: merv.reports.delete.port, default 9191).');btn.disabled=false;});}\n");
        html.append("var __navKpiTarget='kpi-sec-execution-summary';\n");
        html.append("function showIndexView(v){var suites=document.getElementById('view-test-suites');var cons=document.getElementById('view-consolidated');var kpis=document.getElementById('view-kpis');if(suites)suites.classList.toggle('active',v==='test-suites');if(cons)cons.classList.toggle('active',v==='consolidated');if(kpis)kpis.classList.toggle('active',v==='kpis');document.querySelectorAll('.nav-main-link[data-view]').forEach(function(a){a.classList.toggle('active',(a.getAttribute('data-view')||'')===v);});var kpiT=typeof __navKpiTarget!=='undefined'?__navKpiTarget:'kpi-sec-execution-summary';document.querySelectorAll('.nav-sub-link[data-view]').forEach(function(a){var dv=a.getAttribute('data-view')||'';if(dv!==v){a.classList.remove('active');return;}if(v==='consolidated'){var want=a.getAttribute('data-cons-tab')||'testcase';var cur='testcase';var bt=document.getElementById('cons-tab-testcase');var bg=document.getElementById('cons-tab-tags');var bf=document.getElementById('cons-tab-failures');if(bf&&bf.classList.contains('active'))cur='failures';else if(bg&&bg.classList.contains('active'))cur='tags';else if(bt&&bt.classList.contains('active'))cur='testcase';a.classList.toggle('active',want===cur);}else if(v==='kpis'){var st=a.getAttribute('data-scroll-target')||'';a.classList.toggle('active',st===kpiT);}else{a.classList.remove('active');}});}\n");
        html.append("document.querySelectorAll('.nav a[data-view]').forEach(function(a){a.addEventListener('click',function(ev){ev.preventDefault();var v=a.getAttribute('data-view')||'test-suites';if(a.classList.contains('nav-sub-link')){if(v==='consolidated'&&typeof window.setConsSubView==='function')window.setConsSubView(a.getAttribute('data-cons-tab')||'testcase');if(v==='kpis')__navKpiTarget=a.getAttribute('data-scroll-target')||'kpi-sec-execution-summary';}showIndexView(v);if(a.classList.contains('nav-sub-link')){var tid=a.getAttribute('data-scroll-target');if(tid)requestAnimationFrame(function(){var el=document.getElementById(tid);if(el)el.scrollIntoView({behavior:'smooth',block:'start'});});}});});\n");
        html.append("var ss=document.getElementById('suite-search');if(ss){ss.addEventListener('input',function(){var q=(this.value||'').toLowerCase().trim();document.querySelectorAll('.suite-card').forEach(function(c){var d=c.getAttribute('data-q')||'';c.style.display=!q||d.indexOf(q)>=0?'':'none';});});}\n");
        html.append(mervKpiPerfChartsHelperJs());
        html.append("(function liveDashboard(){if(typeof Chart!=='undefined'){Chart.defaults.font.family='Roboto';}var folders=window.MERV_REPORT_FOLDERS||[];var kpiDonut=null;var kpiBarChart=null;var kpiTrendPass=null;var kpiTrendFail=null;var kpiTrendDur=null;var kpiPerfSuite=null;var kpiPerfSlow=null;var kpiScopeState={mode:'20',customN:20};var kpiTagState={tag:''};var kpiTagSel=document.getElementById('kpi-tag-filter');var lastSnaps=null;function loadSavedTag(){try{return localStorage.getItem('merv.kpi.tag')||'';}catch(e){return'';}}function saveTag(v){try{localStorage.setItem('merv.kpi.tag',v||'');}catch(e){}}kpiTagState.tag=loadSavedTag();function fmtKpiDur(sec){if(sec==null||isNaN(sec)||sec<0)return'0s';var s=Math.floor(sec);var h=Math.floor(s/3600),m=Math.floor((s%3600)/60),r=s%60;if(h>0)return h+'h '+m+'m '+r+'s';if(m>0)return m+'m '+r+'s';return r+'s';}function getKpiSnaps(snaps){if(!snaps||!snaps.length)return[];var m=kpiScopeState.mode,take;if(m==='all')return snaps.slice();if(m==='custom')take=Math.max(1,parseInt(kpiScopeState.customN,10)||1);else if(m==='20')take=20;else if(m==='30')take=30;else if(m==='50')take=50;else take=snaps.length;take=Math.min(take,snaps.length);return snaps.slice(0,take);}function rowTags(row){if(!row)return[];var v=row.tags;if(v==null)v=row.testcaseTags;if(v==null)v=row.tagPills;if(v==null)v=row.tagsDisplay;if(Array.isArray(v))return v.map(function(x){return String(x||'').trim();}).filter(function(x){return x;});if(typeof v==='string'){return v.split(/[\\n,]/g).map(function(x){return String(x||'').trim();}).filter(function(x){return x;});}return [];}function hasTag(row,tag){if(!tag)return true;var arr=rowTags(row);for(var i=0;i<arr.length;i++){if(arr[i]===tag)return true;}return false;}function applyTagFilterToSnaps(snaps,tag){if(!tag)return (snaps||[]).slice();return (snaps||[]).map(function(d){if(!d||!d.testSuite)return d;var tc=(d.testSuite&&d.testSuite.testCases)||[];var keep=tc.filter(function(r){return hasTag(r,tag);});var ds=Object.assign({},d);ds.testSuite=Object.assign({},d.testSuite);ds.testSuite.testCases=keep;return ds;});}function collectAllTags(snaps){var seen={},out=[],i,d,tc,j,row,tg,tags,k;for(i=0;i<(snaps||[]).length;i++){d=snaps[i];tc=(d&&d.testSuite&&d.testSuite.testCases)||[];for(j=0;j<tc.length;j++){row=tc[j]||{};tags=rowTags(row);for(k=0;k<tags.length;k++){tg=String(tags[k]||'').trim();if(!tg||seen[tg])continue;seen[tg]=1;out.push(tg);}}}out.sort(function(a,b){return a.localeCompare(b);});return out;}function syncKpiTagOptions(allSnaps){if(!kpiTagSel)return;var tags=collectAllTags(allSnaps);var cur=kpiTagState.tag||'';var ok=!cur||tags.indexOf(cur)>=0;var target=ok?cur:'';if(!ok){kpiTagState.tag='';saveTag('');}var opts='<option value=\"\">All tags</option>'+tags.map(function(t){return'<option value=\"'+escHtml(t)+'\">'+escHtml(t)+'</option>';}).join('');kpiTagSel.innerHTML=opts;kpiTagSel.value=target;}if(kpiTagSel){kpiTagSel.addEventListener('change',function(){kpiTagState.tag=String(kpiTagSel.value||'');saveTag(kpiTagState.tag);if(lastSnaps){var scoped=getKpiSnaps(lastSnaps);var filtered=applyTagFilterToSnaps(scoped,kpiTagState.tag);updateKpiPanel(filtered);refreshKpiScopeStatus();}});}function refreshKpiScopeStatus(){var el=document.getElementById('kpi-build-scope-status');if(!el)return;if(!lastSnaps||!lastSnaps.length){el.textContent='';return;}var k=getKpiSnaps(lastSnaps);el.textContent='Showing '+k.length+' of '+lastSnaps.length+' builds (newest first)'+(kpiTagState.tag?(' · Tag: '+kpiTagState.tag):'');}function computeFlakyRows(snaps){var chron=(snaps||[]).slice().reverse(),byName={},di,tc,row,name,st,seen,j;if(!chron.length)return[];for(di=0;di<chron.length;di++){tc=(chron[di]&&chron[di].testSuite&&chron[di].testSuite.testCases)||[];seen={};for(j=0;j<tc.length;j++){row=tc[j];name=String(row.testcaseName||'Unnamed').trim()||'Unnamed testcase';if(seen[name])continue;seen[name]=1;st=String(row.status||'').toUpperCase();if(!byName[name])byName[name]={seq:[]};byName[name].seq.push(st);}}var out=[],nm,seq,passes,fails,denom,stability,flips,i,a,b,ap,bp;for(nm in byName){if(!Object.prototype.hasOwnProperty.call(byName,nm))continue;seq=byName[nm].seq;passes=0;fails=0;for(i=0;i<seq.length;i++){if(seq[i]==='PASSED')passes++;else if(seq[i]==='FAILED')fails++;}denom=passes+fails;stability=denom>0?Math.round(1000*passes/denom)/10:null;flips=0;for(i=1;i<seq.length;i++){a=seq[i-1];b=seq[i];ap=a==='PASSED'||a==='FAILED';bp=b==='PASSED'||b==='FAILED';if(ap&&bp&&a!==b)flips++;}if(passes>0&&fails>0&&seq.length>=2)out.push({name:nm,passes:passes,fails:fails,stability:stability,flips:flips});}out.sort(function(x,y){return (x.stability-y.stability)||(y.fails-x.fails)});return out;}function updateFlakySection(snaps){var body=document.getElementById('kpi-flaky-body'),cI=document.getElementById('kpi-flaky-intermittent-count'),cF=document.getElementById('kpi-flaky-fail-outcomes'),rows,sumF,i,need;if(!body)return;rows=computeFlakyRows(snaps);if(cI)cI.textContent=String(rows.length);sumF=0;for(i=0;i<rows.length;i++)sumF+=rows[i].fails;if(cF)cF.textContent=String(sumF);if(!rows.length){need=snaps&&snaps.length>=2;body.innerHTML='<tr><td colspan=\"5\" class=\"kpi-flaky-empty\">'+(need?'No intermittent tests in this selection.':'Select at least two builds in KPI scope to compare.')+'</td></tr>';return;}body.innerHTML=rows.map(function(r){return'<tr><td class=\"kpi-flaky-name\">'+escHtml(r.name)+'</td><td class=\"kpi-flaky-num\">'+r.fails+'</td><td class=\"kpi-flaky-num\">'+r.passes+'</td><td class=\"kpi-flaky-num\">'+(r.stability!=null?r.stability+'%':'—')+'</td><td class=\"kpi-flaky-num\">'+r.flips+'</td></tr>';}).join('');}function updateTrendCharts(snaps){var ge=function(id){return document.getElementById(id);};var c1=ge('kpiTrendPassPct'),c2=ge('kpiTrendFailures'),c3=ge('kpiTrendDuration');if(!c1||!c2||!c3||typeof Chart==='undefined')return;var arr=(snaps||[]).slice().reverse();var labels=[],pp=[],ff=[],dd=[];arr.forEach(function(d){var T=tally(d);labels.push((d&&d.__mervFolder)?decodeUi(String(d.__mervFolder)):'\\u2014');var fin=T.pass+T.fail+T.skip;pp.push(fin>0?Math.round(1000*T.pass/fin)/10:0);ff.push(T.fail);var tc=(d&&d.testSuite&&d.testSuite.testCases)||[];var sec=0;tc.forEach(function(row){var a=parseTs(row.startTime),b=parseTs(row.endTime);if(a&&b&&b.getTime()>=a.getTime())sec+=(b.getTime()-a.getTime())/1000;});dd.push(sec);});if(!labels.length){if(kpiTrendPass){kpiTrendPass.destroy();kpiTrendPass=null;}if(kpiTrendFail){kpiTrendFail.destroy();kpiTrendFail=null;}if(kpiTrendDur){kpiTrendDur.destroy();kpiTrendDur=null;}return;}if(!kpiTrendPass){kpiTrendPass=new Chart(c1,{type:'line',data:{labels:labels,datasets:[{label:'Pass %',data:pp,borderColor:'#28a745',backgroundColor:'rgba(40,167,69,0.12)',fill:true,tension:0.3,pointRadius:3}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Pass % over time',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:50,minRotation:0}},y:{min:0,max:100,beginAtZero:true}}}});}else{kpiTrendPass.data.labels=labels;kpiTrendPass.data.datasets[0].data=pp;kpiTrendPass.update('none');}if(!kpiTrendFail){kpiTrendFail=new Chart(c2,{type:'bar',data:{labels:labels,datasets:[{label:'Failures',data:ff,backgroundColor:'rgba(220,53,69,0.88)',borderColor:'#b02a37',borderWidth:1}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Failures per build',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:50,minRotation:0}},y:{beginAtZero:true,ticks:{precision:0}}}}});}else{kpiTrendFail.data.labels=labels;kpiTrendFail.data.datasets[0].data=ff;kpiTrendFail.update('none');}if(!kpiTrendDur){kpiTrendDur=new Chart(c3,{type:'line',data:{labels:labels,datasets:[{label:'Duration (s)',data:dd,borderColor:'#17a2b8',backgroundColor:'rgba(23,162,184,0.12)',fill:true,tension:0.35,pointRadius:3}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Execution time trend',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:50,minRotation:0}},y:{beginAtZero:true,ticks:{callback:function(v){return v+' s';}}}}}});}else{kpiTrendDur.data.labels=labels;kpiTrendDur.data.datasets[0].data=dd;kpiTrendDur.update('none');}}function updateKpiPanel(snaps){var totP=0,totF=0,totK=0,i,d,T,fin,pct;for(i=0;i<(snaps||[]).length;i++){d=snaps[i];T=tally(d);totP+=T.pass;totF+=T.fail;totK+=T.skip;}fin=totP+totF+totK;pct=fin>0?Math.round(1000*totP/fin)/10:null;var elT=document.getElementById('kpi-total-tc');var elP=document.getElementById('kpi-passed');var elF=document.getElementById('kpi-failed');var elK=document.getElementById('kpi-skipped');var elPct=document.getElementById('kpi-pass-pct');if(elT)elT.textContent=String(fin);if(elP)elP.textContent=String(totP);if(elF)elF.textContent=String(totF);if(elK)elK.textContent=String(totK);if(elPct)elPct.textContent=(pct==null?'—':(pct+'%'));updateFlakySection(snaps);updateTrendCharts(snaps);if(typeof Chart!=='undefined'){var dc=document.getElementById('kpiDonutChart');if(dc){if(!kpiDonut){kpiDonut=new Chart(dc,{type:'doughnut',data:{labels:['Passed','Failed','Skipped'],datasets:[{data:[totP,totF,totK],backgroundColor:['#28a745','#dc3545','#ffc107'],borderWidth:0}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{position:'bottom'}}}});}else{kpiDonut.data.datasets[0].data=[totP,totF,totK];kpiDonut.update('none');}}var bc=document.getElementById('kpiStackedBarChart');if(bc){var arr=(snaps||[]).slice().reverse();var labels=[],pA=[],fA=[];arr.forEach(function(x){var t=tally(x);labels.push((x&&x.__mervFolder)?decodeUi(String(x.__mervFolder)):'—');pA.push(t.pass);fA.push(t.fail);});if(!kpiBarChart){kpiBarChart=new Chart(bc,{type:'bar',data:{labels:labels,datasets:[{label:'Passed',data:pA,backgroundColor:'rgba(40,167,69,0.85)',stack:'s'},{label:'Failed',data:fA,backgroundColor:'rgba(220,53,69,0.85)',stack:'s'}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{position:'bottom'}},scales:{x:{stacked:true,ticks:{maxRotation:50,minRotation:0}},y:{stacked:true,beginAtZero:true,ticks:{precision:0}}}}});}else{kpiBarChart.data.labels=labels;kpiBarChart.data.datasets[0].data=pA;kpiBarChart.data.datasets[1].data=fA;kpiBarChart.update('none');}}}var perfTotSuite=0,perfTcSum=0,perfTcN=0;var slow={},suiteSecsRaw=[],tc,row,a,b,sec,nm,max;for(i=0;i<(snaps||[]).length;i++){d=snaps[i];tc=(d&&d.testSuite&&d.testSuite.testCases)||[];var suiteSec=0;for(var j=0;j<tc.length;j++){row=tc[j]||{};a=parseTs(row.startTime);b=parseTs(row.endTime);if(a&&b&&b.getTime()>=a.getTime()){sec=(b.getTime()-a.getTime())/1000;perfTcSum+=sec;perfTcN++;suiteSec+=sec;nm=String(row.testcaseName||'').trim();if(nm){max=slow[nm];if(max==null||sec>max)slow[nm]=sec;}}}perfTotSuite+=suiteSec;suiteSecsRaw.push(suiteSec);}var elSuite=document.getElementById('kpi-perf-total-suite');if(elSuite)elSuite.textContent=fmtKpiDur(perfTotSuite);var elAvg=document.getElementById('kpi-perf-avg-tc');if(elAvg)elAvg.textContent=(perfTcN>0?(fmtKpiDur(perfTcSum/perfTcN)):'—');var elPerfPct=document.getElementById('kpi-perf-pct');if(elPerfPct){if(suiteSecsRaw.length){var sd=suiteSecsRaw.slice().sort(function(x,y){return x-y;});var pf=function(p){if(sd.length===1)return sd[0];var r=(p/100)*(sd.length-1),lo=Math.floor(r),hi=Math.ceil(r);if(lo===hi)return sd[lo];return sd[lo]+(sd[hi]-sd[lo])*(r-lo);};elPerfPct.textContent='P95 '+fmtKpiDur(pf(95))+' · P99 '+fmtKpiDur(pf(99));}else{elPerfPct.textContent='—';}}var slowList=document.getElementById('kpi-perf-slow');if(slowList){var pairs=Object.keys(slow).map(function(k){return{name:k,sec:slow[k]};}).sort(function(x,y){return y.sec-x.sec;}).slice(0,12);slowList.innerHTML=pairs.length?pairs.map(function(p){return'<li><span class=\"kpi-perf-slow-name\">'+escHtml(p.name)+'</span><span class=\"kpi-perf-slow-dur\">'+escHtml(fmtKpiDur(p.sec))+'</span></li>';}).join(''):'<li class=\"kpi-perf-slow-empty\">No timing data yet.</li>';}if(typeof Chart!=='undefined'&&typeof __mervKpiSuiteDurChartConfig==='function'&&typeof __mervKpiPerfSlowChartConfig==='function'){var cSu=document.getElementById('kpiPerfSuiteDurChart'),cSl=document.getElementById('kpiPerfSlowChart');var rev=(snaps||[]).slice().reverse(),lab=[],durR=[];rev.forEach(function(d){var tcc=(d&&d.testSuite&&d.testSuite.testCases)||[],su=0;tcc.forEach(function(r){var a=parseTs(r.startTime),b=parseTs(r.endTime);if(a&&b&&b.getTime()>=a.getTime())su+=(b.getTime()-a.getTime())/1000;});lab.push((d&&d.__mervFolder)?decodeUi(String(d.__mervFolder)):'\\u2014');durR.push(Math.round(10*su)/10);});if(cSu){if(!lab.length){if(kpiPerfSuite){kpiPerfSuite.destroy();kpiPerfSuite=null;}}else if(!kpiPerfSuite){kpiPerfSuite=new Chart(cSu,__mervKpiSuiteDurChartConfig(lab,durR,fmtKpiDur));}else{kpiPerfSuite.data.labels=lab;kpiPerfSuite.data.datasets[0].data=durR;kpiPerfSuite.update('none');}}if(cSl){var slowPairs=Object.keys(slow).map(function(k){return{name:k,sec:slow[k]};}).sort(function(x,y){return y.sec-x.sec;}).slice(0,8);var labS=slowPairs.map(function(x){var n=String(x.name||'');return n.length>44?n.slice(0,42)+'\\u2026':n;});var datS=slowPairs.map(function(x){return Math.round(10*x.sec)/10;});if(!datS.length){if(kpiPerfSlow){kpiPerfSlow.destroy();kpiPerfSlow=null;}}else if(!kpiPerfSlow){kpiPerfSlow=new Chart(cSl,__mervKpiPerfSlowChartConfig(labS,datS,fmtKpiDur));}else{kpiPerfSlow.data.labels=labS;kpiPerfSlow.data.datasets[0].data=datS;kpiPerfSlow.update('none');}}}}var STALE_MS=");
        html.append(MervReportBranding.LOCAL_RUN_STALE_AFTER_MS);
        html.append(";var MINUTE=60000,DAY=86400000,POLL=5000;var execBuckets={};var chart=null;var liveEl=document.getElementById('chart-live');var titleEl=document.getElementById('chart-title');var noteEl=document.getElementById('chart-note');var consBody=document.getElementById('consolidated-body');var consSearch=document.getElementById('consolidated-search');var consFailRoot=document.getElementById('cons-fail-root');var consFailExpanded={};var customWrap=document.getElementById('chart-custom-wrap');var customFrom=document.getElementById('chart-custom-from');var customTo=document.getElementById('chart-custom-to');var customApply=document.getElementById('chart-custom-apply');var rangeState={key:'1h',customStart:0,customEnd:0};var expandedCases={};var consolidatedRows=[];var consPanel=document.getElementById('view-consolidated');if(consPanel){consPanel.addEventListener('click',function(ev){var tg=ev.target&&ev.target.closest?ev.target.closest('.cons-tag'):null;if(tg){var tv=(tg.getAttribute('data-tag')||'').trim();if(consSearch&&tv){consSearch.value=tv;setConsSubView('testcase');applyConsolidatedSearch();}return;}var fh=ev.target&&ev.target.closest?ev.target.closest('.cons-fail-head[data-frk]'):null;if(fh){var key=fh.getAttribute('data-frk')||'';if(!key)return;consFailExpanded[key]=!consFailExpanded[key];renderFailureSummary(window.__mervLiveFailureSummary||window.__mervFailureSummaryCache||null);return;}var t=ev.target&&ev.target.closest?ev.target.closest('.cons-toggle'):null;if(!t)return;var k=t.getAttribute('data-testcase-key')||'';if(!k)return;expandedCases[k]=!expandedCases[k];renderConsolidated(consolidatedRows);});}document.querySelectorAll('.cons-subtab').forEach(function(btn){btn.addEventListener('click',function(){var sub=this.getAttribute('data-cons-sub')||'testcase';setConsSubView(sub);});});if(consSearch){consSearch.addEventListener('input',applyConsolidatedSearch);}function setRangeUi(key){document.querySelectorAll('.chart-range-btn').forEach(function(btn){var k=btn.getAttribute('data-range');var on=(k===key);btn.classList.toggle('active',on);btn.setAttribute('aria-pressed',on?'true':'false');});}(function bindRangeTags(){document.querySelectorAll('.chart-range-btn').forEach(function(btn){btn.addEventListener('click',function(){var v=this.getAttribute('data-range');if(v==='custom'){if(customWrap)customWrap.classList.add('visible');if(customFrom&&!customFrom.value){var n=Date.now();if(customTo)customTo.value=msToLocal(n);if(customFrom)customFrom.value=msToLocal(n-DAY);}setRangeUi('custom');return;}if(customWrap)customWrap.classList.remove('visible');rangeState.key=v;setRangeUi(v);pollAll();});});})();function msToLocal(ms){var d=new Date(ms);function p(n){return n<10?'0'+n:''+n;}return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+'T'+p(d.getHours())+':'+p(d.getMinutes());}function parseTs(v){if(v==null||v===undefined)return null;if(typeof v==='number'){var n=v;return new Date(n>1e11?n:n*1000);}if(typeof v==='string'){var s=new Date(v);return isNaN(s.getTime())?null:s;}if(typeof v==='object'&&v){if(typeof v.time==='number')return new Date(v.time);if(Array.isArray(v)&&v.length>=3)return new Date(v[0],(v[1]||1)-1,v[2]||1,v[3]||0,v[4]||0,v[5]||0);}var t=new Date(v);return isNaN(t.getTime())?null:t;}function fmtTs(ms){if(!ms||ms<=0)return'—';var d=new Date(ms);if(isNaN(d.getTime()))return'—';var mon=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];var dd=(d.getDate()<10?'0':'')+d.getDate();var h=d.getHours(),amp=h>=12?'PM':'AM';h=h%12;if(h===0)h=12;var mm=(d.getMinutes()<10?'0':'')+d.getMinutes();return dd+'-'+mon[d.getMonth()]+'-'+d.getFullYear()+', '+h+':'+mm+amp;}function decodeUi(s){if(s==null)return'';var x=String(s);try{return decodeURIComponent(x);}catch(e){return x;}}function escHtml(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}function statusCls(st){var x=String(st||'').toLowerCase();if(x==='passed'||x==='failed'||x==='skipped'||x==='in_progress')return x;return'in_progress';}function isFailureSummaryTabActive(){var bf=document.getElementById('cons-tab-failures');return !!(bf&&bf.classList.contains('active'));}function normalizeKey(s){return String(s||'').trim();}function computeLiveFailureSummary(snaps){var byName={};(snaps||[]).forEach(function(d){var tc=(d&&d.testSuite&&d.testSuite.testCases)||[];var folder=(d&&d.__mervFolder)||'';var exp=parseTs(d&&d.exportDate);var baseTs=exp?exp.getTime():0;tc.forEach(function(row){var nm=String((row&&row.testcaseName)||'').trim();if(!nm)return;var st=String((row&&row.status)||'').toUpperCase();var rs=String((row&&row.failureReason)||'').trim();var et=parseTs(row&&row.endTime)||parseTs(row&&row.startTime);var tms=et?et.getTime():baseTs;var prev=byName[nm];if(!prev||tms>=prev.ts){byName[nm]={name:nm,status:st,reason:rs,runFolder:folder,ts:tms};}});});var grouped={};Object.keys(byName).forEach(function(k){var c=byName[k];if(!c||c.status!=='FAILED')return;var r=(c.reason||'').trim();if(!r)r='(No failure message)';if(!grouped[r])grouped[r]=[];grouped[r].push({testcaseName:c.name,runFolder:c.runFolder,runTimestampMillis:c.ts});});var reasons=[];Object.keys(grouped).sort(function(a,b){return grouped[b].length-grouped[a].length;}).forEach(function(r){reasons.push({reason:r,count:grouped[r].length,testcases:grouped[r]});});return{generatedAtMillis:Date.now(),failureReasons:reasons};}async function loadFailureSummary(){if(!consFailRoot)return;var live=window.__mervLiveFailureSummary||null;if(live&&live.failureReasons&&live.failureReasons.length){renderFailureSummary(live);return;}var ts='?ts='+Date.now();var paths=['./consolidated-failure-reasons.json'+ts,'../consolidated-failure-reasons.json'+ts];for(var i=0;i<paths.length;i++){try{var r=await fetch(paths[i],{cache:'no-store'});if(!r.ok)continue;var d=await r.json();window.__mervFailureSummaryCache=d;renderFailureSummary(d);return;}catch(e){}}renderFailureSummary(null);}function renderFailureSummary(d){if(!consFailRoot)return;var arr=(d&&d.failureReasons)||[];if(!arr.length){consFailRoot.innerHTML='<p class=\"cons-fail-empty\">No failures in latest runs.</p>';return;}var h='';arr.forEach(function(gr,idx){var reason=String(gr.reason||'').trim();var cnt=+gr.count||0;var cases=(gr.testcases)||[];var key=normalizeKey(reason)||('r-'+idx);if(consFailExpanded[key]===undefined)consFailExpanded[key]=false;var open=!!consFailExpanded[key];h+='<div class=\"cons-fail-group'+(open?' open':'')+'\">';h+='<div class=\"cons-fail-head\" role=\"button\" tabindex=\"0\" data-frk=\"'+escHtml(key)+'\"><div class=\"cons-fail-reason\">'+escHtml(reason||'(No failure message)')+'</div><div class=\"cons-fail-count\">'+cnt+'</div></div>';h+='<div class=\"cons-fail-body\"><div class=\"cons-fail-cases\">';cases.forEach(function(ca){var nm=String((ca&&ca.testcaseName)||'').trim();var folder=String((ca&&ca.runFolder)||'').trim();var href=folder?('./'+encodeURIComponent(folder)+'/html/merv-report.html?testcase='+encodeURIComponent(nm)):'#';h+='<div class=\"cons-fail-case\">'+(nm?('<a href=\"'+escHtml(href)+'\">'+escHtml(nm)+'</a>'):'<span>—</span>')+'</div>';});h+='</div></div></div>';});consFailRoot.innerHTML=h;consFailRoot.querySelectorAll('.cons-fail-head[data-frk]').forEach(function(el){el.addEventListener('keydown',function(ev){if(ev.key==='Enter'||ev.key===' '){ev.preventDefault();var k=el.getAttribute('data-frk')||'';consFailExpanded[k]=!consFailExpanded[k];renderFailureSummary(window.__mervLiveFailureSummary||window.__mervFailureSummaryCache||null);}});});}function getWindow(now){if(rangeState.key==='custom')return{start:rangeState.customStart,end:Math.min(now,rangeState.customEnd),live:false};var ms=0;if(rangeState.key==='1h')ms=60*MINUTE;else if(rangeState.key==='6h')ms=6*60*MINUTE;else if(rangeState.key==='1d')ms=24*60*MINUTE;else if(rangeState.key==='1w')ms=7*DAY;else if(rangeState.key==='2w')ms=14*DAY;else if(rangeState.key==='1m')ms=30*DAY;else ms=60*MINUTE;return{start:now-ms,end:now,live:true};}function bucketMsForRange(key,rangeLen){if(key==='1h')return MINUTE;if(key==='6h')return 5*MINUTE;if(key==='1d')return 15*MINUTE;if(key==='1w'||key==='2w'||key==='1m')return DAY;if(rangeLen<=6*60*MINUTE)return 5*MINUTE;if(rangeLen<=2*DAY)return 15*MINUTE;return DAY;}function aggregateBuckets(snapshots,win,bucketMs){var out={},si,j,row,st,et,tms,bk,d,tc,start=win.start,end=win.end;for(si=0;si<snapshots.length;si++){d=snapshots[si];if(!d||!d.testSuite)continue;tc=d.testSuite.testCases||[];for(j=0;j<tc.length;j++){row=tc[j];st=String(row.status||'').toUpperCase();if(st!=='PASSED'&&st!=='FAILED')continue;et=parseTs(row.endTime);if(!et)continue;tms=et.getTime();if(tms<start||tms>end+MINUTE)continue;bk=Math.floor(tms/bucketMs)*bucketMs;if(!out[bk])out[bk]={pass:0,fail:0};if(st==='PASSED')out[bk].pass++;else out[bk].fail++;}}return out;}function formatTick(t,bucketMs){if(bucketMs>=DAY)return new Date(t).toLocaleDateString([],{month:'short',day:'numeric'});if(bucketMs>=3600000)return new Date(t).toLocaleString([],{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});return new Date(t).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});}function buildSeries(){var now=Date.now(),win=getWindow(now),rangeLen=Math.max(win.end-win.start,MINUTE),bucketMs=bucketMsForRange(rangeState.key,rangeLen),b0=Math.floor(win.start/bucketMs)*bucketMs,bLast=Math.floor(win.end/bucketMs)*bucketMs,labels=[],passA=[],failA=[],t;for(t=b0;t<=bLast;t+=bucketMs){labels.push(formatTick(t,bucketMs));var b=execBuckets[t]||{pass:0,fail:0};passA.push(b.pass);failA.push(b.fail);}return{labels:labels,passA:passA,failA:failA,bucketMs:bucketMs};}function noteHtml(bucketMs){var txt=bucketMs>=DAY?'calendar day':(bucketMs>=900000?'15-minute':(bucketMs>=300000?'5-minute':'minute'));return '<strong>Pass</strong> (green area) and <strong>fail</strong> (red line) &mdash; test cases <strong>finished per '+txt+'</strong> in the selected range, from <strong>all listed runs</strong>';}function syncChrome(s){if(titleEl){if(rangeState.key==='custom'&&rangeState.customStart>0&&rangeState.customEnd>0){var a=new Date(rangeState.customStart),b=new Date(rangeState.customEnd);titleEl.textContent='Test cases executed — '+a.toLocaleString()+' – '+b.toLocaleString();}else{var T={'1h':'Test cases executed — last 1 hour','6h':'Test cases executed — last 6 hours','1d':'Test cases executed — last 24 hours','1w':'Test cases executed — last 7 days','2w':'Test cases executed — last 14 days','1m':'Test cases executed — last 30 days'};titleEl.textContent=T[rangeState.key]||T['1h'];}}if(noteEl)noteEl.innerHTML=noteHtml(s.bucketMs);}function aggregateConsolidated(snapshots){var map={},si,j,d,tc,row,name,st,et,m,exp;for(si=0;si<snapshots.length;si++){d=snapshots[si];if(!d||!d.testSuite)continue;exp=parseTs(d.exportDate);m=exp?exp.getTime():0;tc=d.testSuite.testCases||[];for(j=0;j<tc.length;j++){row=tc[j]||{};name=String(row.testcaseName||'Unnamed testcase').trim()||'Unnamed testcase';st=String(row.status||'IN_PROGRESS').toUpperCase();et=parseTs(row.endTime)||parseTs(row.startTime);var tms=et?et.getTime():m;var suiteName=String((d.testSuite&&d.testSuite.title)||'Unnamed suite').trim()||'Unnamed suite';var folder=(d&&d.__mervFolder)||'';var htmlFile=(d&&d.running===true)?'merv-report-live.html':'merv-report.html';var testcaseHref=folder?(folder+'/html/'+htmlFile+'?testcase='+encodeURIComponent(name)):'';if(!map[name])map[name]={name:name,key:('tc-'+Object.keys(map).length),currentStatus:'IN_PROGRESS',lastAt:0,lastPassed:0,lastFailed:0,totalPass:0,totalFail:0,last5:[],latestHref:'',suiteRuns:[],tags:[]};if(tms>=map[name].lastAt){map[name].lastAt=tms;map[name].currentStatus=st;map[name].latestHref=testcaseHref;}map[name].last5.push({t:tms,s:st});map[name].suiteRuns.push({suite:suiteName,folder:folder,status:st,href:testcaseHref,t:tms});(row.tags||[]).forEach(function(tg){var tx=String(tg||'').trim();if(tx&&map[name].tags.indexOf(tx)<0)map[name].tags.push(tx);});if(st==='PASSED'){map[name].totalPass++;if(tms>map[name].lastPassed)map[name].lastPassed=tms;}else if(st==='FAILED'){map[name].totalFail++;if(tms>map[name].lastFailed)map[name].lastFailed=tms;}}}return Object.keys(map).map(function(k){var r=map[k];r.last5=(r.last5||[]).sort(function(a,b){return b.t-a.t;}).slice(0,5).map(function(x){var s=String(x.s||'').toUpperCase();if(s==='PASSED')return'P';if(s==='FAILED')return'F';if(s==='SKIPPED')return'S';return'I';}).join(' ');r.suiteRuns=(r.suiteRuns||[]).sort(function(a,b){return b.t-a.t;});if(expandedCases[r.key]===undefined)expandedCases[r.key]=false;return r;}).sort(function(a,b){return a.name.localeCompare(b.name);});}function setConsSubView(sub){var a=document.getElementById('cons-subpanel-testcase'),b=document.getElementById('cons-subpanel-tags'),c=document.getElementById('cons-subpanel-failures'),t1=document.getElementById('cons-tab-testcase'),t2=document.getElementById('cons-tab-tags'),t3=document.getElementById('cons-tab-failures');if(!a||!b||!c)return;var onTc=sub==='testcase';var onTags=sub==='tags';var onFail=sub==='failures';a.classList.toggle('active',onTc);b.classList.toggle('active',onTags);c.classList.toggle('active',onFail);if(t1){t1.classList.toggle('active',onTc);t1.setAttribute('aria-selected',onTc?'true':'false');}if(t2){t2.classList.toggle('active',onTags);t2.setAttribute('aria-selected',onTags?'true':'false');}if(t3){t3.classList.toggle('active',onFail);t3.setAttribute('aria-selected',onFail?'true':'false');}if(onFail)loadFailureSummary();if(typeof showIndexView==='function')showIndexView('consolidated');}window.setConsSubView=setConsSubView;function consRowsHtmlFor(r){var html='';var ex=!!expandedCases[r.key];var cls=statusCls(r.currentStatus);var tags=(r.tags||[]).map(function(tg){return '<button type=\"button\" class=\"cons-tag\" data-tag=\"'+escHtml(tg)+'\">'+escHtml(tg)+'</button>';}).join('');var tagBlock=tags?('<div class=\"cons-tags\">'+tags+'</div>'):'';var searchable=((r.name||'')+' '+(r.tags||[]).join(' ')).toLowerCase();html+='<tr class=\"cons-testcase-row\" data-kind=\"tc\" data-testcase-key=\"'+escHtml(r.key)+'\" data-name=\"'+escHtml(searchable)+'\">';html+='<td class=\"cons-name\"><div class=\"cons-suite-cell\"><button type=\"button\" class=\"cons-toggle '+(ex?'expanded':'')+'\" data-testcase-key=\"'+escHtml(r.key)+'\" title=\"Show suites for testcase\"><span class=\"arr\">▶</span></button>'+(r.latestHref?('<a class=\"cons-link\" href=\"'+escHtml(r.latestHref)+'\">'+escHtml(r.name)+'</a>'):escHtml(r.name))+'</div>'+tagBlock+'</td>';html+='<td><span class=\"cons-status '+cls+'\">'+escHtml(String(r.currentStatus||'IN_PROGRESS').replace(/_/g,' '))+'</span></td><td>'+escHtml(r.last5||'—')+'</td><td>'+escHtml(fmtTs(r.lastPassed))+'</td><td>'+escHtml(fmtTs(r.lastFailed))+'</td><td class=\"cons-num\">'+(r.totalPass||0)+'</td><td class=\"cons-num\">'+(r.totalFail||0)+'</td></tr>';if(ex){(r.suiteRuns||[]).forEach(function(sr){var scls=statusCls(sr.status);var sLabel=decodeUi(sr.suite);var fLabel=decodeUi(sr.folder);if(fLabel){sLabel+=' ('+fLabel+')';}html+='<tr class=\"cons-suite-detail-row\" data-kind=\"suite-detail\" data-parent-key=\"'+escHtml(r.key)+'\" data-name=\"'+escHtml((decodeUi(sr.suite)+' '+decodeUi(sr.folder)+' '+String(r.name||'')).toLowerCase())+'\"><td class=\"cons-suite-detail-name\">'+(sr.href?('<a class=\"cons-link\" href=\"'+escHtml(sr.href)+'\">'+escHtml(sLabel)+'</a>'):escHtml(sLabel))+'</td><td><span class=\"cons-status '+scls+'\">'+escHtml(String(sr.status||'IN_PROGRESS').replace(/_/g,' '))+'</span></td><td>—</td><td>—</td><td>—</td><td class=\"cons-num\">—</td><td class=\"cons-num\">—</td></tr>';});}return html;}function renderTagBasedReport(rows){var root=document.getElementById('consolidated-tag-root');if(!root)return;if(!rows||!rows.length){root.innerHTML='<p class=\"cons-tag-empty\">No testcase data available yet.</p>';return;}var UNTAG='\u2014 No tag \u2014',byTag={},tg,i,r,j;for(i=0;i<rows.length;i++){r=rows[i];var tgs=(r.tags||[]).map(function(x){return String(x||'').trim();}).filter(function(x){return x;});if(!tgs.length){if(!byTag[UNTAG])byTag[UNTAG]=[];byTag[UNTAG].push(r);}else{for(j=0;j<tgs.length;j++){tg=tgs[j];if(!byTag[tg])byTag[tg]=[];byTag[tg].push(r);}}}var keys=Object.keys(byTag).sort(function(a,b){if(a===UNTAG)return 1;if(b===UNTAG)return -1;return a.localeCompare(b);});var out='',k,secRows,m,ki;for(ki=0;ki<keys.length;ki++){k=keys[ki];secRows=byTag[k];out+='<section class=\"cons-tag-section\" aria-labelledby=\"cons-tag-h-'+ki+'\"><h3 id=\"cons-tag-h-'+ki+'\" class=\"cons-tag-heading\">'+escHtml(k)+' <span class=\"cons-tag-count\">('+secRows.length+')</span></h3>';out+='<div class=\"consolidated-wrap\"><table class=\"consolidated-table consolidated-tag-table\"><thead><tr><th>Testcase Name</th><th>Current Status (Last Run)</th><th>Last 5 Run Status</th><th>Last Passed Time</th><th>Last Failed Time</th><th>Total Pass</th><th>Total Fail</th></tr></thead><tbody>';for(m=0;m<secRows.length;m++){out+=consRowsHtmlFor(secRows[m]);}out+='</tbody></table></div></section>';}root.innerHTML=out;}function renderConsolidated(rows){if(!consBody)return;consolidatedRows=rows||[];if(!rows||!rows.length){consBody.innerHTML='<tr><td colspan=\"7\">No testcase data available yet.</td></tr>';renderTagBasedReport(rows);return;}var html='';rows.forEach(function(r){html+=consRowsHtmlFor(r);});consBody.innerHTML=html;applyConsolidatedSearch();renderTagBasedReport(rows);}function applyConsolidatedSearch(){if(!consBody)return;var q=(consSearch&&consSearch.value?consSearch.value:'').toLowerCase().trim();var cases=consBody.querySelectorAll('tr[data-kind=\"tc\"]');cases.forEach(function(cr){var key=cr.getAttribute('data-testcase-key')||'';var nm=cr.getAttribute('data-name')||'';var kids=consBody.querySelectorAll('tr[data-parent-key=\"'+key.replace(/\"/g,'')+'\"]');var caseMatch=!q||nm.indexOf(q)>=0;var hasKidMatch=false;kids.forEach(function(kr){var kn=kr.getAttribute('data-name')||'';if(!q||kn.indexOf(q)>=0)hasKidMatch=true;});var showCase=caseMatch||hasKidMatch||!q;cr.style.display=showCase?'':'none';kids.forEach(function(kr){if(!showCase){kr.style.display='none';return;}if(!expandedCases[key]){kr.style.display='none';return;}if(!q){kr.style.display='';return;}var kn=kr.getAttribute('data-name')||'';kr.style.display=(caseMatch||kn.indexOf(q)>=0)?'':'none';});});}function donutCss(p,f,k){var t=p+f+k;if(t<=0)return'background:#e9ecef;';var pEnd=360*p/t,fEnd=pEnd+360*f/t;return'background:conic-gradient(#28a745 0deg '+pEnd+'deg, #dc3545 '+pEnd+'deg '+fEnd+'deg, #ffc107 '+fEnd+'deg 360deg);';}function tally(d){var p=0,f=0,k=0,tc=(d&&d.testSuite&&d.testSuite.testCases)||[];tc.forEach(function(t){var s=String(t.status||'').toUpperCase();if(s==='PASSED')p++;else if(s==='FAILED')f++;else if(s==='SKIPPED')k++;});return{pass:p,fail:f,skip:k,total:tc.length};}function tagList(d){var seen={},out=[];(d&&d.testSuite&&d.testSuite.testCases||[]).forEach(function(t){(t.tags||[]).forEach(function(tg){var x=String(tg);if(x&&!seen[x]){seen[x]=1;out.push(x);}});});return out;}function lastActMs(d){var n=d&&d.lastActivityMillis;if(typeof n==='number'&&n>0)return n;var p=Date.parse(String((d&&d.exportDate)||''));return isNaN(p)?0:p;}function markAborted(card){var b=card.querySelector('.status-badge');if(!b)return;b.textContent='Aborted';b.className='status-badge abort';}function updateCard(card,d,enc){if(!card)return;var T=tally(d);var elT=card.querySelector('.cnt-total'),elP=card.querySelector('.cnt-pass'),elF=card.querySelector('.cnt-fail'),elK=card.querySelector('.cnt-skip'),seg=card.querySelector('.cnt-skip-seg');if(elT)elT.textContent=T.total;if(elP)elP.textContent=T.pass;if(elF)elF.textContent=T.fail;if(elK)elK.textContent=T.skip;if(seg)seg.style.display=T.skip>0?'inline':'none';var donut=card.querySelector('.suite-meta .donut');if(donut)donut.setAttribute('style',donutCss(T.pass,T.fail,T.skip));var tags=tagList(d),tagRow=card.querySelector('.suite-tags-block .tag-row');if(tagRow){if(!tags.length)tagRow.innerHTML='<span class=\"tag-pill tag-pill-empty\" style=\"opacity:.5\">—</span>';else tagRow.innerHTML=tags.map(function(t){return'<span class=\"tag-pill\">'+escHtml(t)+'</span>';}).join('');}var fn=card.getAttribute('data-folder-name')||'';var suiteTitleEl=card.querySelector('.suite-top .suite-name');var folderEl=card.querySelector('.suite-top .suite-folder');if(folderEl)folderEl.textContent=decodeUi(fn);if(suiteTitleEl&&d&&d.testSuite){var jst=d.testSuite.title;if(jst!=null&&String(jst)!=='')suiteTitleEl.textContent=decodeUi(String(jst));}var titleText=(suiteTitleEl&&suiteTitleEl.textContent)||'';card.setAttribute('data-q',(titleText+' '+fn+' '+tags.join(' ')).toLowerCase());var live=(d&&d.running===true);var htmlFile=live?'merv-report-live.html':'merv-report.html';var pathSlash=fn.split('\\\\').join('/');var copyBtn=card.querySelector('button[data-copy]');if(copyBtn)copyBtn.setAttribute('data-copy',pathSlash+'/html/'+htmlFile);var viewA=card.querySelector('a.btn-view');var extA=card.querySelector('a.icon-btn[target=\"_blank\"]');if(viewA&&enc)viewA.setAttribute('href',enc+'/html/'+htmlFile);if(extA&&enc)extA.setAttribute('href',enc+'/html/'+htmlFile);var badge=card.querySelector('.status-badge');if(badge){if(d&&d.running===false){badge.textContent='Completed';badge.className='status-badge done';}else if(d&&d.running===true){var la=lastActMs(d);if(la>0&&Date.now()-la>STALE_MS){markAborted(card);}else{badge.textContent='In progress';badge.className='status-badge run';}}}}function updateChartUi(){if(typeof Chart==='undefined')return;var s=buildSeries();syncChrome(s);var ctx=document.getElementById('suiteExecChart');if(!ctx)return;if(!chart){chart=new Chart(ctx,{type:'line',data:{labels:s.labels,datasets:[{label:'Pass',data:s.passA,borderColor:'#28a745',backgroundColor:'rgba(40,167,69,0.22)',fill:true,tension:0.28,pointRadius:3,pointBackgroundColor:'#28a745',borderWidth:2},{label:'Fail',data:s.failA,borderColor:'#dc3545',backgroundColor:'transparent',fill:false,tension:0.25,pointRadius:2,pointBackgroundColor:'#dc3545',borderWidth:2}]},options:{responsive:true,maintainAspectRatio:false,animation:{duration:350},interaction:{mode:'index',intersect:false},plugins:{legend:{position:'top',labels:{color:'#333',font:{size:12,weight:'600'}}}},scales:{x:{grid:{color:'#e9ecef'},ticks:{color:'#495057',maxRotation:0,maxTicksLimit:14},border:{color:'#dee2e6'}},y:{beginAtZero:true,grid:{color:'#e9ecef'},ticks:{color:'#495057',precision:0},border:{color:'#dee2e6'}}}}});}else{chart.data.labels=s.labels;chart.data.datasets[0].data=s.passA;chart.data.datasets[1].data=s.failA;chart.update('none');}}async function pollAll(){var snaps=[],i,r,d,card;for(i=0;i<folders.length;i++){try{r=await fetch(folders[i]+'/json/merv-report.json?ts='+Date.now());if(!r.ok)continue;d=await r.json();d.__mervFolder=folders[i];snaps.push(d);card=document.querySelector('.suite-card[data-card-idx=\"'+i+'\"]');updateCard(card,d,folders[i]);}catch(err){}}var now=Date.now(),win=getWindow(now),rangeLen=Math.max(win.end-win.start,MINUTE),bucketMs=bucketMsForRange(rangeState.key,rangeLen);execBuckets=aggregateBuckets(snaps,win,bucketMs);updateChartUi();renderConsolidated(aggregateConsolidated(snaps));window.__mervLiveFailureSummary=computeLiveFailureSummary(snaps);if(isFailureSummaryTabActive())renderFailureSummary(window.__mervLiveFailureSummary);lastSnaps=snaps;syncKpiTagOptions(snaps);var scoped=getKpiSnaps(snaps);var filtered=applyTagFilterToSnaps(scoped,kpiTagState.tag);updateKpiPanel(filtered);refreshKpiScopeStatus();if(liveEl)liveEl.textContent=(rangeState.key==='custom'?'Updated':'Live')+' · '+new Date().toLocaleTimeString();}if(customApply){customApply.addEventListener('click',function(){var fv=customFrom.value,tv=customTo.value;if(!fv||!tv){alert('Please choose both From and To.');return;}var a=new Date(fv),b=new Date(tv);if(isNaN(a.getTime())||isNaN(b.getTime())){alert('Invalid dates.');return;}if(a.getTime()>=b.getTime()){alert('From must be before To.');return;}rangeState.key='custom';rangeState.customStart=a.getTime();rangeState.customEnd=b.getTime();setRangeUi('custom');pollAll();});}(function bindKpiBuildScope(){var sel=document.getElementById('kpi-build-scope');var cust=document.getElementById('kpi-build-custom');var wrap=document.getElementById('kpi-build-custom-wrap');var applyBtn=document.getElementById('kpi-build-custom-apply');function syncCustomWrap(){if(!wrap)return;if(kpiScopeState.mode==='custom'){wrap.classList.add('visible');if(cust)cust.value=String(kpiScopeState.customN);}else wrap.classList.remove('visible');}function refreshKpiFromCache(){if(!lastSnaps)return;var scoped=getKpiSnaps(lastSnaps);var filtered=applyTagFilterToSnaps(scoped,kpiTagState.tag);updateKpiPanel(filtered);refreshKpiScopeStatus();}if(sel){sel.value=kpiScopeState.mode;syncCustomWrap();sel.addEventListener('change',function(){kpiScopeState.mode=sel.value||'20';if(kpiScopeState.mode==='custom'&&cust)kpiScopeState.customN=Math.max(1,Math.min(999,parseInt(cust.value,10)||20));syncCustomWrap();refreshKpiFromCache();});}if(cust){cust.addEventListener('change',function(){if(sel&&sel.value==='custom'){kpiScopeState.customN=Math.max(1,Math.min(999,parseInt(cust.value,10)||1));refreshKpiFromCache();}});}if(applyBtn){applyBtn.addEventListener('click',function(){if(sel&&sel.value==='custom'&&cust){kpiScopeState.customN=Math.max(1,Math.min(999,parseInt(cust.value,10)||1));refreshKpiFromCache();}});}})();(function(){var b=document.getElementById('kpi-perf-slow-toggle');var w=document.getElementById('kpi-perf-slow-wrap');if(!b||!w)return;b.addEventListener('click',function(){var open=b.getAttribute('aria-expanded')==='true';var next=!open;b.setAttribute('aria-expanded',next?'true':'false');w.classList.toggle('kpi-perf-slow-collapsed',!next);});})();pollAll();setInterval(pollAll,POLL);})();\n");
        html.append("(function autoReloadIndexWhenStale(){var PERIOD=8000;var baseline=null;function fp(html){var n=(html.match(/data-card-idx=/g)||[]).length;return n+'|'+html.length;}async function tick(){try{var u='./index.html?cb='+Date.now();var r=await fetch(u,{cache:'no-store',credentials:'same-origin'});if(!r.ok)return;var t=await r.text();var s=fp(t);if(baseline===null){baseline=s;return;}if(s!==baseline){location.reload();}}catch(e){}}setTimeout(tick,2500);setInterval(tick,PERIOD);})();\n");
        html.append("</script>\n</body></html>\n");

        FileUtils.writeFile(base + "index.html", html.toString());
        System.out.println("Reports index updated: " + base + "index.html");
    }

    /**
     * Chart.js configs for KPI perf canvases. Emitted as readable JS (text block) so nested braces in
     * {@code options} stay maintainable; the minified {@code liveDashboard} only calls
     * {@code new Chart(canvas, __mervKpiSuiteDurChartConfig(...))}.
     */
    private static String mervKpiPerfChartsHelperJs() {
        return """
                function __mervKpiSuiteDurChartConfig(lab,dur,fmtKpiDur){
                    return {
                        type:'bar',
                        data:{
                            labels:lab,
                            datasets:[{
                                label:'Suite duration (s)',
                                data:dur,
                                backgroundColor:'rgba(23,162,184,0.65)',
                                borderColor:'#138496',
                                borderWidth:1
                            }]
                        },
                        options:{
                            responsive:true,
                            maintainAspectRatio:false,
                            plugins:{
                                title:{display:true,text:'Suite execution time by run',font:{size:13,weight:'600'}},
                                tooltip:{
                                    callbacks:{
                                        label:function(ctx){
                                            var y=ctx.parsed.y!=null?ctx.parsed.y:ctx.raw;
                                            return fmtKpiDur(Number(y));
                                        }
                                    }
                                }
                            },
                            scales:{
                                x:{ticks:{maxRotation:45,minRotation:0}},
                                y:{beginAtZero:true,ticks:{callback:function(v){return v+' s';}}}
                            }
                        }
                    };
                }
                function __mervKpiPerfSlowChartConfig(labS,datS,fmtKpiDur){
                    return {
                        type:'bar',
                        data:{
                            labels:labS,
                            datasets:[{
                                label:'Duration (s)',
                                data:datS,
                                backgroundColor:'rgba(220,53,69,0.72)',
                                borderColor:'#b02a37',
                                borderWidth:1
                            }]
                        },
                        options:{
                            indexAxis:'y',
                            responsive:true,
                            maintainAspectRatio:false,
                            plugins:{
                                title:{display:true,text:'Slowest test cases (max per name)',font:{size:13,weight:'600'}},
                                tooltip:{
                                    callbacks:{
                                        label:function(ctx){
                                            var x=ctx.parsed.x!=null?ctx.parsed.x:ctx.raw;
                                            return fmtKpiDur(Number(x));
                                        }
                                    }
                                }
                            },
                            scales:{
                                x:{
                                    beginAtZero:true,
                                    ticks:{callback:function(v){return v+' s';}}
                                },
                                y:{ticks:{autoSkip:false}}
                            }
                        }
                    };
                }
                """;
    }

    private static long reportFolderSortKey(File folder, SimpleDateFormat folderTs, long fallbackMillis) {
        String name = folder.getName();
        String suffix = " Merv-Report";
        if (name.endsWith(suffix)) {
            try {
                String prefix = name.substring(0, name.length() - suffix.length());
                Date parsed = folderTs.parse(prefix);
                return parsed.getTime();
            } catch (Exception ignored) {
                // fall through
            }
        }
        return fallbackMillis;
    }

}
