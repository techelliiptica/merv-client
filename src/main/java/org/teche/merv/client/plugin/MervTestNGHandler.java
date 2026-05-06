package org.teche.merv.client.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.teche.merv.client.MervClient;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.dto.TestCaseRequest;
import org.teche.merv.client.dto.TestCaseResponse;
import org.teche.merv.client.dto.TestCaseStatus;
import org.teche.merv.client.dto.TestStepPatchRequest;
import org.teche.merv.client.dto.TestStepRequest;
import org.teche.merv.client.dto.TestStepResponse;
import org.teche.merv.client.dto.TestSuitePatchRequest;
import org.teche.merv.client.dto.TestSuiteRequest;
import org.teche.merv.client.dto.TestSuiteResponse;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.report.html.MervReportBranding;
import org.teche.merv.client.report.html.MervConsolidatedFailureReasonsWriter;
import org.teche.merv.client.report.html.MervReportsIndexHtmlWriter;
import org.teche.merv.client.utils.FileUtils;
import org.testng.IExecutionListener;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.ITestContext;

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
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TestNG listener for Merv local reporting.
 *
 * <p>Usage in {@code testng.xml}:
 * {@code <listener class-name="org.teche.merv.client.plugin.MervTestNGHandler" />}</p>
 */
public class MervTestNGHandler implements IExecutionListener, ITestListener, IInvokedMethodListener {
    private static final String ATTR_CASE_ID = "merv.case.id";
    private static final String ATTR_STEP_ID = "merv.step.id";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Object REPORT_LOCK = new Object();

    private static final ThreadLocal<AutomationTool> THREAD_LOCAL_AUTOMATION_TOOL = new ThreadLocal<>();
    private static final ThreadLocal<Object> THREAD_LOCAL_AUTOMATION_DRIVER = new ThreadLocal<>();
    private static final ThreadLocal<UUID> THREAD_LOCAL_STEP_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> THREAD_LOCAL_CASE_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<LocalTestStep>> THREAD_LOCAL_PENDING_CONFIG_STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<LocalTestStep>> THREAD_LOCAL_CUSTOM_STEPS =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Add a custom step to the current TestNG testcase (local report mode).
     * <p>
     * Call this from inside your test method (or from helpers called by the test).
     * Steps are attached to the active testcase on the next listener update.
     * </p>
     */
    public static void addCustomStep(String stepName) {
        addCustomStep(stepName, "PASSED", null);
    }

    /**
     * Add a custom step with explicit status.
     *
     * @param status One of PASSED / FAILED / SKIPPED / IN_PROGRESS
     */
    public static void addCustomStep(String stepName, String status, String errorMessage) {
        LocalTestStep step = new LocalTestStep();
        step.setId(UUID.randomUUID());
        step.setTeststepName(stepName == null ? "Custom Step" : stepName);
        step.setStepType("CUSTOM");
        step.setStatus(status == null ? "PASSED" : status);
        step.setErrorMessage(errorMessage);
        Date now = new Date();
        step.setStartTime(now);
        step.setEndTime(now);
        THREAD_LOCAL_CUSTOM_STEPS.get().add(step);
    }

    private final Properties mervProp = new Properties();
    private final ConcurrentMap<UUID, LocalTestCase> localTestCases = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> serverCaseByResultKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> localCaseByMethodKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> serverCaseByMethodKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> serverStepByMethodKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> localCaseHasCustomFailure = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> serverCaseHasCustomFailure = new ConcurrentHashMap<>();
    private volatile LocalTestSuite localTestSuite;
    private volatile String currentReportFolderPath;
    private volatile boolean stepScreenshotCaptureEnabled = false;
    private volatile MervClient client;
    private volatile UUID suiteId;

