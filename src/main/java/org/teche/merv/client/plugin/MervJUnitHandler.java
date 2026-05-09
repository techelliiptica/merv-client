package org.teche.merv.client.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.teche.merv.client.MervClient;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.dto.TestCaseRequest;
import org.teche.merv.client.dto.TestCaseResponse;
import org.teche.merv.client.dto.TestCaseStatus;
import org.teche.merv.client.dto.TestSuitePatchRequest;
import org.teche.merv.client.dto.TestSuiteRequest;
import org.teche.merv.client.dto.TestSuiteResponse;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.report.html.MervConsolidatedFailureReasonsWriter;
import org.teche.merv.client.report.html.MervReportsIndexHtmlWriter;
import org.teche.merv.client.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JUnit 5 extension for Merv local reporting.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @ExtendWith(MervJUnitHandler.class)
 * class MyTests { ... }
 * }</pre>
 */
public class MervJUnitHandler implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, TestWatcher {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Object REPORT_LOCK = new Object();

    // Per-thread state so MervPluginSteps can attach to the active testcase.
    private static final ThreadLocal<UUID> THREAD_LOCAL_CASE_ID = new ThreadLocal<>();

    // Suite/run state
    private final Properties mervProp = new Properties();
    private volatile LocalTestSuite localTestSuite;
    private volatile String currentReportFolderPath;
    private volatile MervClient client;
    private volatile UUID suiteId;
    private volatile boolean stepScreenshotCaptureEnabled = false;

    // Map each JUnit UniqueId → local case id
    private final ConcurrentMap<String, UUID> localCaseIdByUniqueId = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, LocalTestCase> localCases = new ConcurrentHashMap<>();

    // Server-mode maps (best-effort)
    private final ConcurrentMap<String, UUID> serverCaseByUniqueId = new ConcurrentHashMap<>();

    // ---- Optional automation binding (same API surface as TestNG/Cucumber) ----
    private static final ThreadLocal<AutomationTool> THREAD_LOCAL_AUTOMATION_TOOL = new ThreadLocal<>();
    private static final ThreadLocal<Object> THREAD_LOCAL_AUTOMATION_DRIVER = new ThreadLocal<>();

