package org.teche.merv.client.plugin;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.*;
import org.teche.merv.client.MervClient;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.dto.*;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.utils.MervPropertyFlags;
import org.teche.merv.client.utils.FileUtils;
import org.teche.merv.client.utils.ReportsDeleteServer;
import org.teche.merv.client.report.html.MervHtmlEscape;
import org.teche.merv.client.report.html.MervReportBranding;
import org.teche.merv.client.report.html.MervConsolidatedFailureReasonsWriter;
import org.teche.merv.client.report.html.MervFailureTestJsonWriter;
import org.teche.merv.client.report.html.MervReportsIndexHtmlWriter;
import org.teche.merv.client.report.html.MervTestDataFileHtml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class MervCucumberHandler implements ConcurrentEventListener {
    /** Shared across plugin instances and parallel workers (Cucumber may use more than one handler instance). */
    private static volatile UUID sharedSuiteId;
    private static volatile Properties sharedMervProps;
    private UUID suiteId;
    private UUID activeTestId;
    private UUID activeStepId;
    private MervClient client;
    private Properties mervProp = new Properties();

    // Static storage for static method access (for reporting plugin pattern)
    // Using volatile for thread-safe access to shared client instance
    private static volatile MervClient sharedClient;
    // ThreadLocal for step ID as each thread has its own active step
    private static final ThreadLocal<UUID> threadLocalActiveStepId = new ThreadLocal<>();
    // ThreadLocal for test case ID as each thread has its own active test case
    private static final ThreadLocal<UUID> threadLocalActiveTestCaseId = new ThreadLocal<>();
    // ThreadLocal for skipping next step - tracks if next step should be skipped and if it should be visible in report
    private static final ThreadLocal<Boolean> threadLocalSkipNextStep = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> threadLocalSkipStepViewInReport = new ThreadLocal<>();
    // Track if current step was created as SKIPPED (so we don't update test case status when it finishes)
    private static final ThreadLocal<Boolean> threadLocalCurrentStepIsSkipped = new ThreadLocal<>();

    // Local storage for test data when Merv is disabled
    private static LocalTestSuite localTestSuite;
    private static final Map<UUID, LocalTestCase> localTestCases = new HashMap<>();
    private static final Map<UUID, LocalTestStep> localTestSteps = new HashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Object localReportLock = new Object();

    /** Per-thread automation binding for optional step screenshots ({@link #setAutomationToolObject}). */
    private static final ThreadLocal<AutomationTool> threadLocalAutomationTool = new ThreadLocal<>();
    private static final ThreadLocal<Object> threadLocalAutomationDriver = new ThreadLocal<>();
    /** From {@code merv.screenshot} / {@code screenshot} in {@code merv.properties}, set at suite start. */
    private static volatile boolean stepScreenshotCaptureEnabled = false;

    public EventHandler<TestStepStarted> startMervHandler = this::mervStepStart;
    public EventHandler<TestStepFinished> finishMervHandler = this::mervStepFinish;
    public EventHandler<TestCaseStarted> testStartMervHandler = this::mervTestStart;
    public EventHandler<TestCaseFinished> testFinishMervHandler = this::mervTestFinish;
    public EventHandler<TestRunStarted> suiteStartMervHandler = this::mervSuiteStart;
    public EventHandler<TestRunFinished> suiteFinishMervHandler = this::mervSuiteFinish;

    @Override
    public void setEventPublisher(EventPublisher eventPublisher) {
        eventPublisher.registerHandlerFor(TestStepStarted.class, startMervHandler);
        eventPublisher.registerHandlerFor(TestStepFinished.class, finishMervHandler);
        eventPublisher.registerHandlerFor(TestCaseStarted.class, testStartMervHandler);
        eventPublisher.registerHandlerFor(TestCaseFinished.class, testFinishMervHandler);
        eventPublisher.registerHandlerFor(TestRunStarted.class, suiteStartMervHandler);
        eventPublisher.registerHandlerFor(TestRunFinished.class, suiteFinishMervHandler);
    }

    private void mervSuiteFinish(TestRunFinished suite){
        if (!isMervEnabled()) {
            if (localTestSuite != null) {
                localTestSuite.setEndTime(new Date());
                persistLocalRuntimeSnapshot(true);
            }
            // Generate local reports when Merv is disabled
            generateLocalReports();
        } else {
            boolean parallelFlag = false;
            if(mervProp.containsKey("merv.execution.parallel")){
                try {
                    parallelFlag = Boolean.parseBoolean(mervProp.getProperty("merv.execution.parallel").toLowerCase());
                    System.out.println("merv.properties successfully loaded");
                }catch (Exception e){
                    System.out.println("merv.properties -> merv.execution.parallel value should be true/false. no other value required.");
                }
                if(!parallelFlag){
                    TestSuitePatchRequest testSuiteReq =  new TestSuitePatchRequest();
                    testSuiteReq.setSuiteStatus("COMPLETED");
                    try {
                        client.patchTestSuite(suiteId, testSuiteReq);
                    }catch (MervClientException e){
                        System.out.println("issue in suite status update " +e.getMessage());
                    }
                }
            }
        }
        // Clean up ThreadLocal resources and shared client
        cleanupThreadLocal();
        sharedClient = null;
        sharedSuiteId = null;
        sharedMervProps = null;
    }

    private void mervServerStatus(String serverIp, String username, String status) {
        System.out.println("███╗   ███╗███████╗██████╗ ██╗   ██╗");
        System.out.println("████╗ ████║██╔════╝██╔══██╗██║   ██║");
        System.out.println("██╔████╔██║█████╗  ██████╔╝██║   ██║");
        System.out.println("██║╚██╔╝██║██╔══╝  ██╔══██╗╚██╗ ██╔╝");
        System.out.println("██║ ╚═╝ ██║███████╗██║  ██║ ╚████╔╝ ");
        System.out.println("╚═╝     ╚═╝╚══════╝╚═╝  ╚═╝  ╚═══╝  ");
        System.out.println("════════════════════════════════════");
        System.out.println("Server IP :  " + serverIp);
        if(username.equals("API Key")){
            System.out.println("Connection Method  :  API Key");
            System.out.println("API Key  :  ************************");
        }else {
            System.out.println("Username  :  " + username);
            System.out.println("Password  :  ***********");
        }
        System.out.println("Status    :  " + status);
        System.out.println("════════════════════════════════════");
    }
    private void mervSuiteStart(TestRunStarted suite){
        try {
            String merv_property = System.getProperty("user.dir")+File.separator+"merv.properties";
            if(!new File(merv_property).exists()){
                throw new MervClientException("merv.properties file not available in project root.");
            }
            mervProp.load(new FileInputStream(merv_property));
            sharedMervProps = mervProp;
            stepScreenshotCaptureEnabled = MervPropertyFlags.isScreenshotEnabled(mervProp);

            if (!isMervEnabled()) {
                // Initialize local test suite storage
                localTestSuite = new LocalTestSuite();
                localTestSuite.setTitle(mervProp.getProperty("merv.regression_suite", "Test Execution Report"));
                localTestSuite.setStartTime(new Date());
                localTestSuite.setTestCases(new ArrayList<>());

                // Create report folder structure immediately so screenshots can be saved
                try {
                    // Get report folder from merv.properties, default to "reports" in project root
                    String baseReportPath = MervConfig.getReportFolder();
                    File reportsDir = new File(baseReportPath);
                    if (!reportsDir.exists()) {
                        reportsDir.mkdirs();
                    }

                    // Generate folder name with format "DD-MM-YYYY HH-MM-SS Merv-Report" (using hyphens instead of colons for file system compatibility)
                    SimpleDateFormat folderDateFormat = new SimpleDateFormat("dd-MM-yyyy HH-mm-ss");
                    String folderName = folderDateFormat.format(new Date()) + " Merv-Report";
                    String reportFolderPath = baseReportPath + folderName + File.separator;

                    // Create main report folder
                    File reportFolder = new File(reportFolderPath);
                    if (!reportFolder.exists()) {
                        reportFolder.mkdirs();
                    }

                    // Set current report folder path for screenshot storage
                    currentReportFolderPath = reportFolderPath;
                    initializeLocalRuntimeReporting(reportFolderPath);
                    refreshReportsIndexListing();

                    System.out.println("Merv is disabled. Reports will be generated locally.");
                    System.out.println("Report folder created: " + reportFolderPath);
                } catch (Exception e) {
                    System.err.println("Error creating report folder: " + e.getMessage());
                }
                return;
            }

            // Check if API key is provided, otherwise use username/password
            String apiKey = mervProp.getProperty("merv.api_key");
            String server = mervProp.getProperty("merv.server");
            String username = mervProp.getProperty("merv.username");
            String password = mervProp.getProperty("merv.password");

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                // Use API key authentication
                client = new MervClient(server, apiKey.trim(), true);
                System.out.println("Using API key authentication");
            } else if (username != null && password != null &&
                    !username.trim().isEmpty() && !password.trim().isEmpty()) {
                // Use username/password authentication (existing behavior)
                client = new MervClient(server, username, password);
                System.out.println("Using username/password authentication");
            } else {
                throw new MervClientException("Either merv.api_key or (merv.username and merv.password) must be configured in merv.properties");
            }

            // Store client in static variable for static method access (shared across threads)
            sharedClient = client;
            try {
                client.verifyConnection();
                String authInfo = apiKey != null && !apiKey.trim().isEmpty() ? "API Key" : username;
                mervServerStatus(server, authInfo, "CONNECTED");
            }catch(MervClientException e0){
                String authInfo = apiKey != null && !apiKey.trim().isEmpty() ? "API Key" : username;
                mervServerStatus(server, authInfo, e0.getMessage());
            }
            suiteId = org.teche.merv.client.utils.MervSuiteBootstrap.resolveSuiteId(
                    client, mervProp, "Regression Test Suite");
            sharedSuiteId = suiteId;
            System.out.println("Merv test suite id: " + suiteId);

        }catch(MervClientException e){
            System.err.println("Merv failed to resolve test suite: " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            }
        }catch (Exception e){
            if (mervProp != null && mervProp.getProperty("merv.server") != null) {
                String apiKey = mervProp.getProperty("merv.api_key");
                String authInfo = (apiKey != null && !apiKey.trim().isEmpty()) ? "API Key" : mervProp.getProperty("merv.username");
                mervServerStatus(mervProp.getProperty("merv.server"), authInfo, e.getMessage());
            }

        }
    }

    private static void bindSharedStepApis() {
        MervPluginSteps.bind(new MervPluginSteps.Adapter() {
            @Override
            public boolean isLocalMode() {
                return !isMervEnabled();
            }

            @Override
            public void addLocalStep(MervPluginSteps.StepPayload payload) throws MervClientException {
                UUID testCaseId = getActiveTestCaseId();
                if (testCaseId == null) {
                    throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
                }
                LocalTestStep localStep = new LocalTestStep();
                localStep.setId(UUID.randomUUID());
                localStep.setTeststepName(payload.name);
                localStep.setStepType(payload.type.getApiValue());
                localStep.setStatus(payload.status);
                localStep.setStartTime(new Date());
                localStep.setExpected(payload.expected);
                localStep.setActual(payload.actual);
                localStep.setTestdata(payload.testdata);
                localStep.setPrereq(payload.prereq);
                localStep.setErrorMessage(payload.errorMessage);
                if (localTestCases.containsKey(testCaseId)) {
                    LocalTestCase tc = localTestCases.get(testCaseId);
                    tc.getTestSteps().add(localStep);
                    if ("FAILED".equalsIgnoreCase(payload.status)) {
                        tc.setStatus("FAILED");
                        if (tc.getFailureReason() == null || tc.getFailureReason().isBlank()) {
                            tc.setFailureReason(payload.errorMessage != null ? payload.errorMessage : "Validation/custom step failed");
                        }
                    }
                }
                localTestSteps.put(localStep.getId(), localStep);
            }

            @Override
            public org.teche.merv.client.dto.TestStepResponse addServerStep(MervPluginSteps.StepPayload payload) throws MervClientException {
                MervClient client = sharedClient;
                UUID testCaseId = getActiveTestCaseId();
                if (client == null) {
                    throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
                }
                if (testCaseId == null) {
                    throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
                }
                org.teche.merv.client.dto.TestStepRequest request = new org.teche.merv.client.dto.TestStepRequest();
                request.setTeststepName(payload.name);
                request.setTestcaseId(testCaseId);
                request.setStepType(payload.type.getApiValue());
                request.setStatus(payload.status);
                if (payload.expected != null) request.setExpected(payload.expected);
                if (payload.actual != null) request.setActual(payload.actual);
                if (payload.testdata != null) request.setTestdata(payload.testdata);
                if (payload.prereq != null) request.setPrereq(payload.prereq);
                org.teche.merv.client.dto.TestStepResponse created = client.createTestStep(request);
                if ("FAILED".equalsIgnoreCase(payload.status)) {
                    try {
                        client.updateTestCaseStatus(testCaseId, TestCaseStatus.FAILED);
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
                return created;
            }

            @Override
            public void addLocalFileStep(String stepName, File file, FileType fileType, String prereq)
                    throws MervClientException {
                UUID testCaseId = getActiveTestCaseId();
                if (testCaseId == null) {
                    throw new MervClientException(
                            "No active test case found. Step creation must be called during an active test case execution.");
                }
                LocalTestStep localStep = new LocalTestStep();
                localStep.setId(UUID.randomUUID());
                localStep.setTeststepName(stepName);
                localStep.setStepType(StepType.TESTDATA.getApiValue());
                localStep.setStatus("PASSED");
                localStep.setStartTime(new Date());
                localStep.setPrereq(prereq);
                List<MervTestDataFileHtml.AttachedFile> attached =
                        MervPluginFileDataSupport.saveAttachedFiles(file, currentReportFolderPath);
                if (attached != null) {
                    localStep.setAttachedFiles(attached);
                } else {
                    localStep.setTestdata(MervPluginFileDataSupport.fallbackTestdata(file, fileType));
                }
                if (localTestCases.containsKey(testCaseId)) {
                    localTestCases.get(testCaseId).getTestSteps().add(localStep);
                }
                localTestSteps.put(localStep.getId(), localStep);
            }

            @Override
            public org.teche.merv.client.dto.TestStepResponse addServerFileStep(
                    String stepName,
                    File file,
                    FileType fileType,
                    String prereq) throws MervClientException {
                return MervPluginFileDataSupport.createServerFileStep(
                        sharedClient, getActiveTestCaseId(), stepName, file, fileType, prereq);
            }
        });
    }

    private void mervTestFinish(TestCaseFinished testcase){
        UUID tcId = threadLocalActiveTestCaseId.get();
        if (!isMervEnabled()) {
            // Update local test case status (use ThreadLocal — parallel scenarios share one handler instance)
            if (tcId != null && localTestCases.containsKey(tcId)) {
                LocalTestCase localTestCase = localTestCases.get(tcId);
                localTestCase.setEndTime(new Date());
                closeOrphanedLocalSteps(localTestCase);
                if(testcase.getResult().getStatus() == Status.FAILED){
                    localTestCase.setStatus("FAILED");
                    if (testcase.getResult().getError() != null) {
                        localTestCase.setFailureReason(extractReadableErrorMessage(testcase.getResult().getError()));
                    }
                }else if(testcase.getResult().getStatus() == Status.SKIPPED){
                    if (!"FAILED".equalsIgnoreCase(localTestCase.getStatus())) {
                        localTestCase.setStatus("SKIPPED");
                    }
                }else{
                    if (!"FAILED".equalsIgnoreCase(localTestCase.getStatus())) {
                        localTestCase.setStatus("PASSED");
                    }
                }
                persistLocalRuntimeSnapshot(false);
            }
            threadLocalActiveTestCaseId.remove();
            MervPluginSteps.clear();
            return;
        }

        if (tcId != null) {
            finishServerTestCase(client, tcId, testcase);
        }
        threadLocalActiveTestCaseId.remove();
        threadLocalActiveStepId.remove();
        MervPluginSteps.clear();
    }

    private void finishServerTestCase(MervClient activeClient, UUID tcId, TestCaseFinished testcase) {
        try {
            Status cucumberStatus = testcase.getResult().getStatus();
            TestCaseStatus calculated = activeClient.finishTestCase(tcId);
            if (cucumberStatus == Status.FAILED
                    && (calculated == TestCaseStatus.INPROGRESS || calculated == TestCaseStatus.SKIPPED)) {
                activeClient.updateTestCaseStatus(tcId, TestCaseStatus.FAILED);
            } else if (cucumberStatus == Status.SKIPPED
                    && (calculated == TestCaseStatus.INPROGRESS || calculated == TestCaseStatus.PASSED)) {
                activeClient.updateTestCaseStatus(tcId, TestCaseStatus.SKIPPED);
            }
        } catch (MervClientException e) {
            System.err.println("Error finishing test case: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void patchServerStepStatus(UUID stepId, Status cucumberStepStatus) {
        String apiStatus;
        if (cucumberStepStatus == Status.FAILED) {
            apiStatus = "FAILED";
        } else if (cucumberStepStatus == Status.PASSED) {
            apiStatus = "PASSED";
        } else if (cucumberStepStatus == Status.SKIPPED) {
            apiStatus = "SKIPPED";
        } else {
            apiStatus = "SKIPPED";
        }
        try {
            TestStepPatchRequest stepUpdateRequest = new TestStepPatchRequest();
            stepUpdateRequest.setStatus(apiStatus);
            client.patchTestStep(stepId, stepUpdateRequest);
        } catch (MervClientException e) {
            System.err.println("Warning: Failed to update step status to " + apiStatus + ": " + e.getMessage());
        }
    }

    private static void closeOrphanedLocalSteps(LocalTestCase localTestCase) {
        if (localTestCase.getTestSteps() == null) {
            return;
        }
        Date now = new Date();
        for (LocalTestStep step : localTestCase.getTestSteps()) {
            if ("IN_PROGRESS".equalsIgnoreCase(step.getStatus())) {
                step.setStatus("SKIPPED");
                if (step.getEndTime() == null) {
                    step.setEndTime(now);
                }
            }
        }
    }
    private void mervTestStart(TestCaseStarted testcase){
        try {
            bindSharedStepApis();
            if (!isMervEnabled()) {
                // Create local test case
                LocalTestCase localTestCase = new LocalTestCase();
                localTestCase.setId(UUID.randomUUID());
                localTestCase.setTestcaseName(testcase.getTestCase().getName());
                localTestCase.setTags(new ArrayList<>(testcase.getTestCase().getTags()));
                localTestCase.setStatus("IN_PROGRESS");
                localTestCase.setStartTime(new Date());
                localTestCase.setTestSteps(new ArrayList<>());

                try {
                    String SystemName = InetAddress.getLocalHost().getHostName();
                    localTestCase.setExecutionMachine(SystemName);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }

                if(mervProp.containsKey("merv_testManagement_prefix")){
                    String prefix = mervProp.getProperty("merv_testManagement_prefix");
                    localTestCase.setTestManagementId(testcase.getTestCase().getTags().stream()
                            .filter(e -> e.startsWith(prefix)).collect(Collectors.toList()));
                }

                UUID newTcId = localTestCase.getId();
                synchronized (localReportLock) {
                    activeTestId = newTcId;
                    localTestCases.put(newTcId, localTestCase);
                    if (localTestSuite != null) {
                        localTestSuite.getTestCases().add(localTestCase);
                    }
                }
                threadLocalActiveTestCaseId.set(newTcId);
                persistLocalRuntimeSnapshot(false);
                return;
            }

            UUID activeSuiteId = ensureSuiteIdReady();
            TestCaseRequest mervTest = new TestCaseRequest();
            mervTest.setTestSuiteId(activeSuiteId);
            mervTest.setTestcaseName(testcase.getTestCase().getName());
            mervTest.setTags(testcase.getTestCase().getTags());
            mervTest.setStatus(TestCaseStatus.INPROGRESS);

            if(mervProp.containsKey("merv_testManagement_prefix")){
                String prefix = mervProp.getProperty("merv_testManagement_prefix");
                mervTest.setTestManagementId(testcase.getTestCase().getTags().stream().filter(e -> e.startsWith(prefix)).collect(Collectors.toList()));
            }
            List<String> machines = new ArrayList<>();
            try {
                String SystemName = InetAddress.getLocalHost().getHostName();
                machines.add(SystemName);
                mervTest.setExecutionMachine(machines);
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            TestCaseResponse  testcaseResponse= client.createTestCase(mervTest);
            activeTestId = testcaseResponse.getId();
            // Store active test case ID in ThreadLocal for static method access
            threadLocalActiveTestCaseId.set(activeTestId);
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    private void mervStepStart(TestStepStarted teststep){
        // Always create step normally (step can be skipped later during execution)
        if(teststep.getTestStep() != null && teststep.getTestStep() instanceof PickleStepTestStep){
            PickleStepTestStep step = (PickleStepTestStep)teststep.getTestStep();

            if (!isMervEnabled()) {
                // Create local test step
                LocalTestStep localStep = new LocalTestStep();
                localStep.setId(UUID.randomUUID());
                localStep.setTeststepName(step.getStep().getText());
                localStep.setStatus("IN_PROGRESS");
                localStep.setStartTime(new Date());

                UUID newStepId = localStep.getId();
                localTestSteps.put(newStepId, localStep);

                UUID tcId = threadLocalActiveTestCaseId.get();
                if (tcId != null && localTestCases.containsKey(tcId)) {
                    localTestCases.get(tcId).getTestSteps().add(localStep);
                }

                threadLocalActiveStepId.set(newStepId);
                threadLocalCurrentStepIsSkipped.set(false);
                persistLocalRuntimeSnapshot(false);
                return;
            }

            TestStepRequest mervStep = new TestStepRequest();
            mervStep.setTeststepName(step.getStep().getText());
            mervStep.setStatus("IN_PROGRESS");
            UUID tcForStep = threadLocalActiveTestCaseId.get();
            mervStep.setTestcaseId(tcForStep);
            try {
                TestStepResponse stepResponse = client.createTestStep(mervStep);
                activeStepId = stepResponse.getId();
                // Store active step ID in ThreadLocal for static method access
                threadLocalActiveStepId.set(activeStepId);
                // Initialize as normal step (will be checked in finish handler)
                threadLocalCurrentStepIsSkipped.set(false);
            } catch (MervClientException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void mervStepFinish(TestStepFinished teststep){
        if(teststep.getTestStep() != null && teststep.getTestStep() instanceof PickleStepTestStep){
            UUID currentStepId = threadLocalActiveStepId.get();
            if (currentStepId == null) {
                return;
            }

            if (!isMervEnabled()) {
                // Handle local step finish (ThreadLocal ids — safe for parallel scenarios)
                LocalTestStep localStep = localTestSteps.get(currentStepId);
                if (localStep == null) {
                    return;
                }

                localStep.setEndTime(new Date());

                // Check if this step was marked to be skipped during execution
                Boolean skipStep = threadLocalSkipNextStep.get();
                if (skipStep != null && skipStep) {
                    Boolean viewInReport = threadLocalSkipStepViewInReport.get();

                    // Reset the skip flags
                    threadLocalSkipNextStep.remove();
                    threadLocalSkipStepViewInReport.remove();

                    if (viewInReport == null || !viewInReport) {
                        // Delete the step from local storage
                        UUID tcId = threadLocalActiveTestCaseId.get();
                        if (tcId != null && localTestCases.containsKey(tcId)) {
                            localTestCases.get(tcId).getTestSteps().remove(localStep);
                        }
                        localTestSteps.remove(currentStepId);
                        threadLocalActiveStepId.remove();
                    } else {
                        // Keep step but set status to SKIPPED
                        localStep.setStatus("SKIPPED");
                        tryCaptureAutomationScreenshotLocal(localStep);
                    }
                    threadLocalCurrentStepIsSkipped.remove();
                    persistLocalRuntimeSnapshot(false);
                    return;
                }

                // Normal step finish (not skipped)
                PickleStepTestStep step = (PickleStepTestStep)teststep.getTestStep();
                System.out.println(teststep.getResult().getStatus());

                if(teststep.getResult().getStatus() == Status.FAILED) {
                    localStep.setStatus("FAILED");
                    if (teststep.getResult().getError() != null) {
                        localStep.setErrorMessage(extractReadableErrorMessage(teststep.getResult().getError()));
                    }
                    // Extract logs for failed step
                    try {
                        List<String> stepLogs = extractLogsForTimeRange(localStep.getStartTime(), localStep.getEndTime());
                        localStep.setLogs(stepLogs);
                    } catch (Exception e) {
                        System.err.println("Error extracting logs for failed step: " + e.getMessage());
                    }
                    // Update test case status
                    UUID tcIdFail = threadLocalActiveTestCaseId.get();
                    if (tcIdFail != null && localTestCases.containsKey(tcIdFail)) {
                        localTestCases.get(tcIdFail).setStatus("FAILED");
                    }
                } else if (teststep.getResult().getStatus() == Status.SKIPPED) {
                    localStep.setStatus("SKIPPED");
                } else if(teststep.getResult().getStatus() == Status.PASSED){
                    localStep.setStatus("PASSED");
                } else {
                    localStep.setStatus("SKIPPED");
                }

                tryCaptureAutomationScreenshotLocal(localStep);

                // Clear all flags for next step
                threadLocalCurrentStepIsSkipped.remove();
                threadLocalSkipNextStep.remove();
                threadLocalSkipStepViewInReport.remove();
                persistLocalRuntimeSnapshot(false);
                return;
            }

            // Check if this step was marked to be skipped during execution
            Boolean skipStep = threadLocalSkipNextStep.get();
            if (skipStep != null && skipStep) {
                Boolean viewInReport = threadLocalSkipStepViewInReport.get();

                // Reset the skip flags
                threadLocalSkipNextStep.remove();
                threadLocalSkipStepViewInReport.remove();

                if (viewInReport == null || !viewInReport) {
                    // viewInReport is false - delete the step
                    try {
                        client.deleteTestStep(currentStepId);
                        threadLocalActiveStepId.remove();
                    } catch (MervClientException e) {
                        System.err.println("Warning: Failed to delete skipped step: " + e.getMessage());
                    }
                } else {
                    // viewInReport is true - keep step but set status to SKIPPED
                    try {
                        TestStepPatchRequest stepUpdateRequest = new TestStepPatchRequest();
                        stepUpdateRequest.setStatus("SKIPPED");
                        client.patchTestStep(currentStepId, stepUpdateRequest);
                        // Don't update test case status - skipped steps don't affect it
                    } catch (MervClientException e) {
                        System.err.println("Warning: Failed to update step status to SKIPPED: " + e.getMessage());
                    }
                }

                // Clear the skipped flag
                threadLocalCurrentStepIsSkipped.remove();
                return;
            }

            // Normal step finish (not skipped)
            System.out.println(teststep.getResult().getStatus());
            tryCaptureAutomationScreenshotServer(currentStepId);

            patchServerStepStatus(currentStepId, teststep.getResult().getStatus());

            // Clear all flags for next step (in case skipStepToAdd was called but step completed normally)
            threadLocalCurrentStepIsSkipped.remove();
            threadLocalSkipNextStep.remove();
            threadLocalSkipStepViewInReport.remove();
        }
    }

    /**
     * Static method to attach a file to the current active test step
     * This method can be called from external classes without exposing MervClient
     *
     * @param file The file to attach
     * @param description Optional file description
     * @return FileAttachmentResponse with file attachment details
     * @throws MervClientException if the file upload fails or no active step/client is available
     */
    public static org.teche.merv.client.dto.FileAttachmentResponse attachFile(File file, String description) throws MervClientException {
        if (!isMervEnabled()) {
            // When Merv is disabled, save file to report folder
            UUID stepId = resolveLocalAttachmentStepId();
            if (stepId == null) {
                System.err.println("Merv is disabled. No active test step found. File will not be saved.");
                return null;
            }

            // Save file to report folder
            String savedFilePath = saveFileToReportFolder(file, description);
            if (savedFilePath != null) {
                System.out.println("File saved to report folder: " + savedFilePath);
                synchronized (localReportLock) {
                    LocalTestStep localStep = localTestSteps.get(stepId);
                    if (localStep != null) {
                        if (localStep.getScreenshots() == null) {
                            localStep.setScreenshots(new ArrayList<>());
                        }
                        localStep.getScreenshots().add(savedFilePath);
                    }
                    if (localTestSuite != null) {
                        try {
                            Map<String, Object> jsonReport = new LinkedHashMap<>();
                            jsonReport.put("testSuite", localTestSuite);
                            jsonReport.put("exportDate", new Date().toString());
                            jsonReport.put("version", "1.0");
                            jsonReport.put("running", true);
                            jsonReport.put("lastActivityMillis", System.currentTimeMillis());
                            String json = objectMapper.writeValueAsString(jsonReport);
                            String jsonFolderPath = currentReportFolderPath + "json" + File.separator;
                            File jsonFolder = new File(jsonFolderPath);
                            if (!jsonFolder.exists()) {
                                jsonFolder.mkdirs();
                            }
                            FileUtils.writeFile(jsonFolderPath + "merv-report.json", json);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return null;
        }

        MervClient client = sharedClient;
        UUID stepId = threadLocalActiveStepId.get();

        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        if (stepId == null) {
            throw new MervClientException("No active test step found. File attachment must be called during an active test step execution.");
        }

        return client.uploadFile(stepId, file, description);
    }

    /**
     * ThreadLocal step id (set by the Cucumber plugin). If {@code @AfterStep} runs after the plugin
     * clears it, fall back to the last step of the active test case on this thread.
     */
    private static UUID resolveLocalAttachmentStepId() {
        UUID stepId = threadLocalActiveStepId.get();
        if (stepId != null) {
            return stepId;
        }
        UUID testCaseId = threadLocalActiveTestCaseId.get();
        if (testCaseId == null) {
            return null;
        }
        LocalTestCase tc = localTestCases.get(testCaseId);
        if (tc == null || tc.getTestSteps() == null || tc.getTestSteps().isEmpty()) {
            return null;
        }
        return tc.getTestSteps().get(tc.getTestSteps().size() - 1).getId();
    }

    /**
     * Static method to attach binary data as a file to the current active test step
     * This method can be called from external classes without exposing MervClient
     *
     * @param fileData The file content as byte array
     * @param filename The filename (should include extension)
     * @param description Optional file description
     * @return FileAttachmentResponse with file attachment details
     * @throws MervClientException if the file upload fails or no active step/client is available
     */
    public static org.teche.merv.client.dto.FileAttachmentResponse attachFile(byte[] fileData, String filename, String description) throws MervClientException {
        if (!isMervEnabled()) {
            throw new MervClientException("Merv is disabled. File attachments are not supported in local mode. Reports will be generated locally.");
        }

        MervClient client = sharedClient;
        UUID stepId = threadLocalActiveStepId.get();

        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        if (stepId == null) {
            throw new MervClientException("No active test step found. File attachment must be called during an active test step execution.");
        }

        return client.uploadFile(stepId, fileData, filename, description);
    }

    /**
     * Static method to attach a file from InputStream to the current active test step
     * This method can be called from external classes without exposing MervClient
     *
     * @param inputStream The input stream containing file data
     * @param filename The filename (should include extension)
     * @param description Optional file description
     * @return FileAttachmentResponse with file attachment details
     * @throws MervClientException if the file upload fails or no active step/client is available
     */
    public static org.teche.merv.client.dto.FileAttachmentResponse attachFile(InputStream inputStream, String filename, String description) throws MervClientException {
        if (!isMervEnabled()) {
            throw new MervClientException("Merv is disabled. File attachments are not supported in local mode. Reports will be generated locally.");
        }

        MervClient client = sharedClient;
        UUID stepId = threadLocalActiveStepId.get();

        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        if (stepId == null) {
            throw new MervClientException("No active test step found. File attachment must be called during an active test step execution.");
        }

        return client.uploadFile(stepId, inputStream, filename, description);
    }

    // NOTE: Step helper methods intentionally live in MervReporter / MervPluginSteps.
    // This handler only binds execution context.
    /**
     * Static method to add a test data step with file as data
     * Convenience method for creating TEST_DATA type steps with file attachment
     * The file will be attached to the step and the filename will be stored in testdata field
     *
     * @param stepName The name/description of the test step
     * @param file The file to attach as test data
     * @param fileType The type of the file (IMAGE, JSON, EXCEL, XML, TXT, HTML, OTHERS)
     * @param prereq Optional prerequisites
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    static org.teche.merv.client.dto.TestStepResponse data(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) throws MervClientException {
        return MervPluginSteps.data(stepName, file, fileType, prereq);
    }

    /** @deprecated Use {@link #data(String, File, org.teche.merv.client.dto.FileType, String)}. */
    @Deprecated
    static org.teche.merv.client.dto.TestStepResponse addDataStep(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) throws MervClientException {
        return data(stepName, file, fileType, prereq);
    }

    /**
     * Static method to add a validation/assertion step
     * Convenience method for creating ASSERTION type steps
     *
     * @param stepName The name/description of the test step
     * @param expected Optional expected result
     * @param actual Optional actual result
     * @param testdata Optional test data content
     * @param prereq Optional prerequisites
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    // NOTE: Validation/info helpers are provided by MervReporter / MervPluginSteps.

    /**
     * Get the active test case ID from ThreadLocal (for internal use)
     */
    private static UUID getActiveTestCaseId() {
        return threadLocalActiveTestCaseId.get();
    }

    /**
     * Mark the current step to be skipped
     * By default, the skipped step will be kept with SKIPPED status (visible in report)
     * This method can be called during step execution from external classes without exposing MervClient
     * When the step finishes, if viewInReport is true, the step status will be set to SKIPPED.
     * If viewInReport is false, the step will be deleted from the server.
     */
    static void skipStep() {
        skipStep(true);
    }

    /**
     * Mark the current step to be skipped with option to control report visibility
     * This method can be called during step execution from external classes without exposing MervClient
     *
     * @param viewInReport If true, the step will be kept with SKIPPED status (visible in report, won't affect test case status)
     *                     If false, the step will be deleted from the server (not visible in report)
     */
    static void skipStep(boolean viewInReport) {
        threadLocalSkipNextStep.set(true);
        threadLocalSkipStepViewInReport.set(viewInReport);
    }

    /**
     * Clear the skip flag (allows next step to be added normally)
     * This method can be called from external classes to cancel skipping
     */
    public static void clearSkipStep() {
        threadLocalSkipNextStep.remove();
        threadLocalSkipStepViewInReport.remove();
    }

    /**
     * Get the current test step name
     * This method can be called from external classes without exposing MervClient
     *
     * @return The current test step name, or null if no active step is available
     * @throws MervClientException if the request fails or client is not initialized
     */
    public static String getCurrentTestStepName() throws MervClientException {
        UUID stepId = threadLocalActiveStepId.get();

        if (stepId == null) {
            throw new MervClientException("No active test step found. Method must be called during an active test step execution.");
        }

        if (!isMervEnabled()) {
            // When Merv is disabled, get from local storage
            LocalTestStep localStep = localTestSteps.get(stepId);
            if (localStep != null) {
                return localStep.getTeststepName();
            }
            throw new MervClientException("Test step not found in local storage.");
        }

        MervClient client = sharedClient;
        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        try {
            org.teche.merv.client.dto.TestStepResponse testStep = client.getTestStepById(stepId);
            return testStep.getTeststepName();
        } catch (MervClientException e) {
            throw new MervClientException("Failed to get current test step name: " + e.getMessage(), e);
        }
    }

    /**
     * Set/update the current test step name
     * This method can be called from external classes without exposing MervClient
     *
     * @param testStepName The new test step name to set
     * @throws MervClientException if the update fails, client is not initialized, or no active step is available
     */
    public static void setCurrentTestStepName(String testStepName) throws MervClientException {
        UUID stepId = threadLocalActiveStepId.get();

        if (stepId == null) {
            throw new MervClientException("No active test step found. Method must be called during an active test step execution.");
        }

        if (testStepName == null || testStepName.trim().isEmpty()) {
            throw new MervClientException("Test step name cannot be null or empty.");
        }

        if (!isMervEnabled()) {
            // When Merv is disabled, update local storage
            LocalTestStep localStep = localTestSteps.get(stepId);
            if (localStep != null) {
                localStep.setTeststepName(testStepName.trim());
                return;
            }
            throw new MervClientException("Test step not found in local storage.");
        }

        MervClient client = sharedClient;
        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        try {
            org.teche.merv.client.dto.TestStepPatchRequest patchRequest = new org.teche.merv.client.dto.TestStepPatchRequest();
            patchRequest.setTeststepName(testStepName.trim());
            client.patchTestStep(stepId, patchRequest);
        } catch (MervClientException e) {
            throw new MervClientException("Failed to update test step name: " + e.getMessage(), e);
        }
    }

    /**
     * Ensures a suite id exists for server mode (handles multiple plugin instances and late suite bootstrap).
     */
    private UUID ensureSuiteIdReady() throws Exception {
        if (suiteId != null) {
            return suiteId;
        }
        if (sharedSuiteId != null) {
            suiteId = sharedSuiteId;
            return suiteId;
        }
        MervClient activeClient = client != null ? client : sharedClient;
        if (activeClient == null) {
            throw new MervClientException(
                    "MervClient is not initialized. Check mervSuiteStart logs and merv.properties (merv.local=false, API key).");
        }
        Properties props = sharedMervProps != null ? sharedMervProps : loadMervPropertiesFromProjectRoot();
        suiteId = org.teche.merv.client.utils.MervSuiteBootstrap.resolveSuiteId(
                activeClient, props, "Regression Test Suite");
        sharedSuiteId = suiteId;
        System.out.println("Merv test suite id (lazy): " + suiteId);
        return suiteId;
    }

    private static Properties loadMervPropertiesFromProjectRoot() throws Exception {
        String path = System.getProperty("user.dir") + File.separator + "merv.properties";
        if (!new File(path).exists()) {
            throw new MervClientException("merv.properties file not available in project root: " + path);
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            props.load(in);
        }
        sharedMervProps = props;
        return props;
    }

    /**
     * Check if Merv is enabled based on merv.enabled property
     * @return true if Merv is enabled, false otherwise
     */
    public static boolean isMervEnabled() {
        try {
            String merv_property = System.getProperty("user.dir") + File.separator + "merv.properties";
            File propFile = new File(merv_property);
            if (!propFile.exists()) {
                return false;
            }
            Properties props = new Properties();
            props.load(new FileInputStream(propFile));
            String enabled = props.getProperty("merv.local");
            if (enabled == null) {
                // Default to true if property is not set (backward compatibility)
                return false;
            }
            return !Boolean.parseBoolean(enabled.toLowerCase());
        } catch (Exception e) {
            // If there's any error reading the property, default to true for backward compatibility
            return false;
        }
    }

    // Store the report folder path for screenshot storage
    private static volatile String currentReportFolderPath = null;

    /**
     * Save file (screenshot) to the current report folder inside a screenshot subfolder
     */
    private static String saveFileToReportFolder(File file, String description) {
        if (currentReportFolderPath == null || file == null || !file.exists()) {
            return null;
        }

        try {
            // Create screenshot subfolder in report folder if it doesn't exist
            String screenshotFolderPath = currentReportFolderPath + "screenshot" + File.separator;
            File screenshotFolder = new File(screenshotFolderPath);
            if (!screenshotFolder.exists()) {
                screenshotFolder.mkdirs();
            }

            // Generate unique filename
            String originalFileName = file.getName();
            String extension = "";
            int lastDot = originalFileName.lastIndexOf('.');
            if (lastDot > 0) {
                extension = originalFileName.substring(lastDot);
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS");
            String timestamp = dateFormat.format(new Date());
            String fileName = "screenshot_" + timestamp + extension;
            String filePath = screenshotFolderPath + fileName;

            // Copy file to screenshot folder
            java.nio.file.Files.copy(file.toPath(), new File(filePath).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Return relative path from report folder (including screenshot subfolder)
            return "screenshot" + File.separator + fileName;
        } catch (Exception e) {
            System.err.println("Error saving file to report folder: " + e.getMessage());
            return null;
        }
    }

    /**
     * Registers the automation driver/page used for optional per-step screenshots when
     * {@code merv.screenshot=true} (legacy {@code on}, {@code yes}, {@code 1}; or {@code screenshot=true}) is set in {@code merv.properties}.
     * Use {@link AutomationTool#AUTO} if unsure; capture uses Selenium-style or Playwright APIs reflectively.
     * <p>
     * Typical usage: call from a {@code Before} hook per thread; pass {@code null} for the tool to clear.
     * </p>
     *
     * @param automationToolName identifies how {@code driverObject} should be interpreted
     * @param driverObject       Selenium {@code WebDriver}, Playwright {@code Page}, etc.
     */
    public static void setAutomationToolObject(AutomationTool automationToolName, Object driverObject) {
        if (automationToolName == null) {
            threadLocalAutomationTool.remove();
            threadLocalAutomationDriver.remove();
            return;
        }
        threadLocalAutomationTool.set(automationToolName);
        threadLocalAutomationDriver.set(driverObject);
    }
    private static void tryCaptureAutomationScreenshotLocal(LocalTestStep localStep) {
        if (!stepScreenshotCaptureEnabled || localStep == null) {
            return;
        }
        AutomationTool tool = threadLocalAutomationTool.get();
        Object drv = threadLocalAutomationDriver.get();
        if (tool == null || drv == null) {
            return;
        }
        File shot = AutomationScreenshotCapturer.captureToTempPng(tool, drv);
        if (shot == null || !shot.exists()) {
            return;
        }
        try {
            String rel = saveFileToReportFolder(shot, "Step screenshot");
            if (rel != null) {
                if (localStep.getScreenshots() == null) {
                    localStep.setScreenshots(new ArrayList<>());
                }
                localStep.getScreenshots().add(rel);
            }
        } finally {
            if (!shot.delete()) {
                shot.deleteOnExit();
            }
        }
    }

    private void tryCaptureAutomationScreenshotServer(UUID stepId) {
        if (!stepScreenshotCaptureEnabled || stepId == null) {
            return;
        }
        MervClient cli = sharedClient;
        if (cli == null) {
            return;
        }
        AutomationTool tool = threadLocalAutomationTool.get();
        Object drv = threadLocalAutomationDriver.get();
        if (tool == null || drv == null) {
            return;
        }
        File shot = AutomationScreenshotCapturer.captureToTempPng(tool, drv);
        if (shot == null || !shot.exists()) {
            return;
        }
        try {
            cli.uploadFile(stepId, shot, "Step screenshot");
        } catch (MervClientException e) {
            System.err.println("MERV: could not upload step screenshot: " + e.getMessage());
        } finally {
            if (!shot.delete()) {
                shot.deleteOnExit();
            }
        }
    }

    /**
     * Initialize runtime report assets (live HTML + initial JSON snapshot).
     */
    private void initializeLocalRuntimeReporting(String reportFolderPath) {
        try {
            String jsonFolderPath = reportFolderPath + "json" + File.separator;
            String htmlFolderPath = reportFolderPath + "html" + File.separator;
            new File(jsonFolderPath).mkdirs();
            new File(htmlFolderPath).mkdirs();

            writeRunningHtmlSnapshots(reportFolderPath);
            persistLocalRuntimeSnapshot(false);
            System.out.println("Live runtime report initialized: " + htmlFolderPath + "merv-report-live.html");
        } catch (Exception e) {
            System.err.println("Error initializing live runtime report: " + e.getMessage());
        }
    }

    /**
     * Persist current local execution state so live HTML can read realtime status.
     */
    private void persistLocalRuntimeSnapshot(boolean completed) {
        if (currentReportFolderPath == null || localTestSuite == null) {
            return;
        }
        synchronized (localReportLock) {
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

                String json = objectMapper.writeValueAsString(jsonReport);
                FileUtils.writeFile(jsonFolderPath + "merv-report.json", json);
                MervFailureTestJsonWriter.writeFromJsonReport(currentReportFolderPath, jsonReport);
                if (!completed) {
                    writeRunningHtmlSnapshots(currentReportFolderPath);
                }
            } catch (Exception e) {
                System.err.println("Error persisting runtime snapshot: " + e.getMessage());
            }
        }
    }

    /**
     * Build live HTML that polls {@code ../json/merv-report.json} and renders runtime data
     * (same content written to {@code merv-report-live.html} and {@code merv-report.html} while running).
     */
    /**
     * Shared live/static suite HTML renderer used by Cucumber and other runners (e.g. TestNG).
     * HTML is JSON-driven and reads {@code ../json/merv-report.json}.
     */
    public static String buildLiveHtmlReportContent() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html><head><meta charset=\"UTF-8\"><title>Merv Live Report</title>\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n");
        html.append("<link href=\"https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n");
        html.append("<style>");
        html.append(":root{--merv-grad:").append(MervReportBranding.GRADIENT_CSS).append(";--sidebar-bg:#f2f3f5;--sidebar-border:#e6e8ec;--nav-text:#4a4a4a;--nav-muted:#6b6b6b;--nav-active-bg:#fdeaea;--nav-active-text:#c20000;}");
        html.append("html{scroll-behavior:smooth;}");
        html.append("html{font-family:'Roboto',system-ui,-apple-system,sans-serif;}body{font-family:'Roboto',system-ui,-apple-system,sans-serif;margin:0;padding:0;background:#fafafa;color:#333;}button,input,select,textarea{font-family:inherit;}");
        html.append("h1,h2,h3,h4,h5,h6{letter-spacing:0.5px;}");
        html.append(".main-wrapper{display:flex;min-height:100vh;}");
        html.append(".sidebar{width:400px;background:var(--sidebar-bg);color:var(--nav-text);padding:20px 16px;overflow-y:auto;position:fixed;height:100vh;box-shadow:1px 0 0 var(--sidebar-border);box-sizing:border-box;}");
        html.append(".sidebar-brand{margin-bottom:20px;padding-bottom:18px;border-bottom:1px solid var(--sidebar-border);text-align:center;}");
        html.append(".brand-logo{max-width:180px;height:auto;display:block;margin:0 auto;}");
        html.append(".sidebar-local-label{margin:12px 0 0;padding:0;font-size:13px;font-weight:700;color:var(--nav-active-text);letter-spacing:.04em;text-align:center;display:block;text-decoration:none;cursor:pointer;}");
        html.append(".sidebar-local-label:hover{color:#c20000;text-decoration:underline;text-underline-offset:2px;}");
        html.append(".sidebar-search{margin-bottom:16px;}");
        html.append(".sidebar-search input{width:100%;padding:10px 12px;border:1px solid #ddd;border-radius:6px;background:#fff;color:#333;font-size:14px;box-sizing:border-box;}");
        html.append(".sidebar-search input::placeholder{color:#999;}.sidebar-search input:focus{outline:2px solid rgba(233,1,1,.25);border-color:#e90101;}");
        html.append(".sidebar-filters{margin-bottom:16px;display:flex;gap:6px;flex-wrap:wrap;}");
        html.append(".filter-btn{flex:1;min-width:56px;padding:8px 10px;border:1px solid #ddd;border-radius:6px;background:#fff;color:var(--nav-text);font-size:11px;cursor:pointer;}");
        html.append(".filter-btn.active{background:var(--merv-grad);color:#fff;border-color:transparent;font-weight:700;}");
        html.append(".sidebar h2{color:#333;margin-top:0;margin-bottom:10px;font-size:14px;font-weight:700;letter-spacing:.02em;}");
        html.append(".sidebar-item{padding:12px 12px;margin:4px 0;border-radius:6px;cursor:pointer;border-left:4px solid transparent;background:#eceff3;transition:background .15s;}");
        html.append(".sidebar-item:hover{background:#e3e7ec;}");
        html.append(".sidebar-item.passed:not(.active),.sidebar-item.failed:not(.active),.sidebar-item.skipped:not(.active),.sidebar-item.in_progress:not(.active){border-left-color:transparent;}");
        html.append(".sidebar-item.active{background:#eceff3;border-left-color:#e90101;box-shadow:none;}");
        html.append(".sidebar-item-top{display:flex;align-items:center;justify-content:space-between;gap:10px;}");
        html.append(".sidebar-item-name{font-weight:600;color:#222;font-size:14px;display:block;white-space:normal;line-height:1.35;word-break:break-word;overflow-wrap:anywhere;}");
        html.append(".sidebar-status-tag{font-size:10px;font-weight:700;letter-spacing:.04em;padding:2px 8px;border-radius:999px;background:#dfe4ea;color:#111;text-transform:uppercase;white-space:nowrap;}");
        html.append(".sidebar-status-tag.pass{background:#e8f5e9;color:#1b5e20;}.sidebar-status-tag.fail{background:#ffebee;color:#b71c1c;}.sidebar-status-tag.skip{background:#fff8e1;color:#e65100;}.sidebar-status-tag.active{background:#e3f2fd;color:#0d47a1;}");
        html.append(".sidebar-item.active .sidebar-item-name,.sidebar-item.active .sidebar-status-tag{color:#222;font-weight:600;}");
        html.append(".content-area{margin-left:400px;flex:1;padding:20px;}");
        html.append(".report-toolbar{margin-bottom:14px;padding-bottom:12px;border-bottom:1px solid #eee;}");
        html.append(".report-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;}");
        html.append(".shots-toggle{display:inline-flex;align-items:center;gap:8px;font-size:12px;font-weight:700;color:#444;user-select:none;}");
        html.append(".shots-toggle input{width:16px;height:16px;accent-color:#e90101;}");
        html.append("body.hide-shots .screenshots,body.hide-shots .screenshot-movie,body.hide-shots .screenshot-movie-jump{display:none !important;}");
        html.append(".local-dash{color:#c20000;font-weight:600;text-decoration:none;font-size:14px;}");
        html.append(".local-dash:hover{text-decoration:underline;}");
        html.append(".container{max-width:1200px;margin:0 auto;background:#fff;padding:24px;border-radius:10px;box-shadow:0 2px 12px rgba(0,0,0,.08);}");
        html.append("h1{color:#222;border-bottom:4px solid #c20000;padding-bottom:12px;margin-top:0;}");
        html.append(".live-banner{margin:0 0 14px;font-size:12px;color:#666;min-height:1em;}");
        html.append(".report-folder-meta{margin:-8px 0 14px;font-size:12px;color:#4b5563;word-break:break-word;}");
        html.append(".report-run-meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin:18px 0 20px;}");
        html.append(".report-run-meta-item{background:#fafbfc;border:1px solid #e8eaed;border-radius:10px;padding:16px 18px;min-width:0;display:flex;flex-direction:column;gap:8px;box-shadow:0 1px 2px rgba(0,0,0,.04);}");
        html.append(".report-run-meta-item.report-run-meta-duration{border-color:rgba(194,0,0,.22);background:linear-gradient(145deg,#fff 0%,#fff8f7 55%,#fafbfc 100%);}");
        html.append(".report-run-meta-label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.1em;color:#888;}");
        html.append(".report-run-meta-value{font-size:18px;font-weight:600;color:#1a1a1a;line-height:1.45;word-break:break-word;}");
        html.append(".report-run-meta-item.report-run-meta-duration .report-run-meta-value{color:#c20000;font-weight:700;}");
        html.append(".chart-collapsible{margin:0 0 20px 0;border:1px solid #e0e0e0;border-radius:10px;background:#fafafa;overflow:hidden;}");
        html.append(".chart-collapsible-head{display:flex;align-items:center;gap:10px;padding:12px 16px;cursor:pointer;user-select:none;background:linear-gradient(180deg,#fff,#f5f5f5);border-bottom:1px solid #e8e8e8;font-weight:700;font-size:14px;color:#333;}");
        html.append(".chart-collapsible-head:hover{background:#f0f0f0;}");
        html.append(".chart-collapsible-head .chart-caret{display:inline-block;font-size:10px;color:#c20000;transition:transform .2s ease;}");
        html.append(".chart-collapsible-head.collapsed .chart-caret{transform:rotate(-90deg);}");
        html.append(".chart-collapsible-body{padding:18px;}");
        html.append(".chart-collapsible-body.collapsed{display:none;}");
        html.append(".stats-section{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-start;}");
        html.append(".stats-left-col{flex:0 0 40%;max-width:40%;min-width:260px;}");
        html.append(".stats-right-col{flex:1 1 55%;min-width:280px;}");
        html.append("@media(max-width:900px){.stats-left-col,.stats-right-col{flex:1 1 100%;max-width:100%;}}");
        html.append(".stat-row-4{display:flex;flex-direction:row;gap:8px;margin-bottom:16px;}");
        html.append(".stat-row-4 .stat-card{flex:1;min-width:0;padding:12px 6px;}");
        html.append(".stat-row-4 .stat-card h3{font-size:10px;line-height:1.2;hyphens:auto;word-wrap:break-word;}");
        html.append(".stat-card{background-color:#fff;border-radius:8px;text-align:center;box-shadow:0 2px 4px rgba(0,0,0,.08);border:1px solid #eee;}");
        html.append("a.stat-card{text-decoration:none;color:inherit;display:block;cursor:pointer;}");
        html.append("a.stat-card:hover{box-shadow:0 4px 10px rgba(0,0,0,.12);border-color:#ddd;}");
        html.append(".stat-card h3{margin:0 0 6px 0;color:#666;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.03em;line-height:1.2;}");
        html.append(".stat-card .stat-value{font-size:22px;font-weight:700;color:#333;line-height:1.1;}");
        html.append(".stat-card.total .stat-value{color:#c20000;}");
        html.append(".stat-card.passed .stat-value{color:#4CAF50;}.stat-card.failed .stat-value{color:#f44336;}.stat-card.skipped .stat-value{color:#FF9800;}");
        html.append(".pie-chart-container{text-align:center;background:#fff;padding:14px;border-radius:8px;box-shadow:0 2px 4px rgba(0,0,0,.06);border:1px solid #eee;}");
        html.append(".pie-legend{display:flex;justify-content:center;gap:16px;margin-top:12px;flex-wrap:wrap;}");
        html.append(".pie-legend-item{display:flex;align-items:center;gap:6px;}");
        html.append(".pie-legend-color{width:14px;height:14px;border-radius:3px;flex-shrink:0;}");
        html.append(".pie-legend-label{font-size:12px;color:#333;}");
        html.append(".failure-reasons-panel{background:#fff;border:1px solid #eee;border-radius:8px;padding:14px 16px;box-shadow:0 2px 4px rgba(0,0,0,.06);max-height:420px;overflow:auto;}");
        html.append(".failure-reasons-panel h3{margin:0 0 12px 0;font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:#c20000;}");
        html.append(".failure-reason-empty{color:#888;font-size:13px;margin:0;}");
        html.append(".cons-fail-panel{margin-top:14px;border-top:1px dashed #e5e7eb;padding-top:12px;}");
        html.append(".cons-fail-head{display:flex;align-items:center;justify-content:space-between;gap:10px;cursor:pointer;user-select:none;}");
        html.append(".cons-fail-title{margin:0;font-size:13px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:#111;}");
        html.append(".cons-fail-caret{font-size:10px;color:#c20000;transition:transform .2s ease;}");
        html.append(".cons-fail-caret.collapsed{transform:rotate(-90deg);}");
        html.append(".cons-fail-body{margin-top:10px;}");
        html.append(".cons-fail-group{border:1px solid #eef0f3;border-radius:10px;padding:10px 12px;background:#fcfcfd;margin:10px 0;}");
        html.append(".cons-fail-row{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;}");
        html.append(".cons-fail-text{flex:1;min-width:0;font-size:12px;color:#222;word-break:break-word;}");
        html.append(".cons-fail-count{flex-shrink:0;font-weight:900;color:#c82333;}");
        html.append(".cons-fail-toggle{margin-top:8px;font-size:12px;font-weight:700;color:#0d47a1;cursor:pointer;display:inline-block;}");
        html.append(".cons-fail-list{margin-top:8px;display:none;flex-direction:column;gap:6px;}");
        html.append(".cons-fail-list.open{display:flex;}");
        html.append(".cons-fail-case{font-size:12px;color:#374151;}");
        html.append("a.failure-reason-row{text-decoration:none;color:inherit;display:flex;}");
        html.append(".failure-reason-row{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:10px 8px;border-bottom:1px solid #f0f0f0;cursor:pointer;border-radius:6px;margin:0 -4px;}");
        html.append(".failure-reason-row:last-child{border-bottom:none;}");
        html.append(".failure-reason-row:hover{background:#fff5f5;}");
        html.append(".failure-reason-text{flex:1;min-width:0;font-size:13px;color:#333;line-height:1.35;word-break:break-word;}");
        html.append(".failure-reason-count{flex-shrink:0;font-weight:700;font-size:13px;color:#f44336;min-width:2em;text-align:right;}");
        html.append(".failure-reason-group{border:1px solid #eef0f3;border-radius:8px;padding:8px 10px;margin:8px 0;background:#fcfcfd;}");
        html.append(".failure-related-cases{display:flex;flex-direction:column;gap:8px;margin-top:6px;}");
        html.append(".failure-case-item{display:flex;align-items:center;justify-content:space-between;gap:10px;}");
        html.append(".failure-case-link{border:none;background:transparent;color:#0d47a1;font-size:13px;font-weight:600;cursor:pointer;padding:0;text-align:left;text-decoration:underline;text-underline-offset:2px;}");
        html.append(".failure-case-link:hover{color:#08306f;}");
        html.append(".failure-case-thumb{width:72px;height:42px;object-fit:cover;border:1px solid #d7dce2;border-radius:6px;cursor:pointer;flex-shrink:0;}");
        html.append(".screenshot-movie-jump{margin:6px 0 14px 0;font-size:13px;}");
        html.append(".screenshot-movie-jump a{color:#c20000;font-weight:600;text-decoration:none;border-bottom:1px solid rgba(194,0,0,.35);}");
        html.append(".screenshot-movie-jump a:hover{border-bottom-color:#c20000;}");
        html.append(".screenshot-movie{margin:18px 0 22px;padding:16px 18px;background:linear-gradient(180deg,#2a2a2a 0%,#1a1a1a 100%);border-radius:10px;border:1px solid #404040;scroll-margin-top:20px;}");
        html.append(".screenshot-movie-title{margin:0 0 4px 0;color:#fff;font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.08em;}");
        html.append(".screenshot-movie-hint{margin:0 0 12px 0;color:#888;font-size:11px;}");
        html.append(".screenshot-movie-stage{width:100%;max-width:880px;margin:0 auto;background:#000;border-radius:8px;overflow:hidden;aspect-ratio:16/10;display:flex;align-items:center;justify-content:center;min-height:160px;}");
        html.append(".screenshot-movie-img{max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;cursor:pointer;}");
        html.append(".screenshot-movie-bar{display:flex;justify-content:space-between;align-items:center;margin-top:12px;padding:0 4px;color:#aaa;font-size:12px;}");
        html.append(".screenshot-movie-counter{font-variant-numeric:tabular-nums;font-weight:600;color:#ccc;}");
        html.append(".screenshot-movie-controls{display:flex;justify-content:center;align-items:center;gap:10px;margin:4px 0 10px;flex-wrap:wrap;}");
        html.append(".screenshot-movie-btn{min-width:42px;height:42px;padding:0 10px;border-radius:8px;border:1px solid #555;background:#333;color:#fff;font-size:17px;cursor:pointer;line-height:1;display:inline-flex;align-items:center;justify-content:center;}");
        html.append(".screenshot-movie-btn:hover:not(:disabled){background:#444;border-color:#c20000;}");
        html.append(".screenshot-movie-btn:disabled{opacity:.35;cursor:not-allowed;}");
        html.append(".test-case-content{display:none;}.test-case-content.active{display:block;}");
        html.append("#testcase-content{scroll-margin-top:16px;}");
        html.append(".step-logs{margin-top:10px;}.step-logs p{color:#333;font-weight:700;margin-bottom:8px;}");
        html.append(".log-container{background:#f5f5f5;border:1px solid #ddd;border-radius:4px;padding:10px;max-height:400px;overflow-y:auto;font-family:'Courier New',monospace;font-size:12px;}");
        html.append(".log-line{padding:3px 0;border-bottom:1px solid #eee;white-space:pre-wrap;word-wrap:break-word;}");
        html.append(".log-line:last-child{border-bottom:none;}");
        html.append(".log-info{color:#333;}.log-error{color:#d32f2f;font-weight:700;background:#ffebee;padding:2px 5px;border-radius:2px;}");
        html.append(".log-warn{color:#f57c00;background:#fff3e0;padding:2px 5px;border-radius:2px;}.log-debug{color:#616161;}");
        html.append(".test-case{margin:15px 0;padding:15px;border-left:4px solid #ddd;background-color:#f9f9f9;color:#333;}.test-case.passed{border-left-color:#4CAF50;}.test-case.failed{border-left-color:#f44336;}.test-case.skipped{border-left-color:#FF9800;}.test-case.in_progress{border-left-color:#2196F3;}");
        html.append(".testcase-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:10px 12px;margin:-2px -2px 12px;background:#fff;border:1px solid #eceff3;border-radius:10px;box-shadow:0 1px 2px rgba(0,0,0,.04);}");
        html.append(".testcase-title{margin:0;font-size:26px;font-weight:700;letter-spacing:.01em;color:#042664;line-height:1.3;word-break:break-word;}");
        html.append(".testcase-status-chip{display:inline-flex;align-items:center;justify-content:center;padding:6px 12px;border-radius:999px;font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;border:1px solid #d0d7de;background:#eef2f6;color:#364152;white-space:nowrap;}");
        html.append(".testcase-status-chip.passed{background:#e8f5e9;color:#1b5e20;border-color:#c8e6c9;}");
        html.append(".testcase-status-chip.failed{background:#ffebee;color:#b71c1c;border-color:#ffcdd2;}");
        html.append(".testcase-status-chip.skipped{background:#fff8e1;color:#e65100;border-color:#ffe0b2;}");
        html.append(".testcase-status-chip.in_progress{background:#e3f2fd;color:#0d47a1;border-color:#bbdefb;}");
        html.append(".testcase-meta-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;margin:0 0 12px;}");
        html.append(".testcase-meta-item{background:#fff;border:1px solid #eceff3;border-radius:10px;padding:10px 12px;box-shadow:0 1px 2px rgba(0,0,0,.03);}");
        html.append(".testcase-meta-label{display:block;font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#6b7280;margin-bottom:4px;}");
        html.append(".testcase-meta-value{font-size:13px;font-weight:600;color:#1f2937;word-break:break-word;}");
        html.append(".testcase-tags{display:flex;flex-wrap:wrap;gap:8px;margin:6px 0 10px;}");
        html.append(".testcase-tag{display:inline-flex;align-items:center;height:20px;padding:0 10px;border-radius:999px;background:#eaf1ff;border:1px solid #cfdcff;color:#153a7a;font-size:13px;font-weight:600;line-height:20px;}");
        html.append(".test-step{margin:8px 0;padding:8px;background-color:#fff;border-radius:3px;color:#333;}.test-step.passed{border-left:3px solid #4CAF50;}.test-step.failed{border-left:3px solid #f44336;}.test-step.skipped{border-left:3px solid #FF9800;}.test-step.in_progress{border-left:3px solid #ffc107;}");
        html.append(".test-step-hd{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin:0 0 6px;flex-wrap:wrap;}");
        html.append(".test-step-title{margin:0;flex:1;min-width:0;font-size:14px;font-weight:600;color:#333;line-height:1.35;word-break:break-word;}");
        html.append(".test-step-title.has-shot{cursor:pointer;}.test-step-title.has-shot:hover,.test-step-title.has-shot.shots-open{color:#c20000;}");
        html.append(".test-step-title .step-shot-icon{margin-right:6px;opacity:.85;font-size:13px;}");
        html.append(".test-step-hd.has-shot-hd{cursor:pointer;}.test-step-hd.has-shot-hd:hover .test-step-title.has-shot{color:#c20000;}");
        html.append(".step-screenshots{display:none;margin-top:8px;}.step-screenshots.open{display:block !important;}");
        html.append("body.hide-shots .step-screenshots.open{display:block !important;}");
        html.append(".test-step-badges{display:inline-flex;align-items:center;gap:10px;flex-shrink:0;margin-left:auto;}");
        html.append(".step-type-pill{display:inline-flex;align-items:center;padding:3px 10px;border-radius:999px;font-size:10px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;border:1px solid #d0d7de;background:#eef2f6;color:#364152;white-space:nowrap;}");
        html.append(".step-type-pill.data{background:#eaf1ff;color:#153a7a;border-color:#cfdcff;}.step-type-pill.assert{background:#fff0f0;color:#8a1f1f;border-color:#ffd4d4;}.step-type-pill.info{background:#f3f4f6;color:#374151;border-color:#e5e7eb;}");
        html.append(".step-meta{margin:6px 0 0 0;padding:8px 10px;background:#f7f8fa;border:1px solid #e6e8ec;border-radius:8px;font-size:12px;}");
        html.append(".step-meta-row{display:flex;gap:10px;align-items:flex-start;flex-wrap:wrap;margin:2px 0;}");
        html.append(".step-meta-key{min-width:88px;font-weight:700;color:#555;text-transform:uppercase;letter-spacing:.06em;font-size:10px;}");
        html.append(".step-meta-val{flex:1;min-width:0;color:#222;white-space:pre-wrap;word-break:break-word;}");
        MervTestDataFileHtml.appendStyles(html);
        html.append(".step-status-pill{display:inline-flex;align-items:center;padding:3px 10px;border-radius:999px;font-size:10px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;border:1px solid #d0d7de;background:#eef2f6;color:#364152;white-space:nowrap;}");
        html.append(".step-status-pill.passed{background:#e8f5e9;color:#1b5e20;border-color:#c8e6c9;}");
        html.append(".step-status-pill.failed{background:#ffebee;color:#b71c1c;border-color:#ffcdd2;}");
        html.append(".step-status-pill.skipped{background:#fff8e1;color:#e65100;border-color:#ffe0b2;}");
        html.append(".step-status-pill.data{display:none;}");
        html.append(".step-status-pill.in_progress{background:#fff8e1;color:#f57f17;border-color:#ffe082;}");
        html.append(".step-delta{font-size:12px;font-weight:600;color:#5c5f66;font-variant-numeric:tabular-nums;min-width:6.5em;text-align:right;white-space:nowrap;}");
        html.append(".error{color:#f44336;font-size:.9em;margin-top:5px;background-color:#ffebee;padding:8px;border-radius:3px;border-left:3px solid #f44336;}");
        html.append(".screenshots{margin-top:10px;}.screenshot{margin:10px 0;}.screenshot img{display:block;max-width:800px;margin:10px 0;border:1px solid #ddd;border-radius:4px;cursor:pointer;}");
        html.append(".status-pill{display:inline-block;padding:4px 12px;border-radius:20px;background:").append(MervReportBranding.GRADIENT_CSS).append(";color:#fff;font-size:11px;font-weight:700;margin-left:8px;text-transform:uppercase;letter-spacing:.04em;}");
        html.append(".status-pill.aborted{background:#6c757d!important;}");
        html.append(".sidebar-filters-wrap{margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid #e8eaed;}");
        html.append(".sidebar-search-row{display:flex;align-items:stretch;gap:8px;}.sidebar-search-row .sidebar-search{flex:1;min-width:0;margin-bottom:0;}");
        html.append(".sidebar-filter-btn{flex-shrink:0;width:42px;padding:0;border:1px solid #ddd;border-radius:6px;background:#fff;color:#555;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;}");
        html.append(".sidebar-filter-btn[aria-expanded='true']{background:#fdeaea;border-color:#e90101;color:#e90101;}");
        html.append(".suite-tag-panel{margin-top:10px;padding:12px;background:#fff;border:1px solid #e8eaed;border-radius:8px;max-height:min(40vh,320px);overflow:auto;}.suite-tag-panel[hidden]{display:none!important;}");
        html.append(".suite-adv-label{font-size:10px;font-weight:700;text-transform:uppercase;color:#6b7280;margin:0 0 6px;}.suite-adv-tag-cloud{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:12px;}");
        html.append(".suite-adv-tag-pill{border:1px solid #d6dbe2;background:#f8f9fa;border-radius:999px;padding:4px 9px;font-size:11px;font-weight:600;cursor:pointer;}.suite-adv-tag-pill.selected{background:#fdeaea;border-color:#e90101;color:#e90101;}");
        html.append(".suite-adv-tag-empty{font-size:11px;color:#9ca3af;}.suite-adv-clear{padding:6px 10px;border:1px solid #d6dbe2;border-radius:6px;background:#fff;font-size:11px;cursor:pointer;}");
        html.append(".report-toolbar-links{display:flex;flex-wrap:wrap;gap:12px;align-items:center;}.failure-json-link{color:#b71c1c;font-weight:600;text-decoration:none;font-size:13px;}");
        html.append(".failure-json-link[hidden]{display:none!important;}.failure-json-link.is-empty{opacity:.55;pointer-events:none;}");
        html.append(".sidebar-item-tags{display:flex;flex-wrap:wrap;gap:4px;margin-top:4px;}.sidebar-tag-pill{font-size:10px;padding:2px 6px;border-radius:999px;background:#eaf1ff;border:1px solid #cfdcff;color:#153a7a;}");
        html.append("</style></head><body>");
        html.append("<div class='main-wrapper'><div class='sidebar'>");
        html.append("<div class='sidebar-brand'><img class='brand-logo' src='").append(MervReportBranding.LOGO_URL).append("' alt='Merv'><a class='sidebar-local-label' href='../../index.html' title='Open local dashboard'>Merv Local</a></div>");
        html.append("<div class='sidebar-filters-wrap' id='sidebar-filters-wrap'>");
        html.append("<div class='sidebar-filters' role='group' aria-label='Filter by status'>");
        html.append("<button type='button' class='filter-btn active' data-status='all'>All</button>");
        html.append("<button type='button' class='filter-btn' data-status='passed'>Pass</button>");
        html.append("<button type='button' class='filter-btn' data-status='failed'>Fail</button>");
        html.append("<button type='button' class='filter-btn' data-status='skipped'>Skip</button>");
        html.append("</div>");
        html.append("<div class='sidebar-search-row'><div class='sidebar-search'><input type='search' id='testcase-search' placeholder='Search test cases\u2026' autocomplete='off'></div>");
        html.append("<button type='button' class='sidebar-filter-btn' id='sidebar-filter-btn' aria-expanded='false' aria-controls='suite-tag-panel' title='Filter by tags'><svg width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'><polygon points='22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3'></polygon></svg></button>");
        html.append("</div>");
        html.append("<div class='suite-tag-panel' id='suite-tag-panel' hidden>");
        html.append("<p class='suite-adv-label'>Tags</p>");
        html.append("<div id='suite-adv-tag-cloud' class='suite-adv-tag-cloud'><span class='suite-adv-tag-empty'>Loading tags\u2026</span></div>");
        html.append("<div class='suite-adv-actions'><button type='button' class='suite-adv-clear' id='suite-adv-clear'>Clear tag filters</button><span id='suite-adv-filter-status' aria-live='polite'></span></div>");
        html.append("</div></div><h2>Test Cases</h2><div id='sidebar-list'></div></div>");

        html.append("<div class='content-area'><div class='container'>");
        html.append("<div class='report-toolbar'><div class='report-toolbar-links'><a class='local-dash' href='../../index.html'>Local Dashboard</a><a id='failure-json-link' class='failure-json-link' href='../json/failure-test.json' target='_blank' rel='noopener noreferrer' hidden>Failures JSON</a></div><label class='shots-toggle'><input id='toggle-shots' type='checkbox' checked> Show screenshots</label></div>");
        html.append("<h1 id='suite-title'>Merv Live Runtime Report <span id='run-state' class='status-pill'>RUNNING</span></h1>");
        html.append("<p id='live-banner' class='live-banner'></p>");
        html.append("<p id='report-folder' class='report-folder-meta'></p>");
        html.append("<div class=\"chart-collapsible\"><div class=\"chart-collapsible-head\" id=\"chart-block-head\" role=\"button\" tabindex=\"0\" aria-expanded=\"true\" onclick=\"toggleChartBlock()\" onkeydown=\"if(event.key==='Enter'||event.key===' '){event.preventDefault();toggleChartBlock();}\"><span class=\"chart-caret\" aria-hidden=\"true\">&#9660;</span><span>Summary &amp; charts</span></div>");
        html.append("<div class=\"chart-collapsible-body\" id=\"chart-block-body\"><div class=\"stats-section\"><div class=\"stats-left-col\">");
        html.append("<div class=\"stat-row-4\"><a href=\"#\" class=\"stat-card total\" style=\"background:white;\" onclick=\"return filterShowAllTestCases();\" title=\"Show all test cases in the sidebar\"><h3>Total</h3><div id=\"total-count\" class=\"stat-value\">0</div></a>");
        html.append("<div class=\"stat-card passed\"><h3>Passed</h3><div id=\"passed-count\" class=\"stat-value\">0</div></div>");
        html.append("<div class=\"stat-card failed\"><h3>Failed</h3><div id=\"failed-count\" class=\"stat-value\">0</div></div>");
        html.append("<div class=\"stat-card skipped\"><h3>Skipped</h3><div id=\"skipped-count\" class=\"stat-value\">0</div></div></div>");
        html.append("<div class=\"pie-chart-container\"><canvas id=\"pieChart\" width=\"300\" height=\"300\" data-passed=\"0\" data-failed=\"0\" data-skipped=\"0\"></canvas>");
        html.append("<div class=\"pie-legend\"><span class=\"pie-legend-item\"><span class=\"pie-legend-color\" style=\"background:#4CAF50\"></span><span id=\"leg-p\" class=\"pie-legend-label\">Passed (0)</span></span>");
        html.append("<span class=\"pie-legend-item\"><span class=\"pie-legend-color\" style=\"background:#f44336\"></span><span id=\"leg-f\" class=\"pie-legend-label\">Failed (0)</span></span>");
        html.append("<span class=\"pie-legend-item\"><span class=\"pie-legend-color\" style=\"background:#FF9800\"></span><span id=\"leg-k\" class=\"pie-legend-label\">Skipped (0)</span></span></div></div></div>");
        html.append("<div class=\"stats-right-col\"><div class=\"failure-reasons-panel\" id=\"failure-reasons-panel\"><h3>Failure reasons</h3><p class=\"failure-reason-empty\">No failures yet.</p></div></div></div></div></div>");
        html.append("<div class=\"report-run-meta\" role=\"group\" aria-label=\"Run timing\">");
        html.append("<div class=\"report-run-meta-item\"><span class=\"report-run-meta-label\">Start</span><span class=\"report-run-meta-value\" id=\"run-meta-start\">\u2014</span></div>");
        html.append("<div class=\"report-run-meta-item\"><span class=\"report-run-meta-label\">End</span><span class=\"report-run-meta-value\" id=\"run-meta-end\">\u2014</span></div>");
        html.append("<div class=\"report-run-meta-item report-run-meta-duration\"><span class=\"report-run-meta-label\">Duration</span><span class=\"report-run-meta-value\" id=\"run-meta-duration\">\u2014</span></div>");
        html.append("</div>");
        html.append("<div id='testcase-content'></div></div></div></div>");
        html.append("<script>");
        html.append("function e(s){return String(s||'').replace(/[&<>\\\"']/g,function(c){return({'&':'&amp;','<':'&lt;','>':'&gt;','\\\"':'&quot;',\"'\":'&#39;'})[c];});}");
        html.append("function c(v){return String(v||'').toLowerCase();}");
        html.append("var currentFilter='all';var currentSearch='';var suiteAdvSelectedTags={};var suiteStatusFilters={passed:true,failed:true,skipped:true,in_progress:true};var SUITE_TAG_PANEL_KEY='merv.suite.tagPanel.open';var selectedId='';var openStepShots={};var latestData=null;var pollTimer=null;var requestedTestcaseName=(function(){try{return (new URLSearchParams(window.location.search).get('testcase')||'').trim().toLowerCase();}catch(e){return'';}})();var appliedRequestedCase=false;");
        html.append("function decodeUi(s){if(s==null)return'';try{return decodeURIComponent(String(s));}catch(e){return String(s);}}");
        html.append("function detectReportFolderName(){try{var p=String(window.location.pathname||'').replace(/\\\\/g,'/');var parts=p.split('/').filter(function(x){return x&&x!=='.';});var htmlIdx=parts.lastIndexOf('html');if(htmlIdx>0)return decodeUi(parts[htmlIdx-1]);if(parts.length>1)return decodeUi(parts[parts.length-2]);if(parts.length)return decodeUi(parts[0]);}catch(e){}return'—';}");
        html.append("(function renderFolderMeta(){var el=document.getElementById('report-folder');if(!el)return;el.innerHTML='<strong>Folder name:</strong> '+e(detectReportFolderName());})();");
        html.append("var STALE_MS=").append(MervReportBranding.LOCAL_RUN_STALE_AFTER_MS).append(";");
        html.append("function lastActivityMs(d){var n=d&&d.lastActivityMillis;if(typeof n==='number'&&n>0)return n;var p=Date.parse(String((d&&d.exportDate)||''));return isNaN(p)?0:p;}");
        html.append("function isStaleAborted(d){if(!d||d.running!==true)return false;if(d.aborted===true)return true;var la=lastActivityMs(d);if(la<=0)return false;return Date.now()-la>STALE_MS;}");
        html.append("function stopPolling(){if(pollTimer!==null){clearInterval(pollTimer);pollTimer=null;}}");
        html.append("function statusClass(st){var x=c(st);return x==='passed'||x==='failed'||x==='skipped'||x==='in_progress'?x:'in_progress';}");
        html.append("function statusTag(st){var x=c(st);if(x==='passed')return{txt:'PASS',cls:'pass'};if(x==='failed')return{txt:'FAIL',cls:'fail'};if(x==='skipped')return{txt:'SKIP',cls:'skip'};return{txt:'ACTIVE',cls:'active'};}");
        html.append("function fmtStepDelta(ms){if(ms==null||ms<0||isNaN(ms))return'\u2014';var s=Math.floor(ms/1000);var m=Math.floor(ms%1000);return s+'s '+m+'ms';}");
        html.append("function stepSuiteRunning(){try{return !!(latestData&&latestData.running===true&&!isStaleAborted(latestData));}catch(e){return false;}}");
        html.append("function hasPriorFailedStep(steps,si){return false;}");
        html.append("function stepRowUi(step){var ty=stepTypeClass(step);if(ty&&ty.cls==='data')return{p:'',k:'data'};var raw=c(step&&step.status||'');var end=parseTs(step&&step.endTime);if(raw==='pending')return{p:'SKIP',k:'skipped'};if(raw==='skipped')return{p:'SKIP',k:'skipped'};if(raw==='passed')return{p:'PASS',k:'passed'};if(raw==='failed')return{p:'FAIL',k:'failed'};if(raw==='in_progress'){if(!end&&!stepSuiteRunning())return{p:'SKIP',k:'skipped'};return{p:'IN PROGRESS',k:'in_progress'};}if(!raw)return{p:'SKIP',k:'skipped'};return{p:'SKIP',k:'skipped'};}");
        html.append("function drawPieChart(passed,failed,skipped){var canvas=document.getElementById('pieChart');if(!canvas)return;var ctx=canvas.getContext('2d');var w=canvas.width,h=canvas.height,cx=w/2,cy=h/2,r=Math.min(w,h)*0.36;ctx.clearRect(0,0,w,h);var total=passed+failed+skipped;if(total===0){ctx.fillStyle='#eee';ctx.beginPath();ctx.arc(cx,cy,r,0,2*Math.PI);ctx.fill();return;}var start=-Math.PI/2;var pa=(passed/total)*2*Math.PI,fa=(failed/total)*2*Math.PI,ka=(skipped/total)*2*Math.PI;if(passed>0){ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+pa);ctx.closePath();ctx.fillStyle='#4CAF50';ctx.fill();start+=pa;}if(failed>0){ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+fa);ctx.closePath();ctx.fillStyle='#f44336';ctx.fill();start+=fa;}if(skipped>0){ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+ka);ctx.closePath();ctx.fillStyle='#FF9800';ctx.fill();}ctx.beginPath();ctx.arc(cx,cy,r,0,2*Math.PI);ctx.strokeStyle='#fff';ctx.lineWidth=2;ctx.stroke();}");
        html.append("function toggleChartBlock(){var body=document.getElementById('chart-block-body');var head=document.getElementById('chart-block-head');if(!body||!head)return;var collapsed=body.classList.toggle('collapsed');head.classList.toggle('collapsed',collapsed);head.setAttribute('aria-expanded',collapsed?'false':'true');if(!collapsed&&typeof drawPieChart==='function'&&latestData&&latestData.testSuite){var tc=latestData.testSuite.testCases||[],p=0,f=0,k=0;tc.forEach(function(t){if(t.status==='PASSED')p++;else if(t.status==='FAILED')f++;else if(t.status==='SKIPPED')k++;});drawPieChart(p,f,k);}}");
        html.append("function parseTs(v){if(v==null||v===undefined)return null;if(typeof v==='number'){var n=v;return new Date(n>1e11?n:n*1000);}if(typeof v==='string'){var s=new Date(v);return isNaN(s.getTime())?null:s;}if(typeof v==='object'&&v){if(typeof v.time==='number')return new Date(v.time);if(Array.isArray(v)&&v.length>=3)return new Date(v[0],(v[1]||1)-1,v[2]||1,v[3]||0,v[4]||0,v[5]||0);}var t=new Date(v);return isNaN(t.getTime())?null:t;}");
        html.append("function fmtHms(ms){var d=new Date(ms);function z(x){return(x<10?'0':'')+x;}return z(d.getHours())+':'+z(d.getMinutes())+':'+z(d.getSeconds());}");
        html.append("function floor5s(t){return Math.floor(t/5000)*5000;}");
        html.append("function drawTrendChart(tc,suite,running){try{var SLOT=5000;var canvas=document.getElementById('trendChart');if(!canvas)return;var ctx=canvas.getContext('2d');if(!ctx)return;var W=canvas.width,H=canvas.height;if(W<10||H<10)return;ctx.clearRect(0,0,W,H);var padL=40,padR=8,padT=4,padB=40,pw=W-padL-padR,ph=H-padT-padB;var passC={},failC={};(tc||[]).forEach(function(row){var et=parseTs(row.endTime);if(!et)return;var bk=floor5s(et.getTime());var st=String(row.status||'').toUpperCase();if(st==='PASSED')passC[bk]=(passC[bk]||0)+1;else if(st==='FAILED')failC[bk]=(failC[bk]||0)+1;});var suiteStart=suite&&parseTs(suite.startTime);var nowMs=Date.now();var minMs=null,maxMs=null;if(suiteStart)minMs=floor5s(suiteStart.getTime());function upd(ms){if(minMs==null||ms<minMs)minMs=ms;if(maxMs==null||ms>maxMs)maxMs=ms;}Object.keys(passC).forEach(function(k){upd(+k);});Object.keys(failC).forEach(function(k){upd(+k);});if(minMs==null){minMs=floor5s(nowMs);maxMs=minMs;}if(maxMs==null)maxMs=minMs;var curSlot=floor5s(nowMs);if(running&&curSlot>maxMs)maxMs=curSlot;if(maxMs<minMs)maxMs=minMs;var buckets=[];for(var m=minMs;m<=maxMs;m+=SLOT)buckets.push({t:m,p:passC[m]||0,f:failC[m]||0});if(buckets.length>180)buckets=buckets.slice(-180);var n=buckets.length;if(n===0){buckets.push({t:minMs,p:0,f:0});n=1;}var peaks=buckets.map(function(bk){return Math.max(bk.p,bk.f);});var maxY=Math.max(1,peaks.length?Math.max.apply(null,peaks):0);function xCenter(i){return padL+(n<=1?pw/2:(i/(n-1))*pw);}function yVal(v){return padT+ph-(v/maxY)*ph;}ctx.strokeStyle='#ddd';ctx.lineWidth=1;ctx.setLineDash([3,4]);var yTicks=4,yi,xi;for(yi=0;yi<=yTicks;yi++){var yy=padT+(yi/yTicks)*ph;ctx.beginPath();ctx.moveTo(padL,yy);ctx.lineTo(padL+pw,yy);ctx.stroke();}var xStep=Math.max(1,Math.ceil(n/8));for(xi=0;xi<n;xi+=xStep){var xx=xCenter(xi);ctx.beginPath();ctx.moveTo(xx,padT);ctx.lineTo(xx,padT+ph);ctx.stroke();}ctx.setLineDash([]);ctx.strokeStyle='#333';ctx.beginPath();ctx.moveTo(padL,padT+ph);ctx.lineTo(padL+pw,padT+ph);ctx.stroke();ctx.beginPath();ctx.moveTo(padL,padT);ctx.lineTo(padL,padT+ph);ctx.stroke();var slotW=pw/Math.max(n,1);var gw=Math.min(slotW*0.85,36);var barW=Math.max(2,(gw-4)/2);buckets.forEach(function(bk,idx){var cx=xCenter(idx),x0=cx-gw/2,y0=padT+ph;if(bk.p>0){var y1=yVal(bk.p);ctx.fillStyle='#4CAF50';ctx.fillRect(x0,y1,barW,y0-y1);}if(bk.f>0){var y2=yVal(bk.f);ctx.fillStyle='#f44336';ctx.fillRect(x0+barW+4,y2,barW,y0-y2);}});ctx.fillStyle='#555';ctx.font='11px Arial';ctx.textAlign='right';ctx.textBaseline='middle';for(yi=0;yi<=yTicks;yi++){var yv=Math.round((yTicks-yi)*(maxY/yTicks));var yy2=padT+(yi/yTicks)*ph;ctx.fillText(String(yv),padL-4,yy2);}ctx.fillStyle='#444';ctx.font='9px Arial';ctx.textAlign='right';ctx.textBaseline='top';for(xi=0;xi<n;xi+=xStep){var lbl=fmtHms(buckets[xi].t);var xx2=xCenter(xi);ctx.save();ctx.translate(xx2,padT+ph+4);ctx.rotate(-Math.PI/5);ctx.fillText(lbl,0,0);ctx.restore();}var tPass=buckets.reduce(function(a,bk){return a+bk.p;},0),tFail=buckets.reduce(function(a,bk){return a+bk.f;},0);var el=document.getElementById('trend-meta');if(el)el.textContent=tPass+' pass · '+tFail+' fail in window · '+n+' × 5s bucket(s)'+(running?' · refresh 5s':'');}catch(err){var em=document.getElementById('trend-meta');if(em)em.textContent='Trend chart could not render: '+(err&&err.message?err.message:String(err));}}");
        html.append("function initConsolidatedFailuresToggle(){var head=document.getElementById('cons-fail-head');var caret=document.getElementById('cons-fail-caret');var body=document.getElementById('cons-fail-body');if(!head||!caret||!body)return;var collapsed=false;function apply(){body.style.display=collapsed?'none':'block';caret.classList.toggle('collapsed',collapsed);}function toggle(){collapsed=!collapsed;apply();}head.addEventListener('click',toggle);head.addEventListener('keydown',function(ev){if(ev.key==='Enter'||ev.key===' '){ev.preventDefault();toggle();}});apply();}");
        html.append("async function loadConsolidatedFailures(){var el=document.getElementById('cons-fail-body');if(!el)return;var ts='?ts='+Date.now();var paths=['../../consolidated-failure-reasons.json'+ts,'../consolidated-failure-reasons.json'+ts,'./../../consolidated-failure-reasons.json'+ts];for(var i=0;i<paths.length;i++){try{var r=await fetch(paths[i],{cache:'no-store'});if(!r.ok)continue;var d=await r.json();renderConsolidatedFailures(d);return;}catch(e){}}el.innerHTML='<p class=\"failure-reason-empty\">No consolidated data found.</p>';}");
        html.append("function renderConsolidatedFailures(d){var el=document.getElementById('cons-fail-body');if(!el)return;var arr=(d&&d.failureReasons)||[];if(!arr.length){el.innerHTML='<p class=\"failure-reason-empty\">No failures in latest runs.</p>';return;}var h='';arr.forEach(function(gr,idx){var reason=String(gr.reason||'');var cnt=+gr.count||0;var cases=(gr.testcases)||[];var gid='cons-f-'+idx;h+='<div class=\"cons-fail-group\"><div class=\"cons-fail-row\"><div class=\"cons-fail-text\">'+e(reason)+'</div><div class=\"cons-fail-count\">'+cnt+'</div></div>';h+='<span class=\"cons-fail-toggle\" data-g=\"'+gid+'\">Expand / Collapse</span>';h+='<div class=\"cons-fail-list\" id=\"'+gid+'\">';cases.forEach(function(ca){h+='<div class=\"cons-fail-case\">'+e(ca.testcaseName||'')+'</div>';});h+='</div></div>';});el.innerHTML=h;el.querySelectorAll('.cons-fail-toggle[data-g]').forEach(function(tg){tg.addEventListener('click',function(){var id=tg.getAttribute('data-g');var box=document.getElementById(id);if(!box)return;box.classList.toggle('open');});});}");
        html.append("function renderFailureReasons(testCases){var panel=document.getElementById('failure-reasons-panel');if(!panel)return;var groups={};(testCases||[]).forEach(function(t,i){if(String(t.status||'').toUpperCase()!=='FAILED')return;var r=String(t.failureReason||'').trim();if(!r)r='(No failure message)';var firstShot='';var steps=t.testSteps||[];for(var si=0;si<steps.length&&!firstShot;si++){var ss=(steps[si]&&steps[si].screenshots)||[];if(ss.length)firstShot=String(ss[0]||'');}if(!groups[r])groups[r]=[];groups[r].push({id:'tc-'+i,name:String(t.testcaseName||('Test case '+(i+1))),shot:firstShot});});var keys=Object.keys(groups).sort(function(a,b){return groups[b].length-groups[a].length;});var h='<h3>Failure reasons</h3>';if(!keys.length){h+='<p class=\"failure-reason-empty\">No failures yet.</p>';}else{keys.forEach(function(k){var arr=groups[k]||[];h+='<div class=\"failure-reason-group\"><div class=\"failure-reason-row\"><span class=\"failure-reason-text\">'+e(k)+'</span><span class=\"failure-reason-count\">'+arr.length+'</span></div><div class=\"failure-related-cases\">';arr.forEach(function(ca){h+='<div class=\"failure-case-item\"><button type=\"button\" class=\"failure-case-link\" data-case-id=\"'+e(ca.id)+'\">'+e(ca.name)+'</button>';if(ca.shot){var sp=('../'+String(ca.shot)).replace(/\\\\/g,'/');h+='<img class=\"failure-case-thumb\" src=\"'+e(sp)+'\" alt=\"Screenshot\" onclick=\"window.open(this.src,\\'_blank\\')\">';}h+='</div>';});h+='</div></div>';});}\n");
        html.append("h+='<div class=\"cons-fail-panel\"><div class=\"cons-fail-head\" id=\"cons-fail-head\" role=\"button\" tabindex=\"0\"><div class=\"cons-fail-title\">Consolidated failure reasons (latest)</div><div class=\"cons-fail-caret\" id=\"cons-fail-caret\" aria-hidden=\"true\">&#9660;</div></div><div class=\"cons-fail-body\" id=\"cons-fail-body\"><p class=\"failure-reason-empty\">Loading…</p></div></div>';panel.innerHTML=h;panel.querySelectorAll('.failure-case-link[data-case-id]').forEach(function(btn){btn.addEventListener('click',function(){var cid=btn.getAttribute('data-case-id');if(!cid)return;selectedId=cid;renderSelectedCase((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);renderSidebar((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);setTimeout(scrollToTestcasePanel,0);});});initConsolidatedFailuresToggle();loadConsolidatedFailures();}");
        html.append("function renderSidebar(testCases){var h='';testCases.forEach(function(t,i){var id='tc-'+i;var cls=statusClass(t.status);var tg=statusTag(t.status);var tagAttr=(t.tags&&t.tags.length)?t.tags.map(function(x){return String(x||'').trim();}).filter(Boolean).join('|'):'';var tagHtml='';if(t.tags&&t.tags.length){var pills='';t.tags.forEach(function(tag){pills+='<span class=\"sidebar-tag-pill\">'+e(tag)+'</span>';});tagHtml='<div class=\"sidebar-item-tags\">'+pills+'</div>';}h+='<div class=\"sidebar-item '+cls+(selectedId===id?' active':'')+'\" data-id=\"'+id+'\" data-status=\"'+cls+'\" data-tags=\"'+e(tagAttr)+'\"><div class=\"sidebar-item-top\"><div class=\"sidebar-item-name\">'+e(t.testcaseName||'N/A')+'</div><span class=\"sidebar-status-tag '+tg.cls+'\">'+tg.txt+'</span></div>'+tagHtml+'</div>';});document.getElementById('sidebar-list').innerHTML=h;bindSidebarEvents();applySidebarFilters();}");
        html.append("function stepTypeClass(st){var t=String((st&&st.stepType)||'').toUpperCase();if(t.indexOf('DATA')>=0)return{txt:'DATA',cls:'data'};if(t.indexOf('ASSERT')>=0)return{txt:'ASSERT',cls:'assert'};if(t.indexOf('PREREQ')>=0||t.indexOf('INFO')>=0||t.indexOf('INFORMATION')>=0)return{txt:'INFO',cls:'info'};if(t.indexOf('CONFIG')>=0)return{txt:'CONFIG',cls:'info'};if(t.indexOf('CUSTOM')>=0)return{txt:'CUSTOM',cls:'info'};return null;}");
        html.append("function addMetaRows(st){var rows='';function row(k,v,showKey){if(v==null)return;var s=String(v);if(!s.trim())return;var keyHtml=(showKey===false)?'':('<div class=\"step-meta-key\">'+e(k)+'</div>');rows+='<div class=\"step-meta-row\">'+keyHtml+'<div class=\"step-meta-val\">'+e(s)+'</div></div>';}\nrow('Expected',st.expected,true);\nrow('Actual',st.actual,true);\nvar hasFiles=st.attachedFiles&&st.attachedFiles.length;if(!hasFiles)row('Data',st.testdata,false);\nrow('Info',st.prereq,true);\nreturn rows?('<div class=\"step-meta\">'+rows+'</div>'):'';}");
        MervTestDataFileHtml.appendScriptHelpers(html);
        html.append("function stepShotKey(caseId,stepIdx){return String(caseId||'')+'|'+String(stepIdx==null?'':stepIdx);}");
        html.append("function stepHasScreenshots(st){return!!(st&&st.screenshots&&st.screenshots.length);}");
        html.append("function renderStepTitleHtml(st,stepIdx){var nm=e(st.teststepName||'Step');if(!stepHasScreenshots(st))return '<p class=\"test-step-title\">'+nm+'</p>';return '<p class=\"test-step-title has-shot\" data-step-idx=\"'+stepIdx+'\" role=\"button\" tabindex=\"0\" title=\"Click to show/hide screenshot\"><span class=\"step-shot-icon\" aria-hidden=\"true\">&#128247;</span>'+nm+'</p>';}");
        html.append("function renderStepScreenshotsBlock(st){if(!stepHasScreenshots(st))return '';var h='<div class=\"step-screenshots\">';(st.screenshots||[]).forEach(function(ss){var p=('../'+String(ss||'')).replace(/\\\\/g,'/');h+='<div class=\"screenshot\"><img src=\"'+e(p)+'\" alt=\"Screenshot\" onclick=\"window.open(this.src,\\'_blank\\')\"></div>';});return h+'</div>';}");
        html.append("function ensureShotsToolbarOn(){var cb=document.getElementById('toggle-shots');if(!cb||cb.checked)return;cb.checked=true;document.body.classList.remove('hide-shots');try{localStorage.setItem('merv.showScreenshots','1');}catch(ex){}}");
        html.append("function restoreOpenStepScreenshots(){var root=document.getElementById('testcase-content');if(!root||!selectedId)return;root.querySelectorAll('.test-step[data-step-idx]').forEach(function(stepEl){var idx=stepEl.getAttribute('data-step-idx');if(idx==null)return;if(!openStepShots[stepShotKey(selectedId,idx)])return;var shots=stepEl.querySelector('.step-screenshots');var title=stepEl.querySelector('.test-step-title');if(shots)shots.classList.add('open');if(title)title.classList.add('shots-open');});}");
        html.append("function toggleStepScreenshots(titleEl){var step=titleEl.closest('.test-step');if(!step)return;var shots=step.querySelector('.step-screenshots');if(!shots)return;var idx=step.getAttribute('data-step-idx');shots.classList.toggle('open');var open=shots.classList.contains('open');if(open)ensureShotsToolbarOn();var title=step.querySelector('.test-step-title');if(title)title.classList.toggle('shots-open',open);var key=stepShotKey(selectedId,idx);if(open)openStepShots[key]=true;else delete openStepShots[key];}");
        html.append("function stepShotClickTarget(el){if(!el||!el.closest)return null;if(el.closest('a,button,input,textarea,select,.testdata-flat-file-link,.testdata-image-file-preview,.merv-zoomable-image,.step-screenshots img'))return null;var step=el.closest('.test-step[data-step-idx]');if(!step||!step.querySelector('.step-screenshots'))return null;var hd=el.closest('.test-step-hd.has-shot-hd');if(!hd||!step.contains(hd))return null;return step.querySelector('.test-step-title')||hd;}");
        html.append("document.addEventListener('click',function(ev){var t=stepShotClickTarget(ev.target);if(!t)return;ev.preventDefault();toggleStepScreenshots(t);});");
        html.append("document.addEventListener('keydown',function(ev){if(ev.key!=='Enter'&&ev.key!==' ')return;var t=stepShotClickTarget(ev.target);if(!t)return;ev.preventDefault();toggleStepScreenshots(t);});");
        html.append("function renderSelectedCase(testCases){if(!testCases.length){document.getElementById('testcase-content').innerHTML='<p>No runtime data yet.</p>';return;}if(!selectedId||!document.querySelector('[data-id=\"'+selectedId+'\"]')){selectedId='tc-0';}var idx=parseInt(selectedId.replace('tc-',''),10);var t=testCases[idx]||testCases[0];var cls=statusClass(t.status);var statusText=String(t.status||'IN_PROGRESS').replace(/_/g,' ');var st=parseTs(t.startTime),et=parseTs(t.endTime),dur='0m:0s:0ms';if(st&&et&&et.getTime()>=st.getTime()){var ms=et.getTime()-st.getTime();var min=Math.floor(ms/60000),sec=Math.floor((ms%60000)/1000),msec=ms%1000;dur=min+'m:'+sec+'s:'+msec+'ms';}var tags=(t.tags&&t.tags.length)?t.tags.join(', '):'N/A';var machine=t.executionMachine||'N/A';var h='<div class=\"test-case '+cls+'\">';h+='<div class=\"testcase-heading\"><h3 class=\"testcase-title\">'+e(t.testcaseName||'N/A')+'</h3><span class=\"testcase-status-chip '+cls+'\">'+e(statusText)+'</span></div>';h+='<div class=\"testcase-meta-grid\">';h+='<div class=\"testcase-meta-item\"><span class=\"testcase-meta-label\">Status</span><span class=\"testcase-meta-value\">'+e(statusText)+'</span></div>';h+='<div class=\"testcase-meta-item\"><span class=\"testcase-meta-label\">Total time taken</span><span class=\"testcase-meta-value\">'+e(dur)+'</span></div>';h+='<div class=\"testcase-meta-item\"><span class=\"testcase-meta-label\">Executed on machine</span><span class=\"testcase-meta-value\">'+e(machine)+'</span></div>';h+='</div>';if(t.tags&&t.tags.length){h+='<div><strong>Tags:</strong></div>';h+='<div class=\"testcase-tags\">';t.tags.forEach(function(tag){h+='<span class=\"testcase-tag\">'+e(tag)+'</span>';});h+='</div>';}if(t.failureReason){h+='<div class=\"error\"><strong>Error:</strong> '+e(t.failureReason)+'</div>';}var steps=t.testSteps||[];var caseSt=parseTs(t.startTime);var prevEnd=null;if(steps.length){h+='<h4>Test Steps:</h4>';steps.forEach(function(st,si){var priorFail=hasPriorFailedStep(steps,si);var su=priorFail?{p:'SKIP',k:'skipped'}:stepRowUi(st);var sc=su.k;var pillTxt=su.p;var end=parseTs(st.endTime);var deltaMs=null;if(!priorFail){if(end){if(si===0&&caseSt){deltaMs=end.getTime()-caseSt.getTime();}else if(prevEnd){deltaMs=end.getTime()-prevEnd.getTime();}prevEnd=end;}else if(sc==='in_progress'){var nowMs=Date.now();if(si===0&&caseSt){deltaMs=nowMs-caseSt.getTime();}else if(prevEnd){deltaMs=nowMs-prevEnd.getTime();}else if(caseSt){deltaMs=nowMs-caseSt.getTime();}}}var timeLbl=fmtStepDelta(deltaMs);var ty=stepTypeClass(st);h+='<div class=\"test-step '+sc+(stepHasScreenshots(st)?' has-shot-step':'')+'\" data-step-idx=\"'+si+'\"><div class=\"test-step-hd'+(stepHasScreenshots(st)?' has-shot-hd':'')+'\">'+renderStepTitleHtml(st,si)+'<div class=\"test-step-badges\">'+(ty?('<span class=\"step-type-pill '+ty.cls+'\">'+ty.txt+'</span>'):'')+'<span class=\"step-status-pill '+sc+'\">'+pillTxt+'</span><span class=\"step-delta\">'+timeLbl+'</span></div></div>';if(st.errorMessage){h+='<div class=\"error\">'+e(st.errorMessage)+'</div>';}h+=addMetaRows(st);h+=renderAttachedFiles(st);h+=renderStepScreenshotsBlock(st);h+='</div>';});}h+='</div>';document.getElementById('testcase-content').innerHTML=h;restoreOpenStepScreenshots();}");
        
        html.append("function suiteSelectedTags(){return Object.keys(suiteAdvSelectedTags).filter(function(k){return suiteAdvSelectedTags[k];});}");
        html.append("function sidebarItemMatchesFilters(it){var nm=(it.querySelector('.sidebar-item-name')||{}).textContent||'';var st=it.getAttribute('data-status')||'';var tags=String(it.getAttribute('data-tags')||'').split('|').map(function(t){return t.trim();}).filter(Boolean);if(!suiteStatusFilters[st])return false;var want=suiteSelectedTags();if(want.length&&!want.some(function(tg){return tags.indexOf(tg)>=0;}))return false;var q=currentSearch.trim().toLowerCase();if(q){var hay=(nm+' '+tags.join(' ')).toLowerCase();if(hay.indexOf(q)<0)return false;}return true;}");
        html.append("function renderSuiteTagCloud(){var cloud=document.getElementById('suite-adv-tag-cloud');if(!cloud)return;var tc=(latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[];var seen={},tags=[];tc.forEach(function(row){(row.tags||[]).forEach(function(x){var t=String(x||'').trim();if(t&&!seen[t]){seen[t]=1;tags.push(t);}});});tags.sort(function(a,b){return a.localeCompare(b);});if(!tags.length){cloud.innerHTML='<span class=\"suite-adv-tag-empty\">No tags in this run yet.</span>';return;}cloud.innerHTML=tags.map(function(tg){var on=!!suiteAdvSelectedTags[tg];return'<button type=\"button\" class=\"suite-adv-tag-pill'+(on?' selected':'')+'\" data-tag=\"'+e(tg)+'\">'+e(tg)+'</button>';}).join('');cloud.querySelectorAll('.suite-adv-tag-pill').forEach(function(btn){btn.addEventListener('click',function(){var tv=btn.getAttribute('data-tag')||'';if(!tv)return;suiteAdvSelectedTags[tv]=!suiteAdvSelectedTags[tv];renderSuiteTagCloud();applySidebarFilters();});});}");
        html.append("function syncStatusFromFilter(){var f=currentFilter||'all';suiteStatusFilters.passed=f==='all'||f==='passed';suiteStatusFilters.failed=f==='all'||f==='failed';suiteStatusFilters.skipped=f==='all'||f==='skipped';suiteStatusFilters.in_progress=f==='all';}");
        html.append("function clearSuiteFilters(){Object.keys(suiteAdvSelectedTags).forEach(function(k){delete suiteAdvSelectedTags[k];});currentFilter='all';document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function(b){b.classList.toggle('active',(b.getAttribute('data-status')||'')==='all');});syncStatusFromFilter();var inp=document.getElementById('testcase-search');if(inp)inp.value='';currentSearch='';renderSuiteTagCloud();applySidebarFilters();}");
        html.append("function setSuiteTagPanelOpen(open){var panel=document.getElementById('suite-tag-panel');var btn=document.getElementById('sidebar-filter-btn');if(!panel||!btn)return;panel.hidden=!open;btn.setAttribute('aria-expanded',open?'true':'false');try{localStorage.setItem(SUITE_TAG_PANEL_KEY,open?'1':'0');}catch(ex){}if(open)renderSuiteTagCloud();}");
        html.append("function initSuiteFilters(){syncStatusFromFilter();var tagOpen=false;try{tagOpen=localStorage.getItem(SUITE_TAG_PANEL_KEY)==='1';}catch(ex){}setSuiteTagPanelOpen(tagOpen);var filterBtn=document.getElementById('sidebar-filter-btn');if(filterBtn){filterBtn.addEventListener('click',function(){var panel=document.getElementById('suite-tag-panel');setSuiteTagPanelOpen(panel&&panel.hidden);});}document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function(btn){btn.addEventListener('click',function(){currentFilter=btn.getAttribute('data-status')||'all';document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function(b){b.classList.toggle('active',(b.getAttribute('data-status')||'')===currentFilter);});syncStatusFromFilter();applySidebarFilters();});});var clearBtn=document.getElementById('suite-adv-clear');if(clearBtn)clearBtn.addEventListener('click',clearSuiteFilters);var searchInp=document.getElementById('testcase-search');if(searchInp){searchInp.addEventListener('input',function(ev){currentSearch=String(ev.target.value||'').toLowerCase();applySidebarFilters();});}}");
        html.append("function updateFailureJsonLink(failCount,running){var link=document.getElementById('failure-json-link');if(!link)return;var n=Number(failCount)||0;var ts=running?'?ts='+Date.now():'';link.href=running?'../json/failure-test.json'+ts:'../failure-test.json'+ts;link.textContent=n?'Failures JSON ('+n+')':'Failures JSON (0)';link.classList.toggle('is-empty',n===0);link.hidden=false;}");
html.append("function applySidebarFilters(){var visible=0;document.querySelectorAll('#sidebar-list .sidebar-item').forEach(function(it){var show=sidebarItemMatchesFilters(it);it.style.display=show?'block':'none';if(show)visible++;});var statusEl=document.getElementById('suite-adv-filter-status');if(statusEl){var active=suiteSelectedTags().length||!suiteStatusFilters.passed||!suiteStatusFilters.failed||!suiteStatusFilters.skipped||!suiteStatusFilters.in_progress||currentSearch.trim();statusEl.textContent=active?visible+' testcase'+(visible===1?'':'s')+' shown':'';}}");
        html.append("function filterByStatus(st){currentFilter=st||'all';document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function(b){b.classList.toggle('active',(b.getAttribute('data-status')||'')===currentFilter);});syncStatusFromFilter();applySidebarFilters();}");
        html.append("function filterShowAllTestCases(){clearSuiteFilters();return false;}");
        html.append("function scrollToTestcasePanel(){var tc=document.getElementById('testcase-content');if(!tc)return;try{tc.scrollIntoView({behavior:'smooth',block:'start'});}catch(err){tc.scrollIntoView(true);}try{var top=window.pageYOffset+tc.getBoundingClientRect().top-16;window.scrollTo({top:top,behavior:'smooth'});}catch(err2){window.scrollTo(0,window.pageYOffset+tc.getBoundingClientRect().top-16);}var ca=document.querySelector('.content-area');if(ca&&ca.scrollHeight>ca.clientHeight){var y=Math.max(0,tc.offsetTop-16);try{ca.scrollTo({top:y,behavior:'smooth'});}catch(err3){ca.scrollTop=y;}}}");
        html.append("function bindSidebarEvents(){var items=document.querySelectorAll('#sidebar-list .sidebar-item');items.forEach(function(it){it.onclick=function(){selectedId=it.getAttribute('data-id');renderSelectedCase((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);renderSidebar((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);setTimeout(scrollToTestcasePanel,0);};});}");
        
        
        html.append("function render(d){latestData=d||{};if(!d||!d.testSuite){document.getElementById('live-banner').innerText='No runtime data yet';return;}var s=d.testSuite,tc=s.testCases||[];var p=0,f=0,k=0;tc.forEach(function(t){if(t.status==='PASSED')p++;else if(t.status==='FAILED')f++;else if(t.status==='SKIPPED')k++;});var abortedStale=isStaleAborted(d);var effectiveRun=d.running===true&&!abortedStale;var stLbl=abortedStale?'ABORTED':(d.running?'RUNNING':'COMPLETED');var stExtra=abortedStale?' aborted':'';document.getElementById('suite-title').innerHTML=e(s.title||'Merv Live Runtime Report')+' <span id=\"run-state\" class=\"status-pill'+stExtra+'\">'+stLbl+'</span>';if(abortedStale){document.getElementById('live-banner').innerText='Aborted — no report updates for 1 min (build may have stopped). Test cases: '+tc.length;}else if(d.running){document.getElementById('live-banner').innerText='Live · Last update: '+new Date().toLocaleString()+' | Test cases: '+tc.length;}else{document.getElementById('live-banner').innerText='Execution completed. '+((d.exportDate)?('Snapshot: '+d.exportDate+'. '):'')+'Test cases: '+tc.length;}document.getElementById('total-count').innerText=tc.length;document.getElementById('passed-count').innerText=p;document.getElementById('failed-count').innerText=f;document.getElementById('skipped-count').innerText=k;document.getElementById('leg-p').textContent='Passed ('+p+')';document.getElementById('leg-f').textContent='Failed ('+f+')';document.getElementById('leg-k').textContent='Skipped ('+k+')';drawPieChart(p,f,k);drawTrendChart(tc,s,effectiveRun);renderFailureReasons(tc);if(requestedTestcaseName&&!appliedRequestedCase){for(var qi=0;qi<tc.length;qi++){var nm=String((tc[qi]&&tc[qi].testcaseName)||'').trim().toLowerCase();if(nm===requestedTestcaseName){selectedId='tc-'+qi;appliedRequestedCase=true;break;}}}updateFailureJsonLink(f,d.running===true);renderSuiteTagCloud();renderSidebar(tc);renderSelectedCase(tc);if(!effectiveRun){stopPolling();}}");
        html.append("async function load(){var ts='?ts='+Date.now();var paths=['../json/merv-report.json'+ts,'./../json/merv-report.json'+ts,'../../json/merv-report.json'+ts,'./merv-report.json'+ts];var lastErr='';for(var i=0;i<paths.length;i++){try{var r=await fetch(paths[i],{cache:'no-store'});if(!r.ok){lastErr='HTTP '+r.status+' for '+paths[i];continue;}var d=await r.json();render(d);return;}catch(err){lastErr=(err&&err.message)?err.message:String(err);}}var lb=document.getElementById('live-banner');if(lb&&lastErr){lb.innerText='Live data load issue: '+lastErr;}}");
        html.append("initSuiteFilters();pollTimer=setInterval(load,5000);load();");
        html.append("(function(){var cb=document.getElementById('toggle-shots');if(!cb)return;var key='merv.showScreenshots';function apply(v){document.body.classList.toggle('hide-shots',!v);}var saved=null;try{saved=localStorage.getItem(key);}catch(e){}if(saved==='0'){cb.checked=false;}apply(cb.checked);cb.addEventListener('change',function(){apply(cb.checked);try{localStorage.setItem(key,cb.checked?'1':'0');}catch(e){}});})();");
        html.append("</script></body></html>");
        return html.toString();
    }

    /**
     * While execution is in progress, keep {@code merv-report.html} in sync with the live report so opening either
     * file shows the same real-time JSON-driven UI (counts, sidebar, steps, screenshots).
     */
    private void writeRunningHtmlSnapshots(String reportFolderPath) {
        try {
            String htmlDir = reportFolderPath + "html" + File.separator;
            new File(htmlDir).mkdirs();
            String content = buildLiveHtmlReportContent();
            FileUtils.writeFile(htmlDir + "merv-report-live.html", content);
            FileUtils.writeFile(htmlDir + "merv-report.html", content);
        } catch (Exception e) {
            System.err.println("Error writing running HTML reports: " + e.getMessage());
        }
    }

    /**
     * Generate local HTML and JSON reports when Merv is disabled
     */
    private void generateLocalReports() {
        if (localTestSuite == null) {
            System.out.println("No test suite data available for local report generation.");
            return;
        }

        localTestSuite.setEndTime(new Date());

        try {
            // Use the existing report folder path (created during suite start)
            if (currentReportFolderPath == null) {
                System.err.println("Report folder path not set. Cannot generate reports.");
                return;
            }

            String reportFolderPath = currentReportFolderPath;

            // Create json subfolder
            String jsonFolderPath = reportFolderPath + "json" + File.separator;
            File jsonFolder = new File(jsonFolderPath);
            if (!jsonFolder.exists()) {
                jsonFolder.mkdirs();
            }

            // Create html subfolder
            String htmlFolderPath = reportFolderPath + "html" + File.separator;
            File htmlFolder = new File(htmlFolderPath);
            if (!htmlFolder.exists()) {
                htmlFolder.mkdirs();
            }

            // Finalize HTML report with the latest live shell (includes step screenshot toggle UI).
            String htmlReportPath = htmlFolderPath + "merv-report.html";
            String liveHtmlReportPath = htmlFolderPath + "merv-report-live.html";
            String liveHtmlAltPath = htmlFolderPath + "merv-live-report.html";
            String content = buildLiveHtmlReportContent();
            FileUtils.writeFile(htmlReportPath, content);
            FileUtils.writeFile(liveHtmlReportPath, content);
            FileUtils.writeFile(liveHtmlAltPath, content);
            System.out.println("HTML report finalized: " + htmlReportPath);

            // Generate JSON report in json folder
            String jsonReportPath = jsonFolderPath + "merv-report.json";
            generateJsonReport(jsonReportPath);
            System.out.println("JSON report generated: " + jsonReportPath);

            System.out.println("Merv Report generation completed: " + reportFolderPath);
            refreshReportsIndexListing();
            org.teche.merv.client.report.html.MervLocalReportZipWriter.writeUploadZipIfEnabled(
                    new File(reportFolderPath));

        } catch (Exception e) {
            System.err.println("Error generating local reports: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Rewrite {@code reports/index.html} listing every run folder that contains a final HTML report.
     */
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


    /**
     * Deletes one run folder under the configured Merv report root (name must be a single path segment).
     *
     * @return {@code null} on success, or a short error message for the client
     */
    public static String deleteReportRunFolder(String folderName) {
        if (folderName == null || (folderName = folderName.trim()).isEmpty()) {
            return "Missing folder name";
        }
        if (folderName.contains("..") || folderName.indexOf('/') >= 0 || folderName.indexOf('\\') >= 0) {
            return "Invalid folder name";
        }
        try {
            String base = MervConfig.getReportFolder();
            if (base == null || base.trim().isEmpty()) {
                return "Report folder not configured";
            }
            Path baseDir = Paths.get(base.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(baseDir)) {
                return "Report root not found";
            }
            Path baseReal = baseDir.toRealPath();
            Path target = baseReal.resolve(folderName);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return "Report folder not found";
            }
            Path targetReal = target.toRealPath();
            if (!targetReal.startsWith(baseReal)) {
                return "Invalid path";
            }
            deleteReportDirectoryRecursive(targetReal);
            refreshReportsIndexListing();
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return msg == null || msg.isEmpty() ? "Delete failed" : msg;
        }
    }

    private static void deleteReportDirectoryRecursive(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }


    /**
     * Generate HTML report
     */
    private void generateHtmlReport(String filePath, String reportFolderPath) throws Exception {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<title>Merv Test Execution Report</title>\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n");
        html.append("<link href=\"https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n");
        html.append("<style>\n");
        html.append(":root { --merv-grad: ").append(MervReportBranding.GRADIENT_CSS).append("; --sidebar-bg: #f2f3f5; --sidebar-border: #e6e8ec; --nav-text: #4a4a4a; --nav-muted: #6b6b6b; --nav-active-bg: #fdeaea; --nav-active-text: #c20000; }\n");
        html.append("html { scroll-behavior: smooth; }\n");
        html.append("html { font-family: 'Roboto', system-ui, -apple-system, sans-serif; }\n");
        html.append("body { font-family: 'Roboto', system-ui, -apple-system, sans-serif; margin: 0; padding: 0; background-color: #fafafa; color: #333; }\n");
        html.append("button, input, select, textarea { font-family: inherit; }\n");
        html.append("h1, h2, h3, h4, h5, h6 { letter-spacing: 0.5px; }\n");
        html.append(".main-wrapper { display: flex; min-height: 100vh; }\n");
        html.append(".sidebar { width: 400px; background: var(--sidebar-bg); color: var(--nav-text); padding: 20px 16px; overflow-y: auto; position: fixed; height: 100vh; box-shadow: 1px 0 0 var(--sidebar-border); }\n");
        html.append(".sidebar-brand { margin-bottom: 20px; padding-bottom: 18px; border-bottom: 1px solid var(--sidebar-border); text-align: center; }\n");
        html.append(".brand-logo { max-width: 180px; height: auto; display: block; margin: 0 auto; }\n");
        html.append(".sidebar-local-label { margin: 12px 0 0; padding: 0; font-size: 13px; font-weight: 700; color: var(--nav-active-text); letter-spacing: 0.04em; text-align: center; display: block; text-decoration: none; cursor: pointer; }\n");
        html.append(".sidebar-local-label:hover { color: #c20000; text-decoration: underline; text-underline-offset: 2px; }\n");
        html.append(".sidebar-search { margin-bottom: 16px; }\n");
        html.append(".sidebar-search input { width: 100%; padding: 10px 12px; border: 1px solid #ddd; border-radius: 6px; background-color: #fff; color: #333; font-size: 14px; box-sizing: border-box; }\n");
        html.append(".sidebar-search input::placeholder { color: #999; }\n");
        html.append(".sidebar-search input:focus { outline: 2px solid rgba(233,1,1,0.25); border-color: #e90101; }\n");
        html.append(".sidebar-filters { margin-bottom: 16px; display: flex; gap: 6px; flex-wrap: wrap; }\n");
        html.append(".filter-btn { flex: 1; min-width: 56px; padding: 8px 10px; border: 1px solid #ddd; border-radius: 6px; background-color: #fff; color: var(--nav-text); font-size: 11px; cursor: pointer; transition: background-color 0.2s, color 0.2s; }\n");
        html.append(".filter-btn:hover { background-color: #f8f8f8; }\n");
        html.append(".filter-btn.active { background: var(--merv-grad); color: #fff; border-color: transparent; font-weight: bold; }\n");
        html.append(".sidebar h2 { color: #333; margin-top: 0; margin-bottom: 10px; font-size: 14px; font-weight: bold; }\n");
        html.append(".sidebar-item { padding: 12px 12px; margin: 4px 0; border-radius: 6px; cursor: pointer; transition: background-color 0.15s; border-left: 4px solid transparent; background: #eceff3; }\n");
        html.append(".sidebar-item:hover { background-color: #e3e7ec; }\n");
        html.append(".sidebar-item.passed:not(.active), .sidebar-item.failed:not(.active), .sidebar-item.skipped:not(.active), .sidebar-item.in_progress:not(.active) { border-left-color: transparent; }\n");
        html.append(".sidebar-item.active { background: #eceff3; border-left-color: #959494; box-shadow: none; }\n");
        html.append(".sidebar-item-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }\n");
        html.append(".sidebar-item-name { font-weight: 600; color: #222; font-size: 14px; }\n");
        html.append(".sidebar-status-tag { font-size: 10px; font-weight: 700; letter-spacing: 0.04em; padding: 2px 8px; border-radius: 999px; background: #dfe4ea; color: #111; text-transform: uppercase; white-space: nowrap; }\n");
        html.append(".sidebar-status-tag.pass { background: #e8f5e9; color: #1b5e20; }\n");
        html.append(".sidebar-status-tag.fail { background: #ffebee; color: #b71c1c; }\n");
        html.append(".sidebar-status-tag.skip { background: #fff8e1; color: #e65100; }\n");
        html.append(".sidebar-status-tag.active { background: #e3f2fd; color: #0d47a1; }\n");
        html.append(".sidebar-item.active .sidebar-item-name, .sidebar-item.active .sidebar-status-tag { color: #222; font-weight: 600; }\n");
        html.append(".content-area { margin-left: 400px; flex: 1; padding: 20px; }\n");
        html.append(".report-toolbar { margin-bottom: 14px; padding-bottom: 12px; border-bottom: 1px solid #eee; }\n");
        html.append(".local-dash { color: #c20000; font-weight: 600; text-decoration: none; font-size: 14px; }\n");
        html.append(".local-dash:hover { text-decoration: underline; }\n");
        html.append(".container { max-width: 1200px; margin: 0 auto; background-color: white; padding: 24px; border-radius: 10px; box-shadow: 0 2px 12px rgba(0,0,0,0.08); color: #333; }\n");
        html.append("h1 { color: #222; border-bottom: 4px solid #c20000; padding-bottom: 12px; }\n");
        html.append(".section-heading { font-size: 14px; letter-spacing: 0.08em; text-transform: uppercase; color: #c20000; margin: 28px 0 12px 0; font-weight: bold; }\n");
        html.append(".section-heading:first-of-type { margin-top: 8px; }\n");
        html.append("h2 { color: #555; margin-top: 30px; }\n");
        html.append("h3 { color: #333; margin: 0 0 10px 0; }\n");
        html.append("h4 { color: #555; margin: 15px 0 10px 0; }\n");
        html.append("p { color: #333; margin: 5px 0; }\n");
        html.append(".summary { display: flex; gap: 20px; margin: 20px 0; }\n");
        html.append(".summary-card { flex: 1; padding: 15px; border-radius: 5px; text-align: center; }\n");
        html.append(".summary-card h3 { color: white; margin: 0 0 10px 0; font-size: 16px; font-weight: bold; }\n");
        html.append(".summary-card p { color: white; font-weight: bold; }\n");
        html.append(".total { background: var(--merv-grad); color: white; }\n");
        html.append(".passed { background-color: #eceff3; color: white; }\n");
        html.append(".failed { background-color: #eceff3; color: white; }\n");
        html.append(".skipped { background-color: #eceff3; color: white; }\n");
        html.append(".test-case { margin: 15px 0; padding: 15px; border-left: 4px solid #ddd; background-color: #f9f9f9; color: #333; }\n");
        html.append(".test-case h3 { color: #333; margin: 0 0 10px 0; }\n");
        html.append(".test-case p { color: #333; }\n");
        html.append(".test-case.passed { border-left-color: #4CAF50; }\n");
        html.append(".test-case.failed { border-left-color: #f44336; }\n");
        html.append(".test-case.skipped { border-left-color: #FF9800; }\n");
        html.append(".test-step { margin: 8px 0; padding: 8px; background-color: white; border-radius: 3px; color: #333; }\n");
        html.append(".test-step p { color: #333; }\n");
        html.append(".test-step strong { color: #333; }\n");
        html.append(".test-step.passed { border-left: 3px solid #4CAF50; }\n");
        html.append(".test-step.failed { border-left: 3px solid #f44336; }\n");
        html.append(".test-step.skipped { border-left: 3px solid #FF9800; }\n");
        html.append(".test-step.in_progress { border-left: 3px solid #ffc107; }\n");
        html.append(".test-step-hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin: 0 0 6px; flex-wrap: wrap; }\n");
        html.append(".test-step-title { margin: 0; flex: 1; min-width: 0; font-size: 14px; font-weight: 600; color: #333; line-height: 1.35; word-break: break-word; }\n");
        html.append(".test-step-title.has-shot { cursor: pointer; }\n");
        html.append(".test-step-title.has-shot:hover, .test-step-title.has-shot.shots-open { color: #c20000; }\n");
        html.append(".test-step-title .step-shot-icon { margin-right: 6px; opacity: 0.85; font-size: 13px; }\n");
        html.append(".test-step-hd.has-shot-hd { cursor: pointer; }\n");
        html.append(".test-step-hd.has-shot-hd:hover .test-step-title.has-shot { color: #c20000; }\n");
        html.append(".step-screenshots { display: none; margin-top: 8px; }\n");
        html.append(".step-screenshots.open { display: block !important; }\n");
        html.append(".test-step-badges { display: inline-flex; align-items: center; gap: 10px; flex-shrink: 0; margin-left: auto; }\n");
        html.append(".step-status-pill { display: inline-flex; align-items: center; padding: 3px 10px; border-radius: 999px; font-size: 10px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; border: 1px solid #d0d7de; background: #eef2f6; color: #364152; white-space: nowrap; }\n");
        html.append(".step-status-pill.passed { background: #e8f5e9; color: #1b5e20; border-color: #c8e6c9; }\n");
        html.append(".step-status-pill.failed { background: #ffebee; color: #b71c1c; border-color: #ffcdd2; }\n");
        html.append(".step-status-pill.skipped { background: #fff8e1; color: #e65100; border-color: #ffe0b2; }\n");
        html.append(".step-status-pill.in_progress { background: #fff8e1; color: #f57f17; border-color: #ffe082; }\n");
        html.append(".step-delta { font-size: 12px; font-weight: 600; color: #5c5f66; font-variant-numeric: tabular-nums; min-width: 6.5em; text-align: right; white-space: nowrap; }\n");
        html.append(".error { color: #f44336; font-size: 0.9em; margin-top: 5px; background-color: #ffebee; padding: 8px; border-radius: 3px; border-left: 3px solid #f44336; }\n");
        html.append(".error strong { color: #c62828; }\n");
        html.append(".report-run-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; margin: 18px 0 26px; }\n");
        html.append(".report-run-meta-item { background: #fafbfc; border: 1px solid #e8eaed; border-radius: 10px; padding: 16px 18px; min-width: 0; display: flex; flex-direction: column; gap: 8px; box-shadow: 0 1px 2px rgba(0,0,0,0.04); }\n");
        html.append(".report-run-meta-item.report-run-meta-duration { border-color: rgba(194,0,0,0.22); background: linear-gradient(145deg, #fff 0%, #fff8f7 55%, #fafbfc 100%); }\n");
        html.append(".report-run-meta-label { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: #888; }\n");
        html.append(".report-run-meta-value { font-size: 18px; font-weight: 600; color: #1a1a1a; line-height: 1.45; word-break: break-word; }\n");
        html.append(".report-run-meta-item.report-run-meta-duration .report-run-meta-value { color: #c20000; font-weight: 700; }\n");
        html.append(".screenshots { margin-top: 10px; }\n");
        html.append(".screenshots p { color: #333; font-weight: bold; }\n");
        html.append(".screenshot { margin: 10px 0; }\n");
        html.append(".screenshot img { display: block; }\n");
        html.append(".screenshot p { color: #666; font-size: 0.85em; margin-top: 5px; }\n");
        html.append(".screenshot-movie { margin: 18px 0 22px; padding: 16px 18px; background: linear-gradient(180deg, #2a2a2a 0%, #1a1a1a 100%); border-radius: 10px; border: 1px solid #404040; scroll-margin-top: 20px; }\n");
        html.append(".screenshot-movie-title { margin: 0 0 4px 0; color: #fff; font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; }\n");
        html.append(".screenshot-movie-hint { margin: 0 0 12px 0; color: #888; font-size: 11px; }\n");
        html.append(".screenshot-movie-stage { width: 100%; max-width: 880px; margin: 0 auto; background: #000; border-radius: 8px; overflow: hidden; aspect-ratio: 16 / 10; display: flex; align-items: center; justify-content: center; min-height: 160px; }\n");
        html.append(".screenshot-movie-img { max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; cursor: pointer; }\n");
        html.append(".screenshot-movie-bar { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; padding: 0 4px; color: #aaa; font-size: 12px; }\n");
        html.append(".screenshot-movie-counter { font-variant-numeric: tabular-nums; font-weight: 600; color: #ccc; }\n");
        html.append(".screenshot-movie-jump { margin: 6px 0 14px 0; font-size: 13px; }\n");
        html.append(".screenshot-movie-jump a { color: #c20000; font-weight: 600; text-decoration: none; border-bottom: 1px solid rgba(194,0,0,0.35); }\n");
        html.append(".screenshot-movie-jump a:hover { border-bottom-color: #c20000; }\n");
        html.append(".screenshot-movie-controls { display: flex; justify-content: center; align-items: center; gap: 10px; margin: 4px 0 10px; flex-wrap: wrap; }\n");
        html.append(".screenshot-movie-btn { min-width: 42px; height: 42px; padding: 0 10px; border-radius: 8px; border: 1px solid #555; background: #333; color: #fff; font-size: 17px; cursor: pointer; line-height: 1; display: inline-flex; align-items: center; justify-content: center; }\n");
        html.append(".screenshot-movie-btn:hover:not(:disabled) { background: #444; border-color: #c20000; }\n");
        html.append(".screenshot-movie-btn:disabled { opacity: 0.35; cursor: not-allowed; }\n");
        html.append(".step-logs { margin-top: 15px; }\n");
        html.append(".step-logs p { color: #333; font-weight: bold; margin-bottom: 10px; }\n");
        html.append(".log-container { background-color: #f5f5f5; border: 1px solid #ddd; border-radius: 4px; padding: 10px; max-height: 400px; overflow-y: auto; font-family: 'Courier New', monospace; font-size: 12px; }\n");
        html.append(".log-line { padding: 3px 0; border-bottom: 1px solid #eee; white-space: pre-wrap; word-wrap: break-word; }\n");
        html.append(".log-line:last-child { border-bottom: none; }\n");
        html.append(".log-info { color: #333; }\n");
        html.append(".log-error { color: #d32f2f; font-weight: bold; background-color: #ffebee; padding: 2px 5px; border-radius: 2px; }\n");
        html.append(".log-warn { color: #f57c00; background-color: #fff3e0; padding: 2px 5px; border-radius: 2px; }\n");
        html.append(".log-debug { color: #616161; }\n");
        html.append(".test-case-content { display: none; }\n");
        html.append(".test-case-content.active { display: block; }\n");
        html.append(".chart-collapsible { margin: 0 0 20px 0; border: 1px solid #e0e0e0; border-radius: 10px; background: #fafafa; overflow: hidden; }\n");
        html.append(".chart-collapsible-head { display: flex; align-items: center; gap: 10px; padding: 12px 16px; cursor: pointer; user-select: none; background: linear-gradient(180deg, #fff, #f5f5f5); border-bottom: 1px solid #e8e8e8; font-weight: 700; font-size: 14px; color: #333; }\n");
        html.append(".chart-collapsible-head:hover { background: #f0f0f0; }\n");
        html.append(".chart-collapsible-head .chart-caret { display: inline-block; font-size: 10px; color: #c20000; transition: transform 0.2s ease; transform: rotate(0deg); }\n");
        html.append(".chart-collapsible-head.collapsed .chart-caret { transform: rotate(-90deg); }\n");
        html.append(".chart-collapsible-body { padding: 18px; }\n");
        html.append(".chart-collapsible-body.collapsed { display: none; }\n");
        html.append(".stats-section { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }\n");
        html.append(".stats-left-col { flex: 0 0 40%; max-width: 40%; min-width: 260px; }\n");
        html.append(".stats-right-col { flex: 1 1 55%; min-width: 280px; }\n");
        html.append("@media (max-width: 900px) { .stats-left-col, .stats-right-col { flex: 1 1 100%; max-width: 100%; } }\n");
        html.append(".stat-row-4 { display: flex; flex-direction: row; gap: 8px; margin-bottom: 16px; }\n");
        html.append(".stat-row-4 .stat-card { flex: 1; min-width: 0; padding: 12px 6px; }\n");
        html.append(".stat-row-4 .stat-card h3 { font-size: 10px; line-height: 1.2; hyphens: auto; word-wrap: break-word; }\n");
        html.append(".stat-card { background-color: white; border-radius: 8px; text-align: center; box-shadow: 0 2px 4px rgba(0,0,0,0.08); border: 1px solid #eee; }\n");
        html.append("a.stat-card { text-decoration: none; color: inherit; display: block; cursor: pointer; }\n");
        html.append("a.stat-card:hover { box-shadow: 0 4px 10px rgba(0,0,0,0.12); border-color: #ddd; }\n");
        html.append(".stat-card h3 { margin: 0 0 6px 0; color: #666; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.03em; line-height: 1.2; }\n");
        html.append(".stat-card .stat-value { font-size: 22px; font-weight: bold; color: #333; line-height: 1.1; }\n");
        html.append(".stat-card.total .stat-value { color: #c20000; }\n");
        html.append(".stat-card.passed .stat-value { color: #4CAF50; }\n");
        html.append(".stat-card.failed .stat-value { color: #f44336; }\n");
        html.append(".stat-card.skipped .stat-value { color: #FF9800; }\n");
        html.append(".pie-chart-container { text-align: center; background-color: white; padding: 14px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.06); border: 1px solid #eee; }\n");
        html.append(".pie-chart { display: inline-block; }\n");
        html.append(".pie-legend { display: flex; justify-content: center; gap: 16px; margin-top: 12px; flex-wrap: wrap; }\n");
        html.append(".pie-legend-item { display: flex; align-items: center; gap: 6px; }\n");
        html.append(".pie-legend-color { width: 14px; height: 14px; border-radius: 3px; flex-shrink: 0; }\n");
        html.append(".pie-legend-label { font-size: 12px; color: #333; }\n");
        html.append(".failure-reasons-panel { background: #fff; border: 1px solid #eee; border-radius: 8px; padding: 14px 16px; box-shadow: 0 2px 4px rgba(0,0,0,0.06); max-height: 420px; overflow: auto; }\n");
        html.append(".failure-reasons-panel h3 { margin: 0 0 12px 0; font-size: 13px; text-transform: uppercase; letter-spacing: 0.06em; color: #c20000; }\n");
        html.append(".failure-reason-empty { color: #888; font-size: 13px; margin: 0; }\n");
        html.append("a.failure-reason-row { text-decoration: none; color: inherit; display: flex; }\n");
        html.append(".failure-reason-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 10px 8px; border-bottom: 1px solid #f0f0f0; cursor: pointer; border-radius: 6px; margin: 0 -4px; }\n");
        html.append(".failure-reason-row:last-child { border-bottom: none; }\n");
        html.append(".failure-reason-row:hover { background: #fff5f5; }\n");
        html.append(".failure-reason-text { flex: 1; min-width: 0; font-size: 13px; color: #333; line-height: 1.35; word-break: break-word; }\n");
        html.append(".failure-reason-count { flex-shrink: 0; font-weight: 700; font-size: 13px; color: #f44336; min-width: 2em; text-align: right; }\n");
        html.append(".test-case-summary { cursor: pointer; padding: 15px; margin: 10px 0; background-color: #f9f9f9; border-radius: 5px; border-left: 4px solid #ddd; transition: all 0.3s; }\n");
        html.append(".test-case-summary:hover { background-color: #f0f0f0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append(".test-case-summary.passed { border-left-color: #4CAF50; }\n");
        html.append(".test-case-summary.failed { border-left-color: #f44336; }\n");
        html.append(".test-case-summary.skipped { border-left-color: #FF9800; }\n");
        html.append("</style>\n");
        html.append("<script>\n");
        html.append("function collectMovieUrls(box) {\n");
        html.append("    var spans = box.querySelectorAll('.screenshot-movie-sources [data-src]');\n");
        html.append("    return Array.prototype.map.call(spans, function(s) { return s.getAttribute('data-src'); });\n");
        html.append("}\n");
        html.append("function syncMovieFrame(box, idx) {\n");
        html.append("    var urls = collectMovieUrls(box);\n");
        html.append("    if (!urls.length) return;\n");
        html.append("    var n = urls.length;\n");
        html.append("    idx = ((idx % n) + n) % n;\n");
        html.append("    box._movieIdx = idx;\n");
        html.append("    var img = box.querySelector('.screenshot-movie-img');\n");
        html.append("    var counter = box.querySelector('.screenshot-movie-counter');\n");
        html.append("    if (img) img.src = urls[idx];\n");
        html.append("    if (counter) counter.textContent = (idx + 1) + ' / ' + n;\n");
        html.append("}\n");
        html.append("function updatePlayPauseButtons(box) {\n");
        html.append("    var urls = collectMovieUrls(box);\n");
        html.append("    var n = urls.length;\n");
        html.append("    var playBtn = box.querySelector('.screenshot-movie-btn[data-act=\"play\"]');\n");
        html.append("    var pauseBtn = box.querySelector('.screenshot-movie-btn[data-act=\"pause\"]');\n");
        html.append("    var prevBtn = box.querySelector('.screenshot-movie-btn[data-act=\"prev\"]');\n");
        html.append("    var nextBtn = box.querySelector('.screenshot-movie-btn[data-act=\"next\"]');\n");
        html.append("    if (prevBtn) prevBtn.disabled = n <= 1;\n");
        html.append("    if (nextBtn) nextBtn.disabled = n <= 1;\n");
        html.append("    if (playBtn) playBtn.disabled = n < 2 || !!box._moviePlaying;\n");
        html.append("    if (pauseBtn) pauseBtn.disabled = n < 2 || !box._moviePlaying;\n");
        html.append("}\n");
        html.append("function moviePause(box) {\n");
        html.append("    if (box._movieTimer) { clearInterval(box._movieTimer); box._movieTimer = null; }\n");
        html.append("    box._moviePlaying = false;\n");
        html.append("    updatePlayPauseButtons(box);\n");
        html.append("}\n");
        html.append("function moviePlay(box) {\n");
        html.append("    var urls = collectMovieUrls(box);\n");
        html.append("    if (urls.length < 2 || box._moviePlaying) return;\n");
        html.append("    var ms = parseInt(box.getAttribute('data-interval-ms') || '1000', 10);\n");
        html.append("    if (isNaN(ms) || ms < 200) ms = 1000;\n");
        html.append("    box._moviePlaying = true;\n");
        html.append("    updatePlayPauseButtons(box);\n");
        html.append("    box._movieTimer = setInterval(function() {\n");
        html.append("        syncMovieFrame(box, (box._movieIdx || 0) + 1);\n");
        html.append("    }, ms);\n");
        html.append("}\n");
        html.append("function moviePrev(box) { syncMovieFrame(box, (box._movieIdx || 0) - 1); }\n");
        html.append("function movieNext(box) { syncMovieFrame(box, (box._movieIdx || 0) + 1); }\n");
        html.append("function stopAllScreenshotMovies() {\n");
        html.append("    document.querySelectorAll('.screenshot-movie').forEach(function(box) {\n");
        html.append("        moviePause(box);\n");
        html.append("    });\n");
        html.append("}\n");
        html.append("function startScreenshotMovieForActive() {\n");
        html.append("    stopAllScreenshotMovies();\n");
        html.append("    var activeMovie = document.querySelector('.test-case-content.active .screenshot-movie');\n");
        html.append("    if (!activeMovie) return;\n");
        html.append("    var urls = collectMovieUrls(activeMovie);\n");
        html.append("    if (!urls.length) return;\n");
        html.append("    activeMovie._movieIdx = 0;\n");
        html.append("    syncMovieFrame(activeMovie, 0);\n");
        html.append("    updatePlayPauseButtons(activeMovie);\n");
        html.append("    if (urls.length >= 2) moviePlay(activeMovie);\n");
        html.append("}\n");
        html.append("document.addEventListener('click', function(e) {\n");
        html.append("    var btn = e.target.closest('.screenshot-movie-btn');\n");
        html.append("    if (!btn) return;\n");
        html.append("    var box = btn.closest('.screenshot-movie');\n");
        html.append("    if (!box) return;\n");
        html.append("    var act = btn.getAttribute('data-act');\n");
        html.append("    if (act === 'prev') moviePrev(box);\n");
        html.append("    else if (act === 'next') movieNext(box);\n");
        html.append("    else if (act === 'play') moviePlay(box);\n");
        html.append("    else if (act === 'pause') moviePause(box);\n");
        html.append("});\n");
        html.append("function stepShotClickTarget(el) {\n");
        html.append("    if (!el || !el.closest) return null;\n");
        html.append("    if (el.closest('a, button, input, textarea, select, .step-screenshots img')) return null;\n");
        html.append("    var step = el.closest('.test-step[data-step-idx]');\n");
        html.append("    if (!step || !step.querySelector('.step-screenshots')) return null;\n");
        html.append("    var hd = el.closest('.test-step-hd.has-shot-hd');\n");
        html.append("    if (!hd || !step.contains(hd)) return null;\n");
        html.append("    return step.querySelector('.test-step-title') || hd;\n");
        html.append("}\n");
        html.append("function toggleStepScreenshots(titleEl) {\n");
        html.append("    var step = titleEl.closest('.test-step');\n");
        html.append("    if (!step) return;\n");
        html.append("    var shots = step.querySelector('.step-screenshots');\n");
        html.append("    if (!shots) return;\n");
        html.append("    shots.classList.toggle('open');\n");
        html.append("    var title = step.querySelector('.test-step-title');\n");
        html.append("    if (title) title.classList.toggle('shots-open', shots.classList.contains('open'));\n");
        html.append("}\n");
        html.append("document.addEventListener('click', function(e) {\n");
        html.append("    var title = stepShotClickTarget(e.target);\n");
        html.append("    if (!title) return;\n");
        html.append("    e.preventDefault();\n");
        html.append("    toggleStepScreenshots(title);\n");
        html.append("});\n");
        html.append("document.addEventListener('keydown', function(e) {\n");
        html.append("    if (e.key !== 'Enter' && e.key !== ' ') return;\n");
        html.append("    var title = stepShotClickTarget(e.target);\n");
        html.append("    if (!title) return;\n");
        html.append("    e.preventDefault();\n");
        html.append("    toggleStepScreenshots(title);\n");
        html.append("});\n");
        html.append("function showTestCase(testCaseId) {\n");
        html.append("    // Hide all test case contents\n");
        html.append("    var allContents = document.querySelectorAll('.test-case-content');\n");
        html.append("    allContents.forEach(function(content) { content.classList.remove('active'); });\n");
        html.append("    // Remove active class from all sidebar items\n");
        html.append("    var allItems = document.querySelectorAll('.sidebar-item');\n");
        html.append("    allItems.forEach(function(item) { item.classList.remove('active'); });\n");
        html.append("    // Show selected test case\n");
        html.append("    var selectedContent = document.getElementById('testcase-' + testCaseId);\n");
        html.append("    if (selectedContent) {\n");
        html.append("        selectedContent.classList.add('active');\n");
        html.append("        // Scroll to the test case content with offset for better visibility\n");
        html.append("        setTimeout(function() {\n");
        html.append("            selectedContent.scrollIntoView({ behavior: 'smooth', block: 'start' });\n");
        html.append("            // Additional scroll adjustment to account for fixed sidebar\n");
        html.append("            window.scrollBy(0, -20);\n");
        html.append("        }, 100);\n");
        html.append("    }\n");
        html.append("    // Add active class to clicked sidebar item\n");
        html.append("    var clickedItem = document.getElementById('sidebar-' + testCaseId);\n");
        html.append("    if (clickedItem) { clickedItem.classList.add('active'); }\n");
        html.append("    startScreenshotMovieForActive();\n");
        html.append("}\n");
        html.append("var currentFilter = 'all';\n");
        html.append("var currentFailureReasonId = '';\n");
        html.append("function toggleChartBlock() {\n");
        html.append("    var body = document.getElementById('chart-block-body');\n");
        html.append("    var head = document.getElementById('chart-block-head');\n");
        html.append("    if (!body || !head) return;\n");
        html.append("    var collapsed = body.classList.toggle('collapsed');\n");
        html.append("    head.classList.toggle('collapsed', collapsed);\n");
        html.append("    head.setAttribute('aria-expanded', collapsed ? 'false' : 'true');\n");
        html.append("    if (!collapsed && typeof drawPieChart === 'function') {\n");
        html.append("        var c = document.getElementById('pieChart');\n");
        html.append("        if (c && c.dataset) {\n");
        html.append("            var p = parseInt(c.dataset.passed||'0',10), f = parseInt(c.dataset.failed||'0',10), k = parseInt(c.dataset.skipped||'0',10);\n");
        html.append("            drawPieChart(p,f,k);\n");
        html.append("        }\n");
        html.append("    }\n");
        html.append("}\n");
        html.append("function filterShowAllTestCases() {\n");
        html.append("    currentFailureReasonId = '';\n");
        html.append("    currentFilter = 'all';\n");
        html.append("    var filterButtons = document.querySelectorAll('.filter-btn');\n");
        html.append("    filterButtons.forEach(function(btn) { btn.classList.remove('active'); });\n");
        html.append("    var ab = document.querySelector('.filter-btn[data-status=\"all\"]');\n");
        html.append("    if (ab) { ab.classList.add('active'); }\n");
        html.append("    applyFilters(document.getElementById('testcase-search').value.toLowerCase(), 'all');\n");
        html.append("    return false;\n");
        html.append("}\n");
        html.append("function filterByFailureReasonId(id) {\n");
        html.append("    currentFailureReasonId = String(id);\n");
        html.append("    currentFilter = 'failed';\n");
        html.append("    var filterButtons = document.querySelectorAll('.filter-btn');\n");
        html.append("    filterButtons.forEach(function(btn) { btn.classList.remove('active'); });\n");
        html.append("    var fb = document.querySelector('.filter-btn[data-status=\"failed\"]');\n");
        html.append("    if (fb) { fb.classList.add('active'); }\n");
        html.append("    applyFilters(document.getElementById('testcase-search').value.toLowerCase(), 'failed');\n");
        html.append("    return false;\n");
        html.append("}\n");
        html.append("function searchTestCases() {\n");
        html.append("    var searchTerm = document.getElementById('testcase-search').value.toLowerCase();\n");
        html.append("    applyFilters(searchTerm, currentFilter);\n");
        html.append("}\n");
        html.append("function filterByStatus(status) {\n");
        html.append("    currentFailureReasonId = '';\n");
        html.append("    currentFilter = status;\n");
        html.append("    // Update active filter button\n");
        html.append("    var filterButtons = document.querySelectorAll('.filter-btn');\n");
        html.append("    filterButtons.forEach(function(btn) { btn.classList.remove('active'); });\n");
        html.append("    var activeBtn = document.querySelector('.filter-btn[data-status=\"' + status + '\"]');\n");
        html.append("    if (activeBtn) { activeBtn.classList.add('active'); }\n");
        html.append("    // Apply filters\n");
        html.append("    var searchTerm = document.getElementById('testcase-search').value.toLowerCase();\n");
        html.append("    applyFilters(searchTerm, status);\n");
        html.append("}\n");
        html.append("function applyFilters(searchTerm, statusFilter) {\n");
        html.append("    var sidebarItems = document.querySelectorAll('.sidebar-item');\n");
        html.append("    sidebarItems.forEach(function(item) {\n");
        html.append("        var testCaseName = item.querySelector('.sidebar-item-name').textContent.toLowerCase();\n");
        html.append("        var testCaseStatus = item.classList.contains('passed') ? 'passed' : \n");
        html.append("                           (item.classList.contains('failed') ? 'failed' : \n");
        html.append("                           (item.classList.contains('skipped') ? 'skipped' : 'all'));\n");
        html.append("        var matchesSearch = testCaseName.includes(searchTerm);\n");
        html.append("        var matchesFilter = (statusFilter === 'all' || testCaseStatus === statusFilter);\n");
        html.append("        var matchesReason = true;\n");
        html.append("        if (currentFailureReasonId !== '' && currentFailureReasonId != null) {\n");
        html.append("            matchesReason = (item.getAttribute('data-failure-id') || '') === String(currentFailureReasonId);\n");
        html.append("        }\n");
        html.append("        if (matchesSearch && matchesFilter && matchesReason) {\n");
        html.append("            item.style.display = 'block';\n");
        html.append("        } else {\n");
        html.append("            item.style.display = 'none';\n");
        html.append("        }\n");
        html.append("    });\n");
        html.append("}\n");
        html.append("function drawPieChart(passed, failed, skipped) {\n");
        html.append("    var canvas = document.getElementById('pieChart');\n");
        html.append("    if (!canvas) return;\n");
        html.append("    var ctx = canvas.getContext('2d');\n");
        html.append("    var total = passed + failed + skipped;\n");
        html.append("    if (total === 0) {\n");
        html.append("        ctx.fillStyle = '#ddd';\n");
        html.append("        ctx.beginPath();\n");
        html.append("        ctx.arc(150, 150, 100, 0, 2 * Math.PI);\n");
        html.append("        ctx.fill();\n");
        html.append("        return;\n");
        html.append("    }\n");
        html.append("    var centerX = 150, centerY = 150, radius = 100;\n");
        html.append("    var startAngle = -Math.PI / 2;\n");
        html.append("    var passedAngle = (passed / total) * 2 * Math.PI;\n");
        html.append("    var failedAngle = (failed / total) * 2 * Math.PI;\n");
        html.append("    var skippedAngle = (skipped / total) * 2 * Math.PI;\n");
        html.append("    // Draw passed slice\n");
        html.append("    if (passed > 0) {\n");
        html.append("        ctx.beginPath();\n");
        html.append("        ctx.moveTo(centerX, centerY);\n");
        html.append("        ctx.arc(centerX, centerY, radius, startAngle, startAngle + passedAngle);\n");
        html.append("        ctx.closePath();\n");
        html.append("        ctx.fillStyle = '#4CAF50';\n");
        html.append("        ctx.fill();\n");
        html.append("        startAngle += passedAngle;\n");
        html.append("    }\n");
        html.append("    // Draw failed slice\n");
        html.append("    if (failed > 0) {\n");
        html.append("        ctx.beginPath();\n");
        html.append("        ctx.moveTo(centerX, centerY);\n");
        html.append("        ctx.arc(centerX, centerY, radius, startAngle, startAngle + failedAngle);\n");
        html.append("        ctx.closePath();\n");
        html.append("        ctx.fillStyle = '#f44336';\n");
        html.append("        ctx.fill();\n");
        html.append("        startAngle += failedAngle;\n");
        html.append("    }\n");
        html.append("    // Draw skipped slice\n");
        html.append("    if (skipped > 0) {\n");
        html.append("        ctx.beginPath();\n");
        html.append("        ctx.moveTo(centerX, centerY);\n");
        html.append("        ctx.arc(centerX, centerY, radius, startAngle, startAngle + skippedAngle);\n");
        html.append("        ctx.closePath();\n");
        html.append("        ctx.fillStyle = '#FF9800';\n");
        html.append("        ctx.fill();\n");
        html.append("    }\n");
        html.append("    // Draw border\n");
        html.append("    ctx.beginPath();\n");
        html.append("    ctx.arc(centerX, centerY, radius, 0, 2 * Math.PI);\n");
        html.append("    ctx.strokeStyle = '#fff';\n");
        html.append("    ctx.lineWidth = 2;\n");
        html.append("    ctx.stroke();\n");
        html.append("}\n");
        html.append("</script>\n");
        html.append("</head>\n<body>\n");
        html.append("<div class=\"main-wrapper\">\n");
        html.append("<div class=\"sidebar\">\n");
        html.append("<div class=\"sidebar-brand\"><img class=\"brand-logo\" src=\"").append(MervReportBranding.LOGO_URL).append("\" alt=\"Merv\"><a class=\"sidebar-local-label\" href=\"../../index.html\" title=\"Open local dashboard\">Merv Local</a></div>\n");
        html.append("<div class=\"sidebar-search\">\n");
        html.append("<input type=\"text\" id=\"testcase-search\" placeholder=\"Search test cases...\" onkeyup=\"searchTestCases()\">\n");
        html.append("</div>\n");
        html.append("<div class=\"sidebar-filters\">\n");
        html.append("<button class=\"filter-btn active\" data-status=\"all\" onclick=\"filterByStatus('all')\">All</button>\n");
        html.append("<button class=\"filter-btn\" data-status=\"passed\" onclick=\"filterByStatus('passed')\">Pass</button>\n");
        html.append("<button class=\"filter-btn\" data-status=\"failed\" onclick=\"filterByStatus('failed')\">Fail</button>\n");
        html.append("<button class=\"filter-btn\" data-status=\"skipped\" onclick=\"filterByStatus('skipped')\">Skip</button>\n");
        html.append("</div>\n");
        html.append("<h2>Test Cases</h2>\n");

        List<LocalTestCase> reportCases = localTestSuite.getTestCases();
        LinkedHashMap<String, Integer> failureReasonCounts = new LinkedHashMap<>();
        for (LocalTestCase tc : reportCases) {
            if (!"FAILED".equals(tc.getStatus())) {
                continue;
            }
            String frKey = normalizeFailureReasonForReport(tc);
            failureReasonCounts.merge(frKey, 1, Integer::sum);
        }
        Map<String, Integer> failureReasonToId = new LinkedHashMap<>();
        int frId = 0;
        for (String frKey : failureReasonCounts.keySet()) {
            failureReasonToId.put(frKey, frId++);
        }

        // Build sidebar with test cases
        int testCaseIndex = 0;
        for (LocalTestCase testCase : reportCases) {
            String testCaseId = "tc-" + testCaseIndex;
            html.append("<div class=\"sidebar-item ").append(testCase.getStatus().toLowerCase());
            if (testCaseIndex == 0) {
                html.append(" active"); // First test case is active by default
            }
            String fid = "";
            if ("FAILED".equals(testCase.getStatus())) {
                Integer id = failureReasonToId.get(normalizeFailureReasonForReport(testCase));
                if (id != null) {
                    fid = String.valueOf(id);
                }
            }
            html.append("\" data-failure-id=\"").append(escapeHtml(fid)).append("\" id=\"sidebar-").append(testCaseId).append("\" onclick=\"showTestCase('").append(testCaseId).append("')\">\n");
            String status = testCase.getStatus() == null ? "" : testCase.getStatus().toUpperCase(Locale.ROOT);
            String tagText = "ACTIVE";
            String tagClass = "active";
            if ("PASSED".equals(status)) {
                tagText = "PASS";
                tagClass = "pass";
            } else if ("FAILED".equals(status)) {
                tagText = "FAIL";
                tagClass = "fail";
            } else if ("SKIPPED".equals(status)) {
                tagText = "SKIP";
                tagClass = "skip";
            }
            html.append("<div class=\"sidebar-item-top\">");
            html.append("<div class=\"sidebar-item-name\">").append(escapeHtml(testCase.getTestcaseName())).append("</div>");
            html.append("<span class=\"sidebar-status-tag ").append(tagClass).append("\">").append(tagText).append("</span>");
            html.append("</div>\n");
            html.append("</div>\n");
            testCaseIndex++;
        }
        html.append("</div>\n"); // Close sidebar

        // Content area
        html.append("<div class=\"content-area\">\n");
        html.append("<div class=\"container\">\n");
        html.append("<div class=\"report-toolbar\"><a class=\"local-dash\" href=\"../../index.html\">Local Dashboard</a></div>\n");

        // Suite Name
        html.append("<h1>").append(escapeHtml(localTestSuite.getTitle())).append("</h1>\n");

        // Calculate statistics (same list as sidebar)
        int total = reportCases.size();
        long passed = reportCases.stream().filter(tc -> "PASSED".equals(tc.getStatus())).count();
        long failed = reportCases.stream().filter(tc -> "FAILED".equals(tc.getStatus())).count();
        long skipped = reportCases.stream().filter(tc -> "SKIPPED".equals(tc.getStatus())).count();

        html.append("<div class=\"chart-collapsible\">\n");
        html.append("<div class=\"chart-collapsible-head\" id=\"chart-block-head\" role=\"button\" tabindex=\"0\" aria-expanded=\"true\" onclick=\"toggleChartBlock()\" onkeydown=\"if(event.key==='Enter'||event.key===' '){event.preventDefault();toggleChartBlock();}\">\n");
        html.append("<span class=\"chart-caret\" aria-hidden=\"true\">&#9660;</span>\n");
        html.append("<span>Summary &amp; charts</span>\n");
        html.append("</div>\n");
        html.append("<div class=\"chart-collapsible-body\" id=\"chart-block-body\">\n");
        html.append("<div class=\"stats-section\">\n");

        html.append("<div class=\"stats-left-col\">\n");
        html.append("<div class=\"stat-row-4\">\n");
        html.append("<a href=\"#\" class=\"stat-card total\" style='background:white;' onclick=\"return filterShowAllTestCases();\" title=\"Show all test cases in the sidebar\"><h3>Total</h3><div class=\"stat-value\">").append(total).append("</div></a>\n");
        html.append("<div class=\"stat-card passed\"><h3>Passed</h3><div class=\"stat-value\">").append(passed).append("</div></div>\n");
        html.append("<div class=\"stat-card failed\"><h3>Failed</h3><div class=\"stat-value\">").append(failed).append("</div></div>\n");
        html.append("<div class=\"stat-card skipped\"><h3>Skipped</h3><div class=\"stat-value\">").append(skipped).append("</div></div>\n");
        html.append("</div>\n");
        html.append("<div class=\"pie-chart-container\">\n");
        html.append("<canvas id=\"pieChart\" width=\"300\" height=\"300\" data-passed=\"").append(passed).append("\" data-failed=\"").append(failed).append("\" data-skipped=\"").append(skipped).append("\"></canvas>\n");
        html.append("<div class=\"pie-legend\">\n");
        html.append("<div class=\"pie-legend-item\"><div class=\"pie-legend-color\" style=\"background-color: #4CAF50;\"></div><span class=\"pie-legend-label\">Passed (").append(passed).append(")</span></div>\n");
        html.append("<div class=\"pie-legend-item\"><div class=\"pie-legend-color\" style=\"background-color: #f44336;\"></div><span class=\"pie-legend-label\">Failed (").append(failed).append(")</span></div>\n");
        html.append("<div class=\"pie-legend-item\"><div class=\"pie-legend-color\" style=\"background-color: #FF9800;\"></div><span class=\"pie-legend-label\">Skipped (").append(skipped).append(")</span></div>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        html.append("</div>\n");

        html.append("<div class=\"stats-right-col\">\n");
        html.append("<div class=\"failure-reasons-panel\">\n");
        html.append("<h3>Failure reasons</h3>\n");
        if (failureReasonCounts.isEmpty()) {
            html.append("<p class=\"failure-reason-empty\">No failures in this run.</p>\n");
        } else {
            for (Map.Entry<String, Integer> ent : failureReasonCounts.entrySet()) {
                String reason = ent.getKey();
                int cnt = ent.getValue();
                int rid = failureReasonToId.getOrDefault(reason, 0);
                html.append("<a href=\"#\" class=\"failure-reason-row\" onclick=\"return filterByFailureReasonId(").append(rid).append(");\" title=\"").append(escapeHtml(reason)).append("\">\n");
                html.append("<span class=\"failure-reason-text\">").append(escapeHtml(reason)).append("</span>\n");
                html.append("<span class=\"failure-reason-count\">").append(cnt).append("</span>\n");
                html.append("</a>\n");
            }
        }
        html.append("</div>\n");
        html.append("</div>\n");

        html.append("</div>\n");
        html.append("</div>\n");
        html.append("</div>\n");

        // Run timing (start / end / duration)
        long duration = localTestSuite.getEndTime().getTime() - localTestSuite.getStartTime().getTime();
        html.append("<div class=\"report-run-meta\" role=\"group\" aria-label=\"Run timing\">\n");
        html.append("<div class=\"report-run-meta-item\"><span class=\"report-run-meta-label\">Start</span><span class=\"report-run-meta-value\">")
                .append(escapeHtml(formatReportDateTimeEnglish(localTestSuite.getStartTime()))).append("</span></div>\n");
        html.append("<div class=\"report-run-meta-item\"><span class=\"report-run-meta-label\">End</span><span class=\"report-run-meta-value\">")
                .append(escapeHtml(formatReportDateTimeEnglish(localTestSuite.getEndTime()))).append("</span></div>\n");
        html.append("<div class=\"report-run-meta-item report-run-meta-duration\"><span class=\"report-run-meta-label\">Duration</span><span class=\"report-run-meta-value\">")
                .append(escapeHtml(formatDuration(duration))).append("</span></div>\n");
        html.append("</div>\n");

        // Test Cases Content
        testCaseIndex = 0;
        for (LocalTestCase testCase : reportCases) {
            String testCaseId = "tc-" + testCaseIndex;
            html.append("<div class=\"test-case-content");
            if (testCaseIndex == 0) {
                html.append(" active"); // First test case is visible by default
            }
            html.append("\" id=\"testcase-").append(testCaseId).append("\">\n");

            html.append("<div class=\"test-case ").append(testCase.getStatus().toLowerCase()).append("\">\n");
            html.append("<h3>").append(escapeHtml(testCase.getTestcaseName())).append("</h3>\n");
            html.append("<p><strong>Status:</strong> ").append(testCase.getStatus()).append("</p>\n");

            if (testCase.getFailureReason() != null && !testCase.getFailureReason().isEmpty()) {
                html.append("<div class=\"error\"><strong>Error:</strong> ").append(escapeHtml(testCase.getFailureReason())).append("</div>\n");
            }

            if (testCase.getTags() != null && !testCase.getTags().isEmpty()) {
                html.append("<p><strong>Tags:</strong> ").append(escapeHtml(String.join(", ", testCase.getTags()))).append("</p>\n");
            }

            List<String> moviePaths = collectTestCaseScreenshotPaths(testCase);
            if (!moviePaths.isEmpty()) {
                html.append("<p class=\"screenshot-movie-jump\"><a href=\"#screenshot-movie-").append(testCaseId).append("\">Screenshot movie &#8595;</a></p>\n");
            }

            // Test Steps
            if (testCase.getTestSteps() != null && !testCase.getTestSteps().isEmpty()) {
                html.append("<h4>Test Steps:</h4>\n");
                List<LocalTestStep> stepList = testCase.getTestSteps();
                Date caseStart = testCase.getStartTime();
                Date prevEnd = null;
                for (int si = 0; si < stepList.size(); si++) {
                    LocalTestStep step = stepList.get(si);
                    boolean priorFail = hasPriorFailedStep(stepList, si);
                    Long deltaMs = null;
                    if (!priorFail) {
                        Date stepEnd = step.getEndTime();
                        if (stepEnd != null) {
                            if (si == 0 && caseStart != null) {
                                deltaMs = stepEnd.getTime() - caseStart.getTime();
                            } else if (prevEnd != null) {
                                deltaMs = stepEnd.getTime() - prevEnd.getTime();
                            }
                            prevEnd = stepEnd;
                        }
                    }
                    String timeLbl = formatStepDeltaSecondsMillis(deltaMs);
                    String rowCls = priorFail ? "skipped" : stepRowCssClass(step);
                    String pill = priorFail ? "SKIP" : stepPillLabelForReport(step);
                    boolean hasStepScreenshots = step.getScreenshots() != null && !step.getScreenshots().isEmpty();
                    html.append("<div class=\"test-step ").append(rowCls).append("\" data-step-idx=\"").append(si).append("\">\n");
                    html.append("<div class=\"test-step-hd");
                    if (hasStepScreenshots) {
                        html.append(" has-shot-hd");
                    }
                    html.append("\">\n");
                    html.append("<p class=\"test-step-title");
                    if (hasStepScreenshots) {
                        html.append(" has-shot\" role=\"button\" tabindex=\"0\" title=\"Click to show/hide screenshot\"");
                    } else {
                        html.append("\"");
                    }
                    html.append(">");
                    if (hasStepScreenshots) {
                        html.append("<span class=\"step-shot-icon\" aria-hidden=\"true\">&#128247;</span> ");
                    }
                    html.append(escapeHtml(step.getTeststepName())).append("</p>\n");
                    html.append("<div class=\"test-step-badges\">\n");
                    html.append("<span class=\"step-status-pill ").append(rowCls).append("\">").append(escapeHtml(pill)).append("</span>\n");
                    html.append("<span class=\"step-delta\">").append(escapeHtml(timeLbl)).append("</span>\n");
                    html.append("</div>\n");
                    html.append("</div>\n");
                    if (step.getErrorMessage() != null && !step.getErrorMessage().isEmpty()) {
                        html.append("<div class=\"error\">").append(escapeHtml(step.getErrorMessage())).append("</div>\n");
                    }

                    // Display logs if any (usually for failed steps)
                    if (step.getLogs() != null && !step.getLogs().isEmpty()) {
                        html.append("<div class=\"step-logs\">\n");
                        html.append("<p><strong>Execution Logs:</strong></p>\n");
                        html.append("<div class=\"log-container\">\n");
                        for (String logLine : step.getLogs()) {
                            // Determine log level for styling
                            String logClass = "log-info";
                            if (logLine.contains(" ERROR ") || logLine.contains("ERROR")) {
                                logClass = "log-error";
                            } else if (logLine.contains(" WARN ") || logLine.contains("WARN")) {
                                logClass = "log-warn";
                            } else if (logLine.contains(" DEBUG ") || logLine.contains("DEBUG")) {
                                logClass = "log-debug";
                            }
                            html.append("<div class=\"log-line ").append(logClass).append("\">").append(escapeHtml(logLine)).append("</div>\n");
                        }
                        html.append("</div>\n");
                        html.append("</div>\n");
                    }

                    // Display screenshots if any (hidden until step title is clicked)
                    if (hasStepScreenshots) {
                        html.append("<div class=\"step-screenshots\">\n");
                        for (String screenshot : step.getScreenshots()) {
                            // Calculate relative path from html folder to report root
                            String relativePath = ".." + File.separator + screenshot;
                            html.append("<div class=\"screenshot\">\n");
                            html.append("<img src=\"").append(relativePath.replace("\\", "/")).append("\" alt=\"Screenshot\" style=\"max-width: 800px; margin: 10px 0; border: 1px solid #ddd; border-radius: 4px; cursor: pointer;\" onclick=\"window.open(this.src, '_blank')\">\n");
                            html.append("</div>\n");
                        }
                        html.append("</div>\n");
                    }

                    html.append("</div>\n");
                }
            }

            if (!moviePaths.isEmpty()) {
                String firstUrl = (".." + File.separator + moviePaths.get(0)).replace("\\", "/");
                html.append("<div class=\"screenshot-movie\" id=\"screenshot-movie-").append(testCaseId).append("\" data-interval-ms=\"1000\">\n");
                html.append("<h4 class=\"screenshot-movie-title\">Screenshot movie</h4>\n");
                html.append("<p class=\"screenshot-movie-hint\">All step screenshots in order. Auto-play advances every 1 second; use controls below.</p>\n");
                html.append("<div class=\"screenshot-movie-sources\" hidden aria-hidden=\"true\">\n");
                for (String shot : moviePaths) {
                    String url = (".." + File.separator + shot).replace("\\", "/");
                    html.append("<span data-src=\"").append(escapeHtml(url)).append("\"></span>\n");
                }
                html.append("</div>\n");
                html.append("<div class=\"screenshot-movie-stage\">\n");
                html.append("<img class=\"screenshot-movie-img\" src=\"").append(escapeHtml(firstUrl)).append("\" alt=\"Screenshot frame\" onclick=\"window.open(this.src, '_blank')\">\n");
                html.append("</div>\n");
                html.append("<div class=\"screenshot-movie-controls\">\n");
                html.append("<button type=\"button\" class=\"screenshot-movie-btn\" data-act=\"prev\" title=\"Previous frame\" aria-label=\"Previous frame\">&#9664;</button>\n");
                html.append("<button type=\"button\" class=\"screenshot-movie-btn\" data-act=\"play\" title=\"Play\" aria-label=\"Play\">&#9654;</button>\n");
                html.append("<button type=\"button\" class=\"screenshot-movie-btn\" data-act=\"pause\" title=\"Pause\" aria-label=\"Pause\">&#9208;</button>\n");
                html.append("<button type=\"button\" class=\"screenshot-movie-btn\" data-act=\"next\" title=\"Next frame\" aria-label=\"Next frame\">&#9658;</button>\n");
                html.append("</div>\n");
                html.append("<div class=\"screenshot-movie-bar\"><span class=\"screenshot-movie-counter\">1 / ").append(moviePaths.size()).append("</span>");
                html.append("<span>Click image to open full size</span></div>\n");
                html.append("</div>\n");
            }

            html.append("</div>\n"); // Close test-case
            html.append("</div>\n"); // Close test-case-content
            testCaseIndex++;
        }

        html.append("</div>\n"); // Close container
        html.append("</div>\n"); // Close content-area
        html.append("</div>\n"); // Close main-wrapper
        html.append("<script>\n");
        html.append("window.addEventListener('load', function() {\n");
        html.append("    drawPieChart(").append(passed).append(", ").append(failed).append(", ").append(skipped).append(");\n");
        html.append("    startScreenshotMovieForActive();\n");
        html.append("});\n");
        html.append("</script>\n");
        html.append("</body>\n</html>");

        FileUtils.writeFile(filePath, html.toString());
    }

    /**
     * Generate JSON report for Merv import
     */
    private void generateJsonReport(String filePath) throws Exception {
        // Create a JSON structure that can be imported into Merv
        Map<String, Object> jsonReport = new LinkedHashMap<>();
        jsonReport.put("testSuite", localTestSuite);
        jsonReport.put("exportDate", new Date().toString());
        jsonReport.put("version", "1.0");
        jsonReport.put("running", false);
        jsonReport.put("lastActivityMillis", System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(jsonReport);
        FileUtils.writeFile(filePath, json);
        if (currentReportFolderPath != null) {
            MervFailureTestJsonWriter.writeFromJsonReport(currentReportFolderPath, jsonReport);
        }
    }

    /**
     * Escape HTML special characters
     */
    private static String escapeHtml(String text) {
        return MervHtmlEscape.escapeHtml(text);
    }

    /** e.g. 11th April 2026 04:47:39 PM */
    private static String dayOrdinalEnglish(int day) {
        if (day >= 11 && day <= 13) {
            return day + "th";
        }
        switch (day % 10) {
            case 1:
                return day + "st";
            case 2:
                return day + "nd";
            case 3:
                return day + "rd";
            default:
                return day + "th";
        }
    }

    private String formatReportDateTimeEnglish(Date date) {
        if (date == null) {
            return "";
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        String month = new SimpleDateFormat("MMMM", Locale.ENGLISH).format(date);
        int year = cal.get(Calendar.YEAR);
        String clock = new SimpleDateFormat("hh:mm:ss a", Locale.ENGLISH).format(date);
        return dayOrdinalEnglish(day) + " " + month + " " + year + " " + clock;
    }

    /**
     * Clock-style duration: {@code 00:11 Sec} under one hour; {@code 01:05:30} when an hour or more.
     */
    private String formatDuration(long milliseconds) {
        long totalSec = Math.max(0L, milliseconds / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return String.format(Locale.ENGLISH, "%02d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ENGLISH, "%02d:%02d Sec", m, s);
    }

    /** After a hard failure, Cucumber does not run remaining steps; those may still be listed as in progress. */
    private static boolean hasPriorFailedStep(List<LocalTestStep> steps, int index) {
        if (steps == null || index <= 0) {
            return false;
        }
        for (int j = 0; j < index; j++) {
            LocalTestStep p = steps.get(j);
            if (p != null && p.getStatus() != null && "FAILED".equalsIgnoreCase(p.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /** Step duration as {@code Xs Yms} for the HTML report; em dash when unknown. */
    private static String formatStepDeltaSecondsMillis(Long deltaMs) {
        if (deltaMs == null || deltaMs < 0) {
            return "\u2014";
        }
        long sec = deltaMs / 1000L;
        long ms = deltaMs % 1000L;
        return sec + "s " + ms + "ms";
    }

    /** Row CSS class for step chrome (maps PENDING / unfinished steps to {@code skipped} styling). */
    private static String stepRowCssClass(LocalTestStep step) {
        if (step == null) {
            return "skipped";
        }
        String u = step.getStatus() == null ? "" : step.getStatus().toUpperCase(Locale.ENGLISH);
        if (u.isEmpty() || "PENDING".equals(u)) {
            return "skipped";
        }
        if ("SKIPPED".equals(u)) {
            return "skipped";
        }
        if ("PASSED".equals(u)) {
            return "passed";
        }
        if ("FAILED".equals(u)) {
            return "failed";
        }
        if ("IN_PROGRESS".equals(u)) {
            if (step.getEndTime() == null) {
                return "skipped";
            }
            return "in_progress";
        }
        return "skipped";
    }

    private static String stepPillLabelForReport(LocalTestStep step) {
        if (step == null) {
            return "SKIP";
        }
        String u = step.getStatus() == null ? "" : step.getStatus().toUpperCase(Locale.ENGLISH);
        if (u.isEmpty() || "PENDING".equals(u)) {
            return "SKIP";
        }
        if ("SKIPPED".equals(u)) {
            return "SKIP";
        }
        if ("PASSED".equals(u)) {
            return "PASS";
        }
        if ("FAILED".equals(u)) {
            return "FAIL";
        }
        if ("IN_PROGRESS".equals(u)) {
            if (step.getEndTime() == null) {
                return "SKIP";
            }
            return "IN PROGRESS";
        }
        return "SKIP";
    }

    /** All screenshot paths for a test case, in step order (for HTML movie strip). */
    private static List<String> collectTestCaseScreenshotPaths(LocalTestCase testCase) {
        List<String> paths = new ArrayList<>();
        if (testCase == null || testCase.getTestSteps() == null) {
            return paths;
        }
        for (LocalTestStep step : testCase.getTestSteps()) {
            if (step.getScreenshots() == null) {
                continue;
            }
            for (String s : step.getScreenshots()) {
                if (s != null && !s.trim().isEmpty()) {
                    paths.add(s);
                }
            }
        }
        return paths;
    }

    /**
     * Prefer Selenium WebDriverException#getRawMessage() when available (cleaner than getMessage()),
     * while keeping this module free from a hard Selenium compile dependency.
     */
    private static String extractReadableErrorMessage(Throwable err) {
        if (err == null) {
            return null;
        }
        String msg = err.getMessage();
        try {
            Class<?> wdEx = Class.forName("org.openqa.selenium.WebDriverException");
            if (wdEx.isInstance(err)) {
                java.lang.reflect.Method rawMethod = wdEx.getMethod("getRawMessage");
                Object raw = rawMethod.invoke(err);
                if (raw instanceof String && !((String) raw).trim().isEmpty()) {
                    msg = (String) raw;
                }
            }
        } catch (Throwable ignored) {
            // Selenium not present or reflective call failed; keep default message.
        }
        return msg != null ? msg : String.valueOf(err);
    }

    /** Normalized failure message for grouping in HTML report (failed cases only). */
    private static String normalizeFailureReasonForReport(LocalTestCase tc) {
        if (tc == null || !"FAILED".equals(tc.getStatus())) {
            return "";
        }
        String r = tc.getFailureReason();
        if (r == null || r.trim().isEmpty()) {
            return "(No failure message)";
        }
        return r.trim();
    }

    /**
     * Clean up ThreadLocal resources (should be called when test execution finishes)
     */
    private void cleanupThreadLocal() {
        threadLocalActiveStepId.remove();
        threadLocalActiveTestCaseId.remove();
        threadLocalSkipNextStep.remove();
        threadLocalSkipStepViewInReport.remove();
        threadLocalCurrentStepIsSkipped.remove();
        threadLocalAutomationTool.remove();
        threadLocalAutomationDriver.remove();
    }

    // Local data structure classes for storing test data when Merv is disabled
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
        private List<MervTestDataFileHtml.AttachedFile> attachedFiles;

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
        public List<MervTestDataFileHtml.AttachedFile> getAttachedFiles() { return attachedFiles; }
        public void setAttachedFiles(List<MervTestDataFileHtml.AttachedFile> attachedFiles) { this.attachedFiles = attachedFiles; }
    }

    /**
     * Extract logs from log4j.log file for a specific time range
     * @param startTime Start time of the step
     * @param endTime End time of the step
     * @return List of log lines within the time range
     */
    private List<String> extractLogsForTimeRange(Date startTime, Date endTime) {
        List<String> relevantLogs = new ArrayList<>();
        if (startTime == null || endTime == null) {
            return relevantLogs;
        }

        try {
            String logFilePath = System.getProperty("user.dir") + File.separator + "log4j.log";
            File logFile = new File(logFilePath);

            if (!logFile.exists()) {
                System.out.println("Log file not found: " + logFilePath);
                return relevantLogs;
            }

            // Parse log file
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
            List<String> allLines = java.nio.file.Files.readAllLines(logFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);

            // Add a small buffer before start time and after end time to capture context
            long bufferMs = 1000; // 1 second buffer
            Date searchStartTime = new Date(startTime.getTime() - bufferMs);
            Date searchEndTime = new Date(endTime.getTime() + bufferMs);

            for (String line : allLines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }

                // Parse timestamp from log line
                // Format: yyyy-MM-dd HH:mm:ss,SSS LEVEL - message
                try {
                    // Extract timestamp (first 23 characters: yyyy-MM-dd HH:mm:ss,SSS)
                    if (line.length() < 23) {
                        continue;
                    }

                    String timestampStr = line.substring(0, 23);
                    Date logTime = logDateFormat.parse(timestampStr);

                    // Check if log time is within the step execution time range
                    if (logTime.compareTo(searchStartTime) >= 0 && logTime.compareTo(searchEndTime) <= 0) {
                        relevantLogs.add(line);
                    }
                } catch (Exception e) {
                    // Skip lines that don't match the expected format
                    continue;
                }
            }

        } catch (Exception e) {
            System.err.println("Error reading log file: " + e.getMessage());
            e.printStackTrace();
        }

        return relevantLogs;
    }
}