    /**
     * Registers the automation driver/page used for optional per-step screenshots when
     * {@code merv.screenshot=on} (or {@code screenshot=on}) is set in {@code merv.properties}.
     *
     * @param automationToolName identifies how {@code driverObject} should be interpreted
     * @param driverObject       Selenium {@code WebDriver}, Playwright {@code Page}, etc.
     */
    public static void setAutomationToolObject(AutomationTool automationToolName, Object driverObject) {
        if (automationToolName == null) {
            THREAD_LOCAL_AUTOMATION_TOOL.remove();
            THREAD_LOCAL_AUTOMATION_DRIVER.remove();
            return;
        }
        THREAD_LOCAL_AUTOMATION_TOOL.set(automationToolName);
        THREAD_LOCAL_AUTOMATION_DRIVER.set(driverObject);
    }

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
                LocalTestCase localCase = localTestCases.get(caseId);
                if (localCase == null) {
                    throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
                }
                LocalTestStep step = new LocalTestStep();
                step.setId(UUID.randomUUID());
                step.setTeststepName(payload.name);
                step.setStepType(payload.type.getApiValue());
                step.setStatus(payload.status);
                step.setStartTime(new Date());
                step.setExpected(payload.expected);
                step.setActual(payload.actual);
                step.setTestdata(payload.testdata);
                step.setPrereq(payload.prereq);
                step.setErrorMessage(payload.errorMessage);
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
                    throw new MervClientException("MervClient is not initialized. Make sure TestNG handler is properly configured.");
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
                if (payload.expected != null) req.setExpected(payload.expected);
                if (payload.actual != null) req.setActual(payload.actual);
                if (payload.testdata != null) req.setTestdata(payload.testdata);
                if (payload.prereq != null) req.setPrereq(payload.prereq);
                TestStepResponse created = client.createTestStep(req);
                if ("FAILED".equalsIgnoreCase(payload.status)) {
                    try {
                        client.updateTestCaseStatus(caseId, TestCaseStatus.FAILED);
                    } catch (Exception ignored) {
                        // best effort
                    }
                    serverCaseHasCustomFailure.put(caseId, true);
                }
                return created;
            }
        });
    }

    // ---- Shared step APIs (same as MervCucumberHandler via MervPluginSteps) ----
    public static TestStepResponse addStep(
            String stepName,
            String stepType,
            String expected,
            String actual,
            String testdata,
            String prereq) throws MervClientException {
        TestStepResponse res = MervPluginSteps.addStep(stepName, stepType, expected, actual, testdata, prereq);
        // In TestNG local mode we don't throw; step is queued/attached to local report.
        return res;
    }

    public static TestStepResponse addStep(String stepName, String stepType) throws MervClientException {
        return addStep(stepName, stepType, null, null, null, null);
    }

    public static TestStepResponse addDataStep(String stepName, String testdata) throws MervClientException {
        return MervPluginSteps.addDataStep(stepName, testdata);
    }

    public static TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) throws MervClientException {
        return MervPluginSteps.addValidationStep(stepName, expected, actual, testdata, prereq);
    }

    public static TestStepResponse addValidationStep(String stepName, String expected, String actual) throws MervClientException {
        return MervPluginSteps.addValidationStep(stepName, expected, actual);
    }

    public static TestStepResponse addValidationStep(String stepName) throws MervClientException {
        return MervPluginSteps.addValidationStep(stepName);
    }

    public static TestStepResponse info(String infoToAdd) throws MervClientException {
        return MervPluginSteps.info(infoToAdd);
    }

    @Override
    public void onExecutionStart() {
        try {
            String propertiesPath = System.getProperty("user.dir") + File.separator + "merv.properties";
            File propFile = new File(propertiesPath);
            if (!propFile.exists()) {
                throw new IllegalStateException("merv.properties file not available in project root.");
            }
            mervProp.load(new FileInputStream(propFile));
            stepScreenshotCaptureEnabled = readScreenshotEnabledFromProperties(mervProp);

            if (isMervEnabled()) {
                initializeServerMode();
                return;
            }

            localTestSuite = new LocalTestSuite();
            localTestSuite.setTitle(mervProp.getProperty("merv.regression_suite", "TestNG Execution Report"));
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
            System.out.println("MERV TestNG local report initialized: " + reportFolderPath);
        } catch (Exception e) {
            System.err.println("MERV TestNG handler startup error: " + e.getMessage());
        }
    }

    @Override
    public void onExecutionFinish() {
        try {
            if (isMervEnabled()) {
                finishServerMode();
                cleanupThreadLocals();
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
    public void onTestStart(ITestResult result) {
        if (result == null || result.getMethod() == null || !result.getMethod().isTest()) {
            return;
        }
        if (isMervEnabled()) {
            ensureServerCaseForResult(result);
            UUID caseId = serverCaseByResultKey.get(resultKey(result));
            if (caseId != null) {
                THREAD_LOCAL_CASE_ID.set(caseId);
            }
            bindSharedStepApis();
            return;
        }
        LocalTestCase localCase = ensureCaseForResult(result);
        if (localCase != null && localCase.getId() != null) {
            THREAD_LOCAL_CASE_ID.set(localCase.getId());
            bindSharedStepApis();
            List<LocalTestStep> pendingSteps = THREAD_LOCAL_PENDING_CONFIG_STEPS.get();
            if (pendingSteps != null && !pendingSteps.isEmpty()) {
                localCase.getTestSteps().addAll(pendingSteps);
                pendingSteps.clear();
                persistLocalRuntimeSnapshot(false);
            }
            drainCustomStepsInto(localCase);
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        if (result == null || result.getMethod() == null || !result.getMethod().isTest()) {
            return;
        }
        if (isMervEnabled()) {
            completeServerCase(result, "PASSED", null);
            MervPluginSteps.clear();
            return;
        }
        completeCase(result, "PASSED", null);
        MervPluginSteps.clear();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        if (result == null || result.getMethod() == null || !result.getMethod().isTest()) {
            return;
        }
        Throwable throwable = result == null ? null : result.getThrowable();
        String reason = throwable == null ? "Test failed" : extractReadableErrorMessage(throwable);
        if (isMervEnabled()) {
            completeServerCase(result, "FAILED", reason);
            MervPluginSteps.clear();
            return;
        }
        completeCase(result, "FAILED", reason);
        MervPluginSteps.clear();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        if (result == null || result.getMethod() == null || !result.getMethod().isTest()) {
            return;
        }
        if (isMervEnabled()) {
            completeServerCase(result, "SKIPPED", null);
            MervPluginSteps.clear();
            return;
        }
        completeCase(result, "SKIPPED", null);
        MervPluginSteps.clear();
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        onTestFailure(result);
    }

    @Override
    public void onStart(ITestContext context) {
        // no-op
    }

    @Override
    public void onFinish(ITestContext context) {
        // no-op
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
        if (method == null) {
            return;
        }
        if (isMervEnabled()) {
            ensureServerStepForInvocation(method, testResult);
            return;
        }
        if (!method.isTestMethod()) {
            return;
        }
        LocalTestCase localCase = localCaseForInvocation(method, testResult);
        if (localCase == null) {
            return;
        }
        LocalTestStep step = new LocalTestStep();
        UUID stepId = UUID.randomUUID();
        step.setId(stepId);
        step.setStartTime(new Date());
        step.setStatus("IN_PROGRESS");
        step.setTeststepName(resolveInvocationStepName(method));
        step.setStepType(method.isTestMethod() ? "TEST_METHOD" : "CONFIG_METHOD");
        localCase.getTestSteps().add(step);
        testResult.setAttribute(ATTR_STEP_ID, stepId.toString());
        THREAD_LOCAL_STEP_ID.set(stepId);
        persistLocalRuntimeSnapshot(false);
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
        if (method == null) {
            return;
        }
        if (isMervEnabled()) {
            finishServerStepForInvocation(method, testResult);
            if (!method.isTestMethod() && method.getTestMethod() != null && method.getTestMethod().isAfterMethodConfiguration()) {
                THREAD_LOCAL_CASE_ID.remove();
            }
            return;
        }
        if (!method.isTestMethod()) {
            LocalTestStep configStep = buildCompletedInvocationStep(method, testResult);
            LocalTestCase localCase = localCaseForInvocation(method, testResult);
            if (localCase != null) {
                localCase.getTestSteps().add(configStep);
            } else {
                THREAD_LOCAL_PENDING_CONFIG_STEPS.get().add(configStep);
            }
            persistLocalRuntimeSnapshot(false);
            if (method.getTestMethod() != null && method.getTestMethod().isAfterMethodConfiguration()) {
                THREAD_LOCAL_CASE_ID.remove();
            }
            return;
        }
        LocalTestCase localCase = localCaseForInvocation(method, testResult);
        if (localCase == null) {
            return;
        }

        drainCustomStepsInto(localCase);

        UUID stepId = readStepId(testResult);
        LocalTestStep step = stepId == null ? null : findStep(localCase, stepId);
        if (step != null) {
            step.setEndTime(new Date());
            if (testResult.getThrowable() != null) {
                step.setStatus("FAILED");
                step.setErrorMessage(extractReadableErrorMessage(testResult.getThrowable()));
            } else if (testResult.getStatus() == ITestResult.SKIP) {
                step.setStatus("SKIPPED");
            } else {
                step.setStatus("PASSED");
            }
            tryCaptureAutomationScreenshotLocal(step);
        }
        THREAD_LOCAL_STEP_ID.remove();
        persistLocalRuntimeSnapshot(false);
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

    private void initializeServerMode() {
        try {
            String apiKey = mervProp.getProperty("merv.api_key");
            String server = mervProp.getProperty("merv.server");
            String username = mervProp.getProperty("merv.username");
            String password = mervProp.getProperty("merv.password");

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                client = new MervClient(server, apiKey.trim(), true);
            } else if (username != null && password != null && !username.trim().isEmpty() && !password.trim().isEmpty()) {
                client = new MervClient(server, username, password);
            } else {
                throw new MervClientException("Either merv.api_key or (merv.username and merv.password) must be configured in merv.properties");
            }

            client.verifyConnection();
            suiteId = resolveOrCreateSuite(client);
            System.out.println("MERV TestNG server mode initialized. Suite: " + suiteId);
        } catch (Exception e) {
            System.err.println("MERV TestNG server mode initialization failed: " + e.getMessage());
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
        testSuite.setTitle(mervProp.getProperty("merv.regression_suite", "TestNG Suite"));
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
                System.err.println("MERV TestNG could not patch suite status: " + e.getMessage());
            }
        }
        client = null;
        suiteId = null;
        serverCaseByResultKey.clear();
        serverStepByMethodKey.clear();
    }

    private void ensureServerCaseForResult(ITestResult result) {
        if (result == null || client == null || suiteId == null) {
            return;
        }
        String key = resultKey(result);
        if (serverCaseByResultKey.containsKey(key)) {
            return;
        }
        try {
            TestCaseRequest req = new TestCaseRequest();
            req.setTestcaseName(resolveCaseName(result));
            req.setStatus(TestCaseStatus.INPROGRESS);
            req.setExecutionMachine(Collections.singletonList(resolveExecutionMachine()));
            req.setTestManagementId(new ArrayList<>());
            req.setTags(resolveGroups(result));
            req.setTestSuiteId(suiteId);
            TestCaseResponse created = client.createTestCase(req);
            if (created != null && created.getId() != null) {
                serverCaseByResultKey.put(key, created.getId());
                serverCaseByMethodKey.put(resultMethodInvocationKey(result), created.getId());
            }
        } catch (Exception e) {
            System.err.println("MERV TestNG create test case failed: " + e.getMessage());
        }
    }

    private void completeServerCase(ITestResult result, String status, String failureReason) {
        if (result == null || client == null) {
            return;
        }
        ensureServerCaseForResult(result);
        UUID caseId = serverCaseByResultKey.get(resultKey(result));
        if (caseId == null) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(serverCaseHasCustomFailure.get(caseId))) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.FAILED);
                return;
            }
            if ("PASSED".equals(status)) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.PASSED);
            } else if ("FAILED".equals(status)) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.FAILED);
            } else if ("SKIPPED".equals(status)) {
                client.updateTestCaseStatus(caseId, TestCaseStatus.SKIPPED);
            } else {
                client.updateTestCaseStatus(caseId, TestCaseStatus.INPROGRESS);
            }
        } catch (Exception e) {
            System.err.println("MERV TestNG patch test case failed: " + e.getMessage());
        }
    }

    private void ensureServerStepForInvocation(IInvokedMethod method, ITestResult result) {
        if (client == null || result == null) {
            return;
        }
        UUID caseId = serverCaseForInvocation(method, result);
        if (caseId == null) {
            return;
        }
        String methodKey = methodKey(result, method);
        if (serverStepByMethodKey.containsKey(methodKey)) {
            return;
        }
        try {
            TestStepRequest req = new TestStepRequest();
            req.setTeststepName(resolveInvocationStepName(method));
            req.setExpected("Expected execution");
            req.setActual("In progress");
            req.setStatus("IN_PROGRESS");
            req.setTestcaseId(caseId);
            TestStepResponse created = client.createTestStep(req);
            if (created != null && created.getId() != null) {
                serverStepByMethodKey.put(methodKey, created.getId());
            }
        } catch (Exception e) {
            System.err.println("MERV TestNG create step failed: " + e.getMessage());
        }
    }

    private void finishServerStepForInvocation(IInvokedMethod method, ITestResult result) {
        if (client == null || result == null) {
            return;
        }
        String methodKey = methodKey(result, method);
        UUID stepId = serverStepByMethodKey.get(methodKey);
        if (stepId == null) {
            return;
        }
        try {
            TestStepPatchRequest req = new TestStepPatchRequest();
            tryCaptureAutomationScreenshotServer(stepId);
            if (result.getThrowable() != null) {
                req.setStatus("FAILED");
                req.setExpected("Expected execution");
                req.setActual(extractReadableErrorMessage(result.getThrowable()));
                client.patchTestStep(stepId, req);
            } else if (result.getStatus() == ITestResult.SKIP) {
                req.setStatus("SKIPPED");
                req.setExpected("Expected execution");
                req.setActual("Skipped");
                client.patchTestStep(stepId, req);
            } else {
                req.setStatus("PASSED");
                req.setExpected("Expected execution");
                req.setActual("Executed successfully");
                client.patchTestStep(stepId, req);
            }
        } catch (Exception e) {
            System.err.println("MERV TestNG patch step failed: " + e.getMessage());
        } finally {
            serverStepByMethodKey.remove(methodKey);
        }
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
            System.err.println("MERV TestNG screenshot upload failed: " + e.getMessage());
        } finally {
            if (!shot.delete()) {
                shot.deleteOnExit();
            }
        }
    }

    private LocalTestCase ensureCaseForResult(ITestResult result) {
        if (result == null || localTestSuite == null) {
            return null;
        }
        UUID caseId = readCaseId(result);
        if (caseId != null) {
            return localTestCases.get(caseId);
        }
        UUID mappedCaseId = localCaseByMethodKey.get(resultMethodInvocationKey(result));
        if (mappedCaseId != null) {
            LocalTestCase mappedCase = localTestCases.get(mappedCaseId);
            if (mappedCase != null) {
                result.setAttribute(ATTR_CASE_ID, mappedCaseId.toString());
                return mappedCase;
            }
        }

        LocalTestCase localCase = new LocalTestCase();
        UUID id = UUID.randomUUID();
        localCase.setId(id);
        localCase.setTestcaseName(resolveCaseName(result));
        localCase.setTags(resolveGroups(result));
        localCase.setStatus("IN_PROGRESS");
        localCase.setStartTime(new Date());
        localCase.setExecutionMachine(resolveExecutionMachine());
        localCase.setTestSteps(new ArrayList<>());
        localCase.setTestManagementId(new ArrayList<>());

        localTestCases.put(id, localCase);
        localCaseByMethodKey.put(resultMethodInvocationKey(result), id);
        synchronized (REPORT_LOCK) {
            localTestSuite.getTestCases().add(localCase);
        }

        result.setAttribute(ATTR_CASE_ID, id.toString());
        persistLocalRuntimeSnapshot(false);
        return localCase;
    }

    private void completeCase(ITestResult result, String status, String failureReason) {
        if (isMervEnabled()) {
            return;
        }
        LocalTestCase localCase = ensureCaseForResult(result);
        if (localCase == null) {
            return;
        }
        localCase.setEndTime(new Date());
        if ("FAILED".equalsIgnoreCase(localCase.getStatus()) || Boolean.TRUE.equals(localCaseHasCustomFailure.get(localCase.getId()))) {
            localCase.setStatus("FAILED");
        } else {
            localCase.setStatus(status);
        }
        if (failureReason != null && !failureReason.isBlank()) {
            localCase.setFailureReason(failureReason);
        }
        persistLocalRuntimeSnapshot(false);
    }

    private void initializeLocalRuntimeReporting(String reportFolderPath) {
        try {
            String jsonFolderPath = reportFolderPath + "json" + File.separator;
            String htmlFolderPath = reportFolderPath + "html" + File.separator;
            new File(jsonFolderPath).mkdirs();
            new File(htmlFolderPath).mkdirs();
            writeRunningHtmlSnapshots(reportFolderPath);
            persistLocalRuntimeSnapshot(false);
        } catch (Exception e) {
            System.err.println("Error initializing TestNG runtime report: " + e.getMessage());
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
                System.err.println("Error persisting TestNG runtime snapshot: " + e.getMessage());
            }
        }
    }

    private void writeRunningHtmlSnapshots(String reportFolderPath) {
        try {
            String htmlDir = reportFolderPath + "html" + File.separator;
            new File(htmlDir).mkdirs();
            String content = buildLiveHtmlReportContent();
            FileUtils.writeFile(htmlDir + "merv-report-live.html", content);
            FileUtils.writeFile(htmlDir + "merv-live-report.html", content);
            FileUtils.writeFile(htmlDir + "merv-report.html", content);
        } catch (Exception e) {
            System.err.println("Error writing TestNG running HTML reports: " + e.getMessage());
        }
    }

    private String buildLiveHtmlReportContent() {
        return MervCucumberHandler.buildLiveHtmlReportContent();
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
                String content = buildLiveHtmlReportContent();
                FileUtils.writeFile(finalHtml, content);
                FileUtils.writeFile(liveHtml, content);
                FileUtils.writeFile(liveHtmlAlt, content);
            }
            persistLocalRuntimeSnapshot(true);
            refreshReportsIndexListing();
        } catch (Exception e) {
            System.err.println("Error generating TestNG local reports: " + e.getMessage());
        }
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
            System.err.println("MERV TestNG screenshot save failed: " + e.getMessage());
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

    private String resolveCaseName(ITestResult result) {
        String className = result.getTestClass() == null ? "UnknownClass" : result.getTestClass().getRealClass().getSimpleName();
        String methodName = result.getMethod() == null ? "unknownMethod" : result.getMethod().getMethodName();
        return className + "." + methodName + resolveParameterSuffix(result);
    }

    private List<String> resolveGroups(ITestResult result) {
        if (result.getMethod() == null || result.getMethod().getGroups() == null) {
            return new ArrayList<>();
        }
        String[] groups = result.getMethod().getGroups();
        if (groups.length == 0) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        Collections.addAll(list, groups);
        return list;
    }

    private String resolveInvocationStepName(IInvokedMethod method) {
        String qualified = method.getTestMethod() == null ? "unknown" : method.getTestMethod().getQualifiedName();
        if (method.isTestMethod()) {
            return "Test Method: " + qualified;
        }
        if (method.getTestMethod() != null) {
            if (method.getTestMethod().isBeforeMethodConfiguration()) return "@BeforeMethod: " + qualified;
            if (method.getTestMethod().isAfterMethodConfiguration()) return "@AfterMethod: " + qualified;
            if (method.getTestMethod().isBeforeClassConfiguration()) return "@BeforeClass: " + qualified;
            if (method.getTestMethod().isAfterClassConfiguration()) return "@AfterClass: " + qualified;
            if (method.getTestMethod().isBeforeTestConfiguration()) return "@BeforeTest: " + qualified;
            if (method.getTestMethod().isAfterTestConfiguration()) return "@AfterTest: " + qualified;
            if (method.getTestMethod().isBeforeSuiteConfiguration()) return "@BeforeSuite: " + qualified;
            if (method.getTestMethod().isAfterSuiteConfiguration()) return "@AfterSuite: " + qualified;
        }
        return "@Configuration: " + qualified;
    }

    private LocalTestCase localCaseForInvocation(IInvokedMethod method, ITestResult result) {
        if (result == null) {
            return null;
        }
        if (method.isTestMethod()) {
            return ensureCaseForResult(result);
        }
        UUID caseId = readCaseId(result);
        if (caseId == null) {
            caseId = localCaseByMethodKey.get(resultMethodInvocationKey(result));
        }
        if (caseId == null) {
            caseId = THREAD_LOCAL_CASE_ID.get();
        }
        if (caseId == null) {
            return null;
        }
        return localTestCases.get(caseId);
    }

    private UUID serverCaseForInvocation(IInvokedMethod method, ITestResult result) {
        if (result == null) {
            return null;
        }
        if (method.isTestMethod()) {
            ensureServerCaseForResult(result);
            return serverCaseByResultKey.get(resultKey(result));
        }
        UUID caseId = serverCaseByResultKey.get(resultKey(result));
        if (caseId == null) {
            caseId = serverCaseByMethodKey.get(resultMethodInvocationKey(result));
        }
        if (caseId == null) {
            caseId = THREAD_LOCAL_CASE_ID.get();
        }
        return caseId;
    }

    private String resultKey(ITestResult result) {
        String cls = result.getTestClass() == null ? "UnknownClass" : result.getTestClass().getName();
        String method = result.getMethod() == null ? "unknownMethod" : result.getMethod().getMethodName();
        long start = result.getStartMillis();
        return cls + "#" + method + "#" + start;
    }

    private String methodKey(ITestResult result, IInvokedMethod method) {
        String base = resultKey(result);
        String methodName = method != null && method.getTestMethod() != null ? method.getTestMethod().getMethodName() : "unknown";
        return base + "::" + methodName;
    }

    private String resolveParameterSuffix(ITestResult result) {
        if (result == null || result.getParameters() == null || result.getParameters().length == 0) {
            return "";
        }
        Object[] params = result.getParameters();
        StringBuilder sb = new StringBuilder(" [");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(safeParamValue(params[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private String safeParamValue(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            String text = String.valueOf(value);
            if (text.length() > 80) {
                return text.substring(0, 77) + "...";
            }
            return text.replace("\n", "\\n").replace("\r", "\\r");
        } catch (Exception e) {
            return value.getClass().getSimpleName();
        }
    }

    private String resultMethodKey(ITestResult result) {
        if (result == null) {
            return "UnknownClass#unknownMethod";
        }
        String cls = result.getTestClass() == null ? "UnknownClass" : result.getTestClass().getName();
        String method = result.getMethod() == null ? "unknownMethod" : result.getMethod().getMethodName();
        return cls + "#" + method;
    }

    private String resultMethodInvocationKey(ITestResult result) {
        String base = resultMethodKey(result);
        Object[] params = result == null ? null : result.getParameters();
        int paramsHash = params == null ? 0 : java.util.Arrays.deepHashCode(params);
        return base + "#" + paramsHash;
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
                // continue to final fallback
            }
        }
        if (host == null || host.isBlank()) {
            host = "Unknown Machine (" + System.getProperty("user.name", "user") + ")";
        }
        return host;
    }

    private UUID readCaseId(ITestResult result) {
        if (result == null) {
            return null;
        }
        Object raw = result.getAttribute(ATTR_CASE_ID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private UUID readStepId(ITestResult result) {
        if (result == null) {
            return THREAD_LOCAL_STEP_ID.get();
        }
        Object raw = result.getAttribute(ATTR_STEP_ID);
        if (raw == null) {
            return THREAD_LOCAL_STEP_ID.get();
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (Exception ignored) {
            return THREAD_LOCAL_STEP_ID.get();
        }
    }

    private LocalTestStep findStep(LocalTestCase localCase, UUID stepId) {
        if (localCase == null || localCase.getTestSteps() == null || stepId == null) {
            return null;
        }
        for (LocalTestStep step : localCase.getTestSteps()) {
            if (Objects.equals(step.getId(), stepId)) {
                return step;
            }
        }
        return null;
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

    private LocalTestStep buildCompletedInvocationStep(IInvokedMethod method, ITestResult testResult) {
        LocalTestStep step = new LocalTestStep();
        step.setId(UUID.randomUUID());
        long startMs = testResult == null ? 0L : testResult.getStartMillis();
        long endMs = testResult == null ? 0L : testResult.getEndMillis();
        step.setStartTime(startMs > 0 ? new Date(startMs) : new Date());
        step.setEndTime(endMs > 0 ? new Date(endMs) : new Date());
        step.setTeststepName(resolveInvocationStepName(method));
        step.setStepType(method != null && method.isTestMethod() ? "TEST_METHOD" : "CONFIG_METHOD");
        if (testResult != null && testResult.getThrowable() != null) {
            step.setStatus("FAILED");
            step.setErrorMessage(extractReadableErrorMessage(testResult.getThrowable()));
            tryCaptureAutomationScreenshotLocal(step);
        } else if (testResult != null && testResult.getStatus() == ITestResult.SKIP) {
            step.setStatus("SKIPPED");
            tryCaptureAutomationScreenshotLocal(step);
        } else {
            step.setStatus("PASSED");
            tryCaptureAutomationScreenshotLocal(step);
        }
        return step;
    }

    private void cleanupThreadLocals() {
        THREAD_LOCAL_STEP_ID.remove();
        THREAD_LOCAL_CASE_ID.remove();
        THREAD_LOCAL_PENDING_CONFIG_STEPS.remove();
        THREAD_LOCAL_CUSTOM_STEPS.remove();
        MervPluginSteps.clear();
        THREAD_LOCAL_AUTOMATION_TOOL.remove();
        THREAD_LOCAL_AUTOMATION_DRIVER.remove();
    }

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