    public static void setAutomationToolObject(AutomationTool automationToolName, Object driverObject) {
        if (automationToolName == null) {
            THREAD_LOCAL_AUTOMATION_TOOL.remove();
            THREAD_LOCAL_AUTOMATION_DRIVER.remove();
            return;
        }
        THREAD_LOCAL_AUTOMATION_TOOL.set(automationToolName);
        THREAD_LOCAL_AUTOMATION_DRIVER.set(driverObject);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        try {
            loadMervProperties();
            bindSharedStepApis();
            stepScreenshotCaptureEnabled = readScreenshotEnabledFromProperties(mervProp);

            if (isMervEnabled()) {
                initializeServerMode();
                return;
            }

            // Local mode
            localTestSuite = new LocalTestSuite();
            localTestSuite.setTitle(mervProp.getProperty("merv.regression_suite", "JUnit5 Execution Report"));
            localTestSuite.setStartTime(new Date());
            localTestSuite.setTestCases(new ArrayList<>());

            String baseReportPath = MervConfig.getReportFolder();
            File reportsDir = new File(baseReportPath);
            if (!reportsDir.exists()) {
                reportsDir.mkdirs();
            }
            String folderName = new SimpleDateFormat("dd-MM-yyyy HH-mm-ss").format(new Date()) + " Merv-Report";
            String reportFolderPath = baseReportPath + folderName + File.separator;
            File reportFolder = new File(reportFolderPath);
            if (!reportFolder.exists()) {
                reportFolder.mkdirs();
            }
            currentReportFolderPath = reportFolderPath;
            initializeLocalRuntimeReporting(reportFolderPath);
            refreshReportsIndexListing();
            System.out.println("MERV JUnit5 local report initialized: " + reportFolderPath);
        } catch (Exception e) {
            System.err.println("MERV JUnit5 handler startup error: " + e.getMessage());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        try {
            if (isMervEnabled()) {
                finishServerMode();
                return;
            }
            if (localTestSuite != null) {
                localTestSuite.setEndTime(new Date());
                persistLocalRuntimeSnapshot(true);
            }
            generateLocalReports();
        } finally {
            cleanupThreadLocals();
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (context == null || context.getUniqueId() == null) {
            return;
        }
        String uid = context.getUniqueId();
        if (isMervEnabled()) {
            ensureServerCaseForContext(context);
            UUID caseId = serverCaseByUniqueId.get(uid);
            if (caseId != null) {
                THREAD_LOCAL_CASE_ID.set(caseId);
            }
            bindSharedStepApis();
            return;
        }

        LocalTestCase localCase = ensureLocalCaseForContext(context);
        if (localCase != null && localCase.getId() != null) {
            THREAD_LOCAL_CASE_ID.set(localCase.getId());
            bindSharedStepApis();
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // Status is finalized in TestWatcher callbacks; we just clear thread local.
        THREAD_LOCAL_CASE_ID.remove();
        MervPluginSteps.clear();
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        completeCase(context, "PASSED", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        completeCase(context, "FAILED", extractReadableErrorMessage(cause));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        // Map aborted to SKIPPED for local dashboard consistency
        completeCase(context, "SKIPPED", extractReadableErrorMessage(cause));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        completeCase(context, "SKIPPED", reason.orElse(null));
    }

    // ---------------- Local mode ----------------

    private void initializeLocalRuntimeReporting(String reportFolderPath) {
        try {
            String jsonFolderPath = reportFolderPath + "json" + File.separator;
            String htmlFolderPath = reportFolderPath + "html" + File.separator;
            new File(jsonFolderPath).mkdirs();
            new File(htmlFolderPath).mkdirs();
            writeRunningHtmlSnapshots(reportFolderPath);
            persistLocalRuntimeSnapshot(false);
        } catch (Exception e) {
            System.err.println("Error initializing JUnit5 runtime report: " + e.getMessage());
        }
    }

    private void persistLocalRuntimeSnapshot(boolean completed) {
        if (currentReportFolderPath == null || localTestSuite == null) {
            return;
        }
        synchronized (REPORT_LOCK) {
            try {
                String jsonFolderPath = currentReportFolderPath + "json" + File.separator;
                File jsonFolder = new File(jsonFolderPath);
                if (!jsonFolder.exists()) {
                    jsonFolder.mkdirs();
                }
                Map<String, Object> jsonReport = new LinkedHashMap<>();
                jsonReport.put("testSuite", localTestSuite);
                jsonReport.put("exportDate", new Date().toString());
                jsonReport.put("version", "1.0");
                jsonReport.put("running", !completed);
                jsonReport.put("lastActivityMillis", System.currentTimeMillis());
                FileUtils.writeFile(jsonFolderPath + "merv-report.json", OBJECT_MAPPER.writeValueAsString(jsonReport));
                if (!completed) {
                    writeRunningHtmlSnapshots(currentReportFolderPath);
                }
            } catch (Exception e) {
                System.err.println("Error persisting JUnit5 runtime snapshot: " + e.getMessage());
            }
        }
    }

    private void writeRunningHtmlSnapshots(String reportFolderPath) {
        try {
            String htmlDir = reportFolderPath + "html" + File.separator;
            new File(htmlDir).mkdirs();
            // Reuse the existing live report shell (Cucumber handler owns it today).
            String content = MervCucumberHandler.buildLiveHtmlReportContent();
            FileUtils.writeFile(htmlDir + "merv-report-live.html", content);
            FileUtils.writeFile(htmlDir + "merv-live-report.html", content);
            FileUtils.writeFile(htmlDir + "merv-report.html", content);
        } catch (Exception e) {
            System.err.println("Error writing JUnit5 running HTML reports: " + e.getMessage());
        }
    }

    private void generateLocalReports() {
        if (localTestSuite == null || currentReportFolderPath == null) {
            return;
        }
        try {
            localTestSuite.setEndTime(new Date());
            String jsonFolderPath = currentReportFolderPath + "json" + File.separator;
            String htmlFolderPath = currentReportFolderPath + "html" + File.separator;
            new File(jsonFolderPath).mkdirs();
            new File(htmlFolderPath).mkdirs();

            String liveHtml = htmlFolderPath + "merv-report-live.html";
            String liveHtmlAlt = htmlFolderPath + "merv-live-report.html";
            String finalHtml = htmlFolderPath + "merv-report.html";
            if (new File(liveHtml).isFile()) {
                Files.copy(Paths.get(liveHtml), Paths.get(finalHtml), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(Paths.get(liveHtml), Paths.get(liveHtmlAlt), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (new File(liveHtmlAlt).isFile()) {
                Files.copy(Paths.get(liveHtmlAlt), Paths.get(finalHtml), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                String content = MervCucumberHandler.buildLiveHtmlReportContent();
                FileUtils.writeFile(finalHtml, content);
                FileUtils.writeFile(liveHtml, content);
                FileUtils.writeFile(liveHtmlAlt, content);
            }
            persistLocalRuntimeSnapshot(true);
            refreshReportsIndexListing();
        } catch (Exception e) {
            System.err.println("Error generating JUnit5 local reports: " + e.getMessage());
        }
    }

    private LocalTestCase ensureLocalCaseForContext(ExtensionContext context) {
        if (context == null || localTestSuite == null) {
            return null;
        }
        String uid = context.getUniqueId();
        UUID existing = localCaseIdByUniqueId.get(uid);
        if (existing != null) {
            return localCases.get(existing);
        }

        LocalTestCase localCase = new LocalTestCase();
        UUID id = UUID.randomUUID();
        localCase.setId(id);
        localCase.setTestcaseName(resolveCaseName(context));
        localCase.setTags(new ArrayList<>());
        localCase.setStatus("IN_PROGRESS");
        localCase.setStartTime(new Date());
        localCase.setExecutionMachine(resolveExecutionMachine());
        localCase.setTestSteps(new ArrayList<>());
        localCase.setTestManagementId(new ArrayList<>());

        localCases.put(id, localCase);
        localCaseIdByUniqueId.put(uid, id);
        synchronized (REPORT_LOCK) {
            localTestSuite.getTestCases().add(localCase);
        }
        persistLocalRuntimeSnapshot(false);
        return localCase;
    }

    private void completeCase(ExtensionContext context, String status, String failureReason) {
        if (context == null) {
            return;
        }
        if (isMervEnabled()) {
            completeServerCase(context, status);
            return;
        }
        LocalTestCase localCase = ensureLocalCaseForContext(context);
        if (localCase == null) {
            return;
        }
        localCase.setEndTime(new Date());
        localCase.setStatus(status);
        if (failureReason != null && !failureReason.isBlank()) {
            localCase.setFailureReason(failureReason);
        }
        persistLocalRuntimeSnapshot(false);
        MervPluginSteps.clear();
    }

    // ---------------- Server mode (best-effort parity with TestNG) ----------------

    private void initializeServerMode() {
        try {
            String apiKey = mervProp.getProperty("merv.api_key");
            String server = mervProp.getProperty("merv.server");
            String username = mervProp.getProperty("merv.username");
            String password = mervProp.getProperty("merv.password");

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                client = new MervClient(server, apiKey.trim(), true);
            } else if (username != null && password != null && !username.trim().isEmpty() && !username.trim().isEmpty()) {
                client = new MervClient(server, username, password);
            } else {
                throw new MervClientException("Either merv.api_key or (merv.username and merv.password) must be configured in merv.properties");
            }
            client.verifyConnection();
            suiteId = resolveOrCreateSuite(client);
            System.out.println("MERV JUnit5 server mode initialized. Suite: " + suiteId);
        } catch (Exception e) {
            System.err.println("MERV JUnit5 server mode initialization failed: " + e.getMessage());
        }
    }

    private UUID resolveOrCreateSuite(MervClient cli) throws MervClientException {
        String appendSuite = System.getenv("merv.append_suite") == null ? mervProp.getProperty("merv.append_suite") : System.getenv("merv.append_suite");
        String suiteAlias = System.getenv("merv.append_suite_alias") == null ? mervProp.getProperty("merv.append_suite_alias") : System.getenv("merv.append_suite_alias");
        if (appendSuite != null && !appendSuite.isBlank()) {
            return UUID.fromString(appendSuite.trim());
        }
        if (suiteAlias != null && !suiteAlias.isBlank()) {
            return cli.getTestSuiteIdByAlias(suiteAlias.trim());
        }
        TestSuiteRequest testSuite = new TestSuiteRequest();
        testSuite.setTitle(mervProp.getProperty("merv.regression_suite", "JUnit5 Suite"));
        String hierarchy = mervProp.getProperty("merv.parent_hierarchy");
        if (hierarchy != null && !hierarchy.isBlank()) {
            testSuite.setHierarchyId(UUID.fromString(hierarchy.trim()));
        }
        testSuite.setSprint(mervProp.getProperty("merv.sprint"));
        TestSuiteResponse response = cli.createTestSuite(testSuite);
        return response.getId();
    }

    private void finishServerMode() {
        if (client == null || suiteId == null) {
            return;
        }
        String parallelFlagRaw = mervProp.getProperty("merv.execution.parallel");
        boolean parallelFlag = parallelFlagRaw != null && Boolean.parseBoolean(parallelFlagRaw.toLowerCase(Locale.ROOT));
        if (!parallelFlag) {
            try {
                TestSuitePatchRequest req = new TestSuitePatchRequest();
                req.setSuiteStatus("COMPLETED");
                client.patchTestSuite(suiteId, req);
            } catch (Exception e) {
                System.err.println("MERV JUnit5 could not patch suite status: " + e.getMessage());
            }
        }
        client = null;
        suiteId = null;
        serverCaseByUniqueId.clear();
    }

    private void ensureServerCaseForContext(ExtensionContext context) {
        if (context == null || client == null || suiteId == null) {
            return;
        }
        String uid = context.getUniqueId();
        if (serverCaseByUniqueId.containsKey(uid)) {
            return;
        }
        try {
            TestCaseRequest req = new TestCaseRequest();
            req.setTestcaseName(resolveCaseName(context));
            req.setStatus(TestCaseStatus.INPROGRESS);
            req.setExecutionMachine(Collections.singletonList(resolveExecutionMachine()));
            req.setTestManagementId(new ArrayList<>());
            req.setTags(new ArrayList<>());
            req.setTestSuiteId(suiteId);
            TestCaseResponse created = client.createTestCase(req);
            if (created != null && created.getId() != null) {
                serverCaseByUniqueId.put(uid, created.getId());
            }
        } catch (Exception e) {
            System.err.println("MERV JUnit5 create test case failed: " + e.getMessage());
        }
    }

    private void completeServerCase(ExtensionContext context, String status) {
        if (context == null || client == null) {
            return;
        }
        ensureServerCaseForContext(context);
        UUID caseId = serverCaseByUniqueId.get(context.getUniqueId());
        if (caseId == null) {
            return;
        }
        try {
            if ("PASSED".equalsIgnoreCase(status)) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.PASSED);
            } else if ("FAILED".equalsIgnoreCase(status)) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.FAILED);
            } else if ("SKIPPED".equalsIgnoreCase(status)) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.SKIPPED);
            } else {
                client.updateTestCaseStatus(caseId, TestCaseStatus.INPROGRESS);
            }
        } catch (Exception e) {
            System.err.println("MERV JUnit5 patch test case failed: " + e.getMessage());
        }
    }

    // ---------------- Shared step API binding (MervPluginSteps) ----------------

    private void bindSharedStepApis() {
        final boolean localMode = !isMervEnabled();
        MervPluginSteps.bind(new MervPluginSteps.Adapter() {
            @Override
            public boolean isLocalMode() {
                return localMode;
            }

            @Override
            public void addLocalStep(MervPluginSteps.StepPayload payload) throws MervClientException {
                UUID caseId = THREAD_LOCAL_CASE_ID.get();
                if (caseId == null) {
                    throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
                }
                LocalTestCase localCase = localCases.get(caseId);
                if (localCase == null) {
                    throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
                }
                LocalTestStep step = new LocalTestStep();
                step.setId(UUID.randomUUID());
                step.setTeststepName(payload.name);
                step.setStepType(payload.type.getApiValue());
                step.setStatus(payload.status);
                step.setStartTime(new Date());
                step.setEndTime(new Date());
                step.setExpected(payload.expected);
                step.setActual(payload.actual);
                step.setTestdata(payload.testdata);
                step.setPrereq(payload.prereq);
                step.setErrorMessage(payload.errorMessage);
                tryCaptureAutomationScreenshotLocal(step);
                if (localCase.getTestSteps() == null) {
                    localCase.setTestSteps(new ArrayList<>());
                }
                localCase.getTestSteps().add(step);
                persistLocalRuntimeSnapshot(false);
            }

            @Override
            public org.teche.merv.client.dto.TestStepResponse addServerStep(MervPluginSteps.StepPayload payload) throws MervClientException {
                throw new MervClientException("JUnit5 server-mode steps are not implemented yet. Use local mode or TestNG/Cucumber for step streaming.");
            }
        });
    }

    // ---------------- Utilities ----------------

    private void loadMervProperties() throws Exception {
        String propertiesPath = System.getProperty("user.dir") + File.separator + "merv.properties";
        File propFile = new File(propertiesPath);
        if (!propFile.exists()) {
            throw new IllegalStateException("merv.properties file not available in project root.");
        }
        mervProp.load(new FileInputStream(propFile));
    }

    private boolean isMervEnabled() {
        if (mervProp.isEmpty()) {
            return false;
        }
        String local = mervProp.getProperty("merv.local");
        if (local == null) {
            return true;
        }
        return !Boolean.parseBoolean(local.trim());
    }

    private static void refreshReportsIndexListing() {
        try {
            String base = MervConfig.getReportFolder();
            if (base == null || base.trim().isEmpty()) {
                return;
            }
            MervReportsIndexHtmlWriter.write(base.trim());
            MervConsolidatedFailureReasonsWriter.write(base.trim());
        } catch (Exception e) {
            System.err.println("Could not update reports index: " + e.getMessage());
        }
    }

    private String resolveCaseName(ExtensionContext context) {
        String cls = context.getRequiredTestClass().getSimpleName();
        String method = context.getTestMethod().map(m -> m.getName()).orElse("unknownMethod");
        return cls + "." + method;
    }

    private String resolveExecutionMachine() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("HOSTNAME");
        }
        if (host == null || host.isBlank()) {
            host = System.getProperty("HOSTNAME");
        }
        if (host == null || host.isBlank()) {
            host = System.getProperty("COMPUTERNAME");
        }
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                // continue
            }
        }
        if (host == null || host.isBlank()) {
            host = "Unknown Machine (" + System.getProperty("user.name", "user") + ")";
        }
        return host;
    }

    private String extractReadableErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String msg = throwable.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        return throwable.getClass().getSimpleName();
    }

    private static boolean readScreenshotEnabledFromProperties(Properties p) {
        if (p == null) {
            return false;
        }
        String v = p.getProperty("merv.screenshot");
        if (v == null) {
            v = p.getProperty("screenshot");
        }
        if (v == null) {
            return false;
        }
        String normalized = v.trim().toLowerCase(Locale.ROOT);
        return "on".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized);
    }

    private void tryCaptureAutomationScreenshotLocal(LocalTestStep localStep) {
        if (!stepScreenshotCaptureEnabled || localStep == null) {
            return;
        }
        AutomationTool tool = THREAD_LOCAL_AUTOMATION_TOOL.get();
        Object drv = THREAD_LOCAL_AUTOMATION_DRIVER.get();
        if (tool == null || drv == null) {
            return;
        }
        File shot = AutomationScreenshotCapturer.captureToTempPng(tool, drv);
        if (shot == null || !shot.exists()) {
            return;
        }
        try {
            String rel = saveFileToReportFolder(shot);
            if (rel != null) {
                if (localStep.getScreenshots() == null) {
                    localStep.setScreenshots(new ArrayList<>());
                }
                localStep.getScreenshots().add(rel);
            }
        } catch (Exception e) {
            System.err.println("MERV JUnit5 screenshot save failed: " + e.getMessage());
        } finally {
            if (!shot.delete()) {
                shot.deleteOnExit();
            }
        }
    }

    private String saveFileToReportFolder(File sourceFile) throws Exception {
        if (sourceFile == null || currentReportFolderPath == null) {
            return null;
        }
        String screenshotFolderPath = currentReportFolderPath + "screenshots" + File.separator;
        File screenshotFolder = new File(screenshotFolderPath);
        if (!screenshotFolder.exists()) {
            screenshotFolder.mkdirs();
        }
        String targetName = "step_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".png";
        File target = new File(screenshotFolder, targetName);
        Files.copy(sourceFile.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return "screenshots/" + targetName;
    }

    private void cleanupThreadLocals() {
        THREAD_LOCAL_CASE_ID.remove();
        MervPluginSteps.clear();
        THREAD_LOCAL_AUTOMATION_TOOL.remove();
        THREAD_LOCAL_AUTOMATION_DRIVER.remove();
    }

    // ---------------- Local JSON DTOs (match LOCAL_REPORTS_CONTEXT contract) ----------------

    private static class LocalTestSuite {
        private String title;
        private Date startTime;
        private Date endTime;
        private List<LocalTestCase> testCases;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Date getStartTime() { return startTime; }
        public void setStartTime(Date startTime) { this.startTime = startTime; }
        public Date getEndTime() { return endTime; }
        public void setEndTime(Date endTime) { this.endTime = endTime; }
        public List<LocalTestCase> getTestCases() { return testCases; }
        public void setTestCases(List<LocalTestCase> testCases) { this.testCases = testCases; }
    }

    private static class LocalTestCase {
        private UUID id;
        private String testcaseName;
        private List<String> tags;
        private String status;
        private Date startTime;
        private Date endTime;
        private String failureReason;
        private List<String> testManagementId;
        private String executionMachine;
        private List<LocalTestStep> testSteps;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getTestcaseName() { return testcaseName; }
        public void setTestcaseName(String testcaseName) { this.testcaseName = testcaseName; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Date getStartTime() { return startTime; }
        public void setStartTime(Date startTime) { this.startTime = startTime; }
        public Date getEndTime() { return endTime; }
        public void setEndTime(Date endTime) { this.endTime = endTime; }
        public String getFailureReason() { return failureReason; }
        public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
        public List<String> getTestManagementId() { return testManagementId; }
        public void setTestManagementId(List<String> testManagementId) { this.testManagementId = testManagementId; }
        public String getExecutionMachine() { return executionMachine; }
        public void setExecutionMachine(String executionMachine) { this.executionMachine = executionMachine; }
        public List<LocalTestStep> getTestSteps() { return testSteps; }
        public void setTestSteps(List<LocalTestStep> testSteps) { this.testSteps = testSteps; }
    }

    private static class LocalTestStep {
        private UUID id;
        private String teststepName;
        private String status;
        private Date startTime;
        private Date endTime;
        private String errorMessage;
        private String stepType;
        private String expected;
        private String actual;
        private String testdata;
        private String prereq;
        private List<String> screenshots;
        private List<String> logs;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getTeststepName() { return teststepName; }
        public void setTeststepName(String teststepName) { this.teststepName = teststepName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Date getStartTime() { return startTime; }
        public void setStartTime(Date startTime) { this.startTime = startTime; }
        public Date getEndTime() { return endTime; }
        public void setEndTime(Date endTime) { this.endTime = endTime; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getStepType() { return stepType; }
        public void setStepType(String stepType) { this.stepType = stepType; }
        public String getExpected() { return expected; }
        public void setExpected(String expected) { this.expected = expected; }
        public String getActual() { return actual; }
        public void setActual(String actual) { this.actual = actual; }
        public String getTestdata() { return testdata; }
        public void setTestdata(String testdata) { this.testdata = testdata; }
        public String getPrereq() { return prereq; }
        public void setPrereq(String prereq) { this.prereq = prereq; }
        public List<String> getScreenshots() { return screenshots; }
        public void setScreenshots(List<String> screenshots) { this.screenshots = screenshots; }
        public List<String> getLogs() { return logs; }
        public void setLogs(List<String> logs) { this.logs = logs; }
    }
}

