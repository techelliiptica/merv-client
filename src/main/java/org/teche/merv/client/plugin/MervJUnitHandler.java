package org.teche.merv.client.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.teche.merv.client.MervClient;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.dto.StepType;
import org.teche.merv.client.dto.TestCaseRequest;
import org.teche.merv.client.dto.TestCaseResponse;
import org.teche.merv.client.dto.TestCaseStatus;
import org.teche.merv.client.dto.TestStepPatchRequest;
import org.teche.merv.client.dto.TestStepRequest;
import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.dto.TestSuitePatchRequest;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.report.html.MervConsolidatedFailureReasonsWriter;
import org.teche.merv.client.report.html.MervFailureTestJsonWriter;
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
import java.lang.reflect.Method;
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
public class MervJUnitHandler implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback,
        BeforeTestExecutionCallback, AfterTestExecutionCallback, TestWatcher, InvocationInterceptor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Object REPORT_LOCK = new Object();
    private static final ExtensionContext.Namespace MERV_STORE =
            ExtensionContext.Namespace.create(MervJUnitHandler.class);
    private static final String STORE_CASE_ID = "case.id";
    private static final String STORE_CASE_FINALIZED = "case.finalized";
    private static final String STORE_STEP_STARTED_PREFIX = "step.started.";

    // Per-thread state so MervPluginSteps can attach to the active testcase.
    private static final ThreadLocal<UUID> THREAD_LOCAL_CASE_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> THREAD_LOCAL_STEP_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<LocalTestStep>> THREAD_LOCAL_PENDING_CONFIG_STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<LocalTestStep>> THREAD_LOCAL_CUSTOM_STEPS =
            ThreadLocal.withInitial(ArrayList::new);

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

    // Server-mode maps
    private final ConcurrentMap<String, UUID> serverCaseByUniqueId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> serverStepByStepKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> serverCaseFinalized = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> serverCaseHasCustomFailure = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> localCaseHasCustomFailure = new ConcurrentHashMap<>();

    private enum InvocationKind {
        BEFORE_EACH,
        AFTER_EACH,
        TEST_METHOD
    }

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
        if (context == null) {
            return;
        }
        ExtensionContext testContext = resolveTestContext(context);
        if (isMervEnabled()) {
            ensureServerCaseForContext(testContext);
            UUID caseId = resolveServerCaseId(testContext);
            if (caseId != null) {
                THREAD_LOCAL_CASE_ID.set(caseId);
            }
            bindSharedStepApis();
            return;
        }

        LocalTestCase localCase = ensureLocalCaseForContext(testContext);
        if (localCase != null && localCase.getId() != null) {
            THREAD_LOCAL_CASE_ID.set(localCase.getId());
            bindSharedStepApis();
        }
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        if (context == null) {
            return;
        }
        ExtensionContext testContext = resolveTestContext(context);
        if (testContext.getTestMethod().isEmpty()) {
            return;
        }
        Method testMethod = testContext.getRequiredTestMethod();
        if (isMervEnabled()) {
            ensureServerCaseForContext(testContext);
            UUID caseId = resolveServerCaseId(testContext);
            if (caseId != null) {
                THREAD_LOCAL_CASE_ID.set(caseId);
            }
            bindSharedStepApis();
            if (!isInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod)) {
                beginServerInvocationStep(testContext, InvocationKind.TEST_METHOD, testMethod);
                markInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod);
            }
            return;
        }
        LocalTestCase localCase = ensureLocalCaseForContext(testContext);
        if (localCase == null) {
            return;
        }
        THREAD_LOCAL_CASE_ID.set(localCase.getId());
        bindSharedStepApis();
        drainPendingConfigStepsInto(localCase);
        if (!isInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod)) {
            beginLocalTestInvocationStep(localCase, testMethod);
            markInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod);
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (context == null) {
            return;
        }
        ExtensionContext testContext = resolveTestContext(context);
        if (testContext.getTestMethod().isEmpty()) {
            return;
        }
        Method testMethod = testContext.getRequiredTestMethod();
        Throwable failure = context.getExecutionException().orElse(null);
        if (isMervEnabled()) {
            if (isInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod)) {
                finishServerInvocationStep(testContext, InvocationKind.TEST_METHOD, testMethod, failure);
                clearInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod);
            } else {
                recordFallbackServerTestStep(testContext, testMethod, failure);
            }
            return;
        }
        LocalTestCase localCase = ensureLocalCaseForContext(testContext);
        if (localCase == null) {
            return;
        }
        if (isInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod)) {
            drainCustomStepsInto(localCase);
            finishLocalTestInvocationStep(localCase, failure);
            clearInvocationStepStarted(testContext, InvocationKind.TEST_METHOD, testMethod);
        } else {
            recordFallbackLocalTestStep(localCase, testMethod, failure);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        MervPluginSteps.clear();
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        interceptLifecycleInvocation(InvocationKind.BEFORE_EACH, invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        interceptLifecycleInvocation(InvocationKind.AFTER_EACH, invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        // Test body steps are recorded via BeforeTestExecutionCallback / AfterTestExecutionCallback
        // (InvocationInterceptor is not invoked reliably in all JUnit 5 setups).
        invocation.proceed();
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        invocation.proceed();
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
                MervFailureTestJsonWriter.writeFromJsonReport(currentReportFolderPath, jsonReport);
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
            org.teche.merv.client.report.html.MervLocalReportZipWriter.writeUploadZipIfEnabled(
                    new File(currentReportFolderPath));
        } catch (Exception e) {
            System.err.println("Error generating JUnit5 local reports: " + e.getMessage());
        }
    }

    private LocalTestCase ensureLocalCaseForContext(ExtensionContext context) {
        if (context == null || localTestSuite == null) {
            return null;
        }
        ExtensionContext testContext = resolveTestContext(context);
        UUID existingId = testContext.getStore(MERV_STORE).get(STORE_CASE_ID, UUID.class);
        if (existingId != null) {
            LocalTestCase existing = localCases.get(existingId);
            if (existing != null) {
                return existing;
            }
        }
        String uid = testContext.getUniqueId();
        UUID mapped = localCaseIdByUniqueId.get(uid);
        if (mapped != null) {
            LocalTestCase existing = localCases.get(mapped);
            if (existing != null) {
                testContext.getStore(MERV_STORE).put(STORE_CASE_ID, mapped);
                return existing;
            }
        }

        LocalTestCase localCase = new LocalTestCase();
        UUID id = UUID.randomUUID();
        localCase.setId(id);
        localCase.setTestcaseName(resolveCaseName(testContext));
        localCase.setTags(new ArrayList<>());
        localCase.setStatus("IN_PROGRESS");
        localCase.setStartTime(new Date());
        localCase.setExecutionMachine(resolveExecutionMachine());
        localCase.setTestSteps(new ArrayList<>());
        localCase.setTestManagementId(new ArrayList<>());

        localCases.put(id, localCase);
        localCaseIdByUniqueId.put(uid, id);
        testContext.getStore(MERV_STORE).put(STORE_CASE_ID, id);
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
        ExtensionContext testContext = resolveTestContext(context);
        if (testContext == null) {
            return;
        }
        if (isMervEnabled()) {
            finalizeServerCase(testContext, status, failureReason);
            THREAD_LOCAL_CASE_ID.remove();
            return;
        }
        LocalTestCase localCase = ensureLocalCaseForContext(testContext);
        if (localCase == null) {
            return;
        }
        if (Boolean.TRUE.equals(localCaseHasCustomFailure.get(localCase.getId()))) {
            status = "FAILED";
        }
        localCase.setEndTime(new Date());
        localCase.setStatus(status);
        if (failureReason != null && !failureReason.isBlank()) {
            localCase.setFailureReason(failureReason);
        }
        persistLocalRuntimeSnapshot(false);
        THREAD_LOCAL_CASE_ID.remove();
        MervPluginSteps.clear();
    }

    private void interceptLifecycleInvocation(InvocationKind kind,
                                              Invocation<Void> invocation,
                                              ReflectiveInvocationContext<Method> invocationContext,
                                              ExtensionContext extensionContext) throws Throwable {
        if (extensionContext == null || invocationContext == null || invocationContext.getExecutable() == null) {
            invocation.proceed();
            return;
        }
        ExtensionContext testContext = resolveTestContext(extensionContext);
        if (testContext == null || testContext.getTestMethod().isEmpty()) {
            invocation.proceed();
            return;
        }
        if (kind == InvocationKind.TEST_METHOD) {
            invocation.proceed();
            return;
        }
        Method method = invocationContext.getExecutable();
        if (isMervEnabled()) {
            ensureServerCaseForContext(testContext);
            UUID caseId = resolveServerCaseId(testContext);
            if (caseId != null) {
                THREAD_LOCAL_CASE_ID.set(caseId);
            }
            if (!isInvocationStepStarted(testContext, kind, method)) {
                beginServerInvocationStep(testContext, kind, method);
                markInvocationStepStarted(testContext, kind, method);
            }
        }

        Throwable failure = null;
        try {
            invocation.proceed();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            if (isMervEnabled()) {
                if (isInvocationStepStarted(testContext, kind, method)) {
                    finishServerInvocationStep(testContext, kind, method, failure);
                    clearInvocationStepStarted(testContext, kind, method);
                }
            } else {
                LocalTestStep hookStep = buildCompletedHookStep(kind, method, failure);
                LocalTestCase localCase = ensureLocalCaseForContext(testContext);
                if (localCase != null) {
                    localCase.getTestSteps().add(hookStep);
                } else {
                    THREAD_LOCAL_PENDING_CONFIG_STEPS.get().add(hookStep);
                }
                persistLocalRuntimeSnapshot(false);
            }
        }
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
        return org.teche.merv.client.utils.MervSuiteBootstrap.resolveSuiteId(cli, mervProp, "JUnit5 Suite");
    }

    private void finishServerMode() {
        if (client == null || suiteId == null) {
            return;
        }
        finalizeRemainingServerCases();
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
        serverStepByStepKey.clear();
        serverCaseFinalized.clear();
        serverCaseHasCustomFailure.clear();
    }

    private void ensureServerCaseForContext(ExtensionContext context) {
        if (context == null || client == null || suiteId == null) {
            return;
        }
        ExtensionContext testContext = resolveTestContext(context);
        UUID existingId = testContext.getStore(MERV_STORE).get(STORE_CASE_ID, UUID.class);
        if (existingId != null) {
            serverCaseByUniqueId.put(testContext.getUniqueId(), existingId);
            return;
        }
        String uid = testContext.getUniqueId();
        if (serverCaseByUniqueId.containsKey(uid)) {
            testContext.getStore(MERV_STORE).put(STORE_CASE_ID, serverCaseByUniqueId.get(uid));
            return;
        }
        try {
            TestCaseRequest req = new TestCaseRequest();
            req.setTestcaseName(resolveCaseName(testContext));
            req.setStatus(TestCaseStatus.INPROGRESS);
            req.setExecutionMachine(Collections.singletonList(resolveExecutionMachine()));
            req.setTestManagementId(new ArrayList<>());
            req.setTags(new ArrayList<>());
            req.setTestSuiteId(suiteId);
            TestCaseResponse created = client.createTestCase(req);
            if (created != null && created.getId() != null) {
                serverCaseByUniqueId.put(uid, created.getId());
                testContext.getStore(MERV_STORE).put(STORE_CASE_ID, created.getId());
                testContext.getStore(MERV_STORE).put(STORE_CASE_FINALIZED, Boolean.FALSE);
            }
        } catch (Exception e) {
            System.err.println("MERV JUnit5 create test case failed: " + e.getMessage());
        }
    }

    private UUID resolveServerCaseId(ExtensionContext testContext) {
        if (testContext == null) {
            return null;
        }
        UUID fromStore = testContext.getStore(MERV_STORE).get(STORE_CASE_ID, UUID.class);
        if (fromStore != null) {
            return fromStore;
        }
        return serverCaseByUniqueId.get(testContext.getUniqueId());
    }

    private void finalizeServerCase(ExtensionContext context, String status, String failureReason) {
        if (context == null || client == null) {
            return;
        }
        if (Boolean.TRUE.equals(context.getStore(MERV_STORE).get(STORE_CASE_FINALIZED))) {
            return;
        }
        UUID caseId = resolveServerCaseId(context);
        if (caseId == null) {
            return;
        }
        String effectiveStatus = Boolean.TRUE.equals(serverCaseHasCustomFailure.get(caseId)) ? "FAILED" : status;
        try {
            closeOpenServerSteps(caseId);
            client.finishTestCase(caseId);
            TestCaseStatus desired = toTestCaseStatus(effectiveStatus);
            if (desired != null) {
                client.updateTestCaseStatus(caseId, desired);
            }
            context.getStore(MERV_STORE).put(STORE_CASE_FINALIZED, Boolean.TRUE);
            serverCaseFinalized.put(caseId, true);
        } catch (MervClientException e) {
            try {
                TestCaseStatus desired = toTestCaseStatus(effectiveStatus);
                if (desired != null) {
                    client.updateTestCaseStatus(caseId, desired);
                }
                context.getStore(MERV_STORE).put(STORE_CASE_FINALIZED, Boolean.TRUE);
                serverCaseFinalized.put(caseId, true);
            } catch (MervClientException ignored) {
                // best effort
            }
        }
    }

    private void finalizeRemainingServerCases() {
        if (client == null) {
            return;
        }
        for (UUID caseId : serverCaseByUniqueId.values()) {
            if (caseId == null || Boolean.TRUE.equals(serverCaseFinalized.get(caseId))) {
                continue;
            }
            try {
                closeOpenServerSteps(caseId);
                client.finishTestCase(caseId);
                serverCaseFinalized.put(caseId, true);
            } catch (MervClientException ignored) {
                // best effort at suite end
            }
        }
    }

    private void closeOpenServerSteps(UUID caseId) {
        try {
            List<TestStepResponse> steps = client.getTestStepsByTestCase(caseId);
            if (steps == null) {
                return;
            }
            for (TestStepResponse step : steps) {
                if (step == null || step.getId() == null || step.getStatus() == null) {
                    continue;
                }
                String stepStatus = step.getStatus().trim().toUpperCase(Locale.ROOT);
                if ("IN_PROGRESS".equals(stepStatus) || "PENDING".equals(stepStatus)) {
                    TestStepPatchRequest patch = new TestStepPatchRequest();
                    patch.setStatus("SKIPPED");
                    client.patchTestStep(step.getId(), patch);
                }
            }
        } catch (MervClientException ignored) {
            // finishTestCase on API also closes orphan steps
        }
    }

    private void beginServerInvocationStep(ExtensionContext testContext, InvocationKind kind, Method method) {
        if (client == null || testContext == null) {
            return;
        }
        UUID caseId = resolveServerCaseId(testContext);
        if (caseId == null) {
            return;
        }
        String stepKey = invocationStepKey(testContext, kind, method);
        if (serverStepByStepKey.containsKey(stepKey)) {
            return;
        }
        try {
            TestStepRequest req = new TestStepRequest();
            req.setTeststepName(resolveInvocationStepName(kind, method));
            if (shouldUseInfoStepTypeAtCreate(kind)) {
                req.setStepType(StepType.INFORMATION.getApiValue());
            }
            req.setExpected("Expected execution");
            req.setActual("In progress");
            req.setStatus("IN_PROGRESS");
            req.setTestcaseId(caseId);
            TestStepResponse created = createServerTestStep(req, caseId);
            if (created != null && created.getId() != null) {
                serverStepByStepKey.put(stepKey, created.getId());
            }
        } catch (MervClientException ignored) {
            // best effort
        }
    }

    private void finishServerInvocationStep(ExtensionContext testContext, InvocationKind kind, Method method, Throwable failure) {
        if (client == null || testContext == null) {
            return;
        }
        String stepKey = invocationStepKey(testContext, kind, method);
        UUID stepId = serverStepByStepKey.remove(stepKey);
        if (stepId == null) {
            return;
        }
        UUID caseId = resolveServerCaseId(testContext);
        try {
            TestStepPatchRequest req = new TestStepPatchRequest();
            tryCaptureAutomationScreenshotServer(stepId);
            if (isBeforeOrAfterKind(kind)) {
                if (failure != null) {
                    req.setStepType(StepType.ASSERTION.getApiValue());
                } else {
                    req.setStepType(StepType.INFORMATION.getApiValue());
                }
            }
            if (failure != null) {
                req.setStatus("FAILED");
                req.setExpected("Expected execution");
                req.setActual(extractReadableErrorMessage(failure));
            } else {
                req.setStatus("PASSED");
                req.setExpected("Expected execution");
                req.setActual("Executed successfully");
            }
            patchServerTestStep(stepId, caseId, req);
        } catch (MervClientException ignored) {
            // best effort
        }
    }

    private TestStepResponse createServerTestStep(TestStepRequest req, UUID caseId) throws MervClientException {
        try {
            return client.createTestStep(req);
        } catch (MervClientException e) {
            if (!isTestCaseNotOpenForStepsError(e)) {
                throw e;
            }
            reopenServerCaseForMoreSteps(caseId);
            return client.createTestStep(req);
        }
    }

    private void patchServerTestStep(UUID stepId, UUID caseId, TestStepPatchRequest req) throws MervClientException {
        try {
            client.patchTestStep(stepId, req);
        } catch (MervClientException e) {
            if (!isTestCaseNotOpenForStepsError(e)) {
                throw e;
            }
            reopenServerCaseForMoreSteps(caseId);
            client.patchTestStep(stepId, req);
        }
    }

    private void reopenServerCaseForMoreSteps(UUID caseId) throws MervClientException {
        client.restartTestCase(caseId);
        serverCaseFinalized.remove(caseId);
    }

    private static boolean isTestCaseNotOpenForStepsError(MervClientException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("Test case must be in 'INPROGRESS' status");
    }

    private static TestCaseStatus toTestCaseStatus(String status) {
        if ("PASSED".equalsIgnoreCase(status)) {
            return TestCaseStatus.PASSED;
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return TestCaseStatus.FAILED;
        }
        if ("SKIPPED".equalsIgnoreCase(status)) {
            return TestCaseStatus.SKIPPED;
        }
        return null;
    }

    private void recordFallbackServerTestStep(ExtensionContext testContext, Method testMethod, Throwable failure) {
        ensureServerCaseForContext(testContext);
        UUID caseId = resolveServerCaseId(testContext);
        if (caseId == null) {
            return;
        }
        try {
            TestStepRequest req = new TestStepRequest();
            req.setTeststepName(resolveInvocationStepName(InvocationKind.TEST_METHOD, testMethod));
            req.setStepType(StepType.INFORMATION.getApiValue());
            req.setExpected("Expected execution");
            req.setActual(failure != null ? extractReadableErrorMessage(failure) : "Executed successfully");
            req.setStatus(failure != null ? "FAILED" : "PASSED");
            req.setTestcaseId(caseId);
            createServerTestStep(req, caseId);
        } catch (MervClientException ignored) {
            // best effort
        }
    }

    private void recordFallbackLocalTestStep(LocalTestCase localCase, Method testMethod, Throwable failure) {
        drainPendingConfigStepsInto(localCase);
        drainCustomStepsInto(localCase);
        LocalTestStep step = buildCompletedHookStep(InvocationKind.TEST_METHOD, testMethod, failure);
        step.setStepType(resolveInvocationStepType(InvocationKind.TEST_METHOD, failure));
        if (localCase.getTestSteps() == null) {
            localCase.setTestSteps(new ArrayList<>());
        }
        localCase.getTestSteps().add(step);
        persistLocalRuntimeSnapshot(false);
    }

    private void beginLocalTestInvocationStep(LocalTestCase localCase, Method method) {
        LocalTestStep step = new LocalTestStep();
        UUID stepId = UUID.randomUUID();
        step.setId(stepId);
        step.setStartTime(new Date());
        step.setStatus("IN_PROGRESS");
        step.setTeststepName(resolveInvocationStepName(InvocationKind.TEST_METHOD, method));
        step.setStepType(resolveInvocationStepType(InvocationKind.TEST_METHOD, null));
        if (localCase.getTestSteps() == null) {
            localCase.setTestSteps(new ArrayList<>());
        }
        localCase.getTestSteps().add(step);
        THREAD_LOCAL_STEP_ID.set(stepId);
        persistLocalRuntimeSnapshot(false);
    }

    private void finishLocalTestInvocationStep(LocalTestCase localCase, Throwable failure) {
        UUID stepId = THREAD_LOCAL_STEP_ID.get();
        THREAD_LOCAL_STEP_ID.remove();
        if (stepId == null) {
            return;
        }
        LocalTestStep step = findLocalStep(localCase, stepId);
        if (step == null) {
            return;
        }
        step.setEndTime(new Date());
        if (failure != null) {
            step.setStatus("FAILED");
            step.setErrorMessage(extractReadableErrorMessage(failure));
        } else {
            step.setStatus("PASSED");
        }
        tryCaptureAutomationScreenshotLocal(step);
        persistLocalRuntimeSnapshot(false);
    }

    private LocalTestStep buildCompletedHookStep(InvocationKind kind, Method method, Throwable failure) {
        LocalTestStep step = new LocalTestStep();
        step.setId(UUID.randomUUID());
        Date now = new Date();
        step.setStartTime(now);
        step.setEndTime(now);
        step.setTeststepName(resolveInvocationStepName(kind, method));
        step.setStepType(resolveInvocationStepType(kind, failure));
        if (failure != null) {
            step.setStatus("FAILED");
            step.setErrorMessage(extractReadableErrorMessage(failure));
            tryCaptureAutomationScreenshotLocal(step);
        } else {
            step.setStatus("PASSED");
            tryCaptureAutomationScreenshotLocal(step);
        }
        return step;
    }

    private static LocalTestStep findLocalStep(LocalTestCase localCase, UUID stepId) {
        if (localCase == null || localCase.getTestSteps() == null || stepId == null) {
            return null;
        }
        for (LocalTestStep step : localCase.getTestSteps()) {
            if (stepId.equals(step.getId())) {
                return step;
            }
        }
        return null;
    }

    private static void drainPendingConfigStepsInto(LocalTestCase localCase) {
        if (localCase == null) {
            return;
        }
        List<LocalTestStep> pending = THREAD_LOCAL_PENDING_CONFIG_STEPS.get();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        if (localCase.getTestSteps() == null) {
            localCase.setTestSteps(new ArrayList<>());
        }
        localCase.getTestSteps().addAll(pending);
        pending.clear();
    }

    private static void drainCustomStepsInto(LocalTestCase localCase) {
        if (localCase == null) {
            return;
        }
        List<LocalTestStep> custom = THREAD_LOCAL_CUSTOM_STEPS.get();
        if (custom == null || custom.isEmpty()) {
            return;
        }
        if (localCase.getTestSteps() == null) {
            localCase.setTestSteps(new ArrayList<>());
        }
        localCase.getTestSteps().addAll(custom);
        custom.clear();
    }

    private static boolean isBeforeOrAfterKind(InvocationKind kind) {
        return kind == InvocationKind.BEFORE_EACH || kind == InvocationKind.AFTER_EACH;
    }

    private static boolean shouldUseInfoStepTypeAtCreate(InvocationKind kind) {
        return kind == InvocationKind.TEST_METHOD || isBeforeOrAfterKind(kind);
    }

    private static String resolveInvocationStepType(InvocationKind kind, Throwable failure) {
        if (kind == InvocationKind.TEST_METHOD) {
            return StepType.INFORMATION.getValue();
        }
        if (isBeforeOrAfterKind(kind)) {
            return failure != null ? StepType.ASSERTION.getValue() : StepType.INFORMATION.getValue();
        }
        return "CONFIG_METHOD";
    }

    private static String resolveInvocationStepName(InvocationKind kind, Method method) {
        String qualified = method == null ? "unknown" : method.toString();
        return switch (kind) {
            case BEFORE_EACH -> "@BeforeEach: " + qualified;
            case AFTER_EACH -> "@AfterEach: " + qualified;
            case TEST_METHOD -> "Test Method: " + qualified;
        };
    }

    private static String invocationStepKey(ExtensionContext testContext, InvocationKind kind, Method method) {
        String methodPart = method == null ? "unknown" : method.getName() + "#" + method.hashCode();
        return testContext.getUniqueId() + "|" + kind.name() + "|" + methodPart;
    }

    private static String invocationStepStartedKey(InvocationKind kind, Method method) {
        String methodPart = method == null ? "unknown" : method.getName() + "#" + method.hashCode();
        return STORE_STEP_STARTED_PREFIX + kind.name() + "|" + methodPart;
    }

    private static boolean isInvocationStepStarted(ExtensionContext testContext, InvocationKind kind, Method method) {
        return Boolean.TRUE.equals(testContext.getStore(MERV_STORE).get(invocationStepStartedKey(kind, method)));
    }

    private static void markInvocationStepStarted(ExtensionContext testContext, InvocationKind kind, Method method) {
        testContext.getStore(MERV_STORE).put(invocationStepStartedKey(kind, method), Boolean.TRUE);
    }

    private static void clearInvocationStepStarted(ExtensionContext testContext, InvocationKind kind, Method method) {
        testContext.getStore(MERV_STORE).remove(invocationStepStartedKey(kind, method));
    }

    private ExtensionContext resolveTestContext(ExtensionContext context) {
        ExtensionContext current = context;
        while (current != null) {
            if (current.getTestMethod().isPresent()) {
                return current;
            }
            Optional<ExtensionContext> parent = current.getParent();
            if (parent.isEmpty()) {
                break;
            }
            current = parent.get();
        }
        return context;
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
                step.setStepType(payload.type.getValue());
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
                if ("FAILED".equalsIgnoreCase(payload.status)) {
                    localCase.setStatus("FAILED");
                    if (localCase.getFailureReason() == null || localCase.getFailureReason().isBlank()) {
                        localCase.setFailureReason(payload.errorMessage != null ? payload.errorMessage : "Validation/custom step failed");
                    }
                    localCaseHasCustomFailure.put(caseId, true);
                }
                persistLocalRuntimeSnapshot(false);
            }

            @Override
            public TestStepResponse addServerStep(MervPluginSteps.StepPayload payload) throws MervClientException {
                if (client == null) {
                    throw new MervClientException("MervClient is not initialized. Make sure JUnit5 handler is properly configured.");
                }
                UUID caseId = THREAD_LOCAL_CASE_ID.get();
                if (caseId == null) {
                    throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
                }
                TestStepRequest req = new TestStepRequest();
                req.setTeststepName(payload.name);
                req.setTestcaseId(caseId);
                req.setStepType(payload.type.getApiValue());
                req.setStatus(payload.status);
                if (payload.expected != null) {
                    req.setExpected(payload.expected);
                }
                if (payload.actual != null) {
                    req.setActual(payload.actual);
                }
                if (payload.testdata != null) {
                    req.setTestdata(payload.testdata);
                }
                if (payload.prereq != null) {
                    req.setPrereq(payload.prereq);
                }
                TestStepResponse created = createServerTestStep(req, caseId);
                if ("FAILED".equalsIgnoreCase(payload.status)) {
                    try {
                        client.updateTestCaseStatus(caseId, TestCaseStatus.FAILED);
                    } catch (MervClientException ignored) {
                        // best effort
                    }
                    serverCaseHasCustomFailure.put(caseId, true);
                }
                return created;
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

    private void tryCaptureAutomationScreenshotServer(UUID stepId) {
        if (!stepScreenshotCaptureEnabled || stepId == null || client == null) {
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
            client.uploadFile(stepId, shot, "Step screenshot");
        } catch (Exception e) {
            System.err.println("MERV JUnit5 screenshot upload failed: " + e.getMessage());
        } finally {
            if (!shot.delete()) {
                shot.deleteOnExit();
            }
        }
    }

    private void cleanupThreadLocals() {
        THREAD_LOCAL_CASE_ID.remove();
        THREAD_LOCAL_STEP_ID.remove();
        THREAD_LOCAL_PENDING_CONFIG_STEPS.remove();
        THREAD_LOCAL_CUSTOM_STEPS.remove();
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

