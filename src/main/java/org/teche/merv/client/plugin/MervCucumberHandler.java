package org.teche.merv.client.plugin;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.*;
import org.teche.merv.client.MervClient;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.dto.*;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.utils.FileUtils;
import org.teche.merv.client.utils.ReportsDeleteServer;

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

    /** Branding for locally generated HTML reports (sidebar logo — red variant for light nav). */
    private static final String MERV_REPORT_LOGO_URL = "https://merv.online/images/logo-red.png";
    /** Primary UI gradient for local report chrome (sidebar, accents). */
    private static final String MERV_REPORT_GRADIENT_CSS = "linear-gradient(135deg,#e90101,#c20000)";
    /** If {@code running} stays true but JSON is not refreshed for this long, live UI treats the run as aborted (killed/stopped JVM). */
    private static final long LOCAL_RUN_STALE_AFTER_MS = 60_000L;

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
            stepScreenshotCaptureEnabled = readScreenshotEnabledFromProperties(mervProp);

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
            String appendSuite = System.getenv("merv.append_suite") == null ? mervProp.getProperty("merv.append_suite"):System.getenv("merv.append_suite");
            String suiteAlias = System.getenv("merv.append_suite_alias") == null ? mervProp.getProperty("merv.append_suite_alias"):System.getenv("merv.append_suite_alias");

            if(appendSuite == null){
                if(suiteAlias != null){
                    suiteId = client.getTestSuiteIdByAlias(suiteAlias);
                }else {
                    TestSuiteRequest testSuite = new TestSuiteRequest();
                    testSuite.setTitle(mervProp.getProperty("merv.regression_suite"));
                    testSuite.setHierarchyId(UUID.fromString(mervProp.getProperty("merv.parent_hierarchy")));
                    testSuite.setSprint(mervProp.getProperty("merv.sprint"));
                    TestSuiteResponse res = client.createTestSuite(testSuite);
                    suiteId = res.getId();
                }
            }else{
                suiteId = UUID.fromString(appendSuite);
            }


        }catch(MervClientException e){

        }catch (Exception e){
            if (mervProp != null && mervProp.getProperty("merv.server") != null) {
                String apiKey = mervProp.getProperty("merv.api_key");
                String authInfo = (apiKey != null && !apiKey.trim().isEmpty()) ? "API Key" : mervProp.getProperty("merv.username");
                mervServerStatus(mervProp.getProperty("merv.server"), authInfo, e.getMessage());
            }

        }
    }

    private void mervTestFinish(TestCaseFinished testcase){
        if (!isMervEnabled()) {
            // Update local test case status
            if (activeTestId != null && localTestCases.containsKey(activeTestId)) {
                LocalTestCase localTestCase = localTestCases.get(activeTestId);
                localTestCase.setEndTime(new Date());
                if(testcase.getResult().getStatus() == Status.FAILED){
                    localTestCase.setStatus("FAILED");
                    if (testcase.getResult().getError() != null) {
                        localTestCase.setFailureReason(extractReadableErrorMessage(testcase.getResult().getError()));
                    }
                }else if(testcase.getResult().getStatus() == Status.SKIPPED){
                    localTestCase.setStatus("SKIPPED");
                }else{
                    localTestCase.setStatus("PASSED");
                }
                persistLocalRuntimeSnapshot(false);
            }
            return;
        }

        // Use finishTestCase which automatically calculates status based on test steps
        // and sets the end time. This is the recommended approach.
        if (activeTestId != null) {
            try {
                client.finishTestCase(activeTestId);
            } catch (MervClientException e) {
                // Log error but don't throw RuntimeException to avoid breaking test execution
                System.err.println("Error finishing test case: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    private void mervTestStart(TestCaseStarted testcase){
        try {
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

                activeTestId = localTestCase.getId();
                localTestCases.put(activeTestId, localTestCase);
                if (localTestSuite != null) {
                    localTestSuite.getTestCases().add(localTestCase);
                }
                threadLocalActiveTestCaseId.set(activeTestId);
                persistLocalRuntimeSnapshot(false);
                return;
            }

            TestCaseRequest mervTest = new TestCaseRequest();
            mervTest.setTestSuiteId(suiteId);
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

                activeStepId = localStep.getId();
                localTestSteps.put(activeStepId, localStep);

                if (activeTestId != null && localTestCases.containsKey(activeTestId)) {
                    localTestCases.get(activeTestId).getTestSteps().add(localStep);
                }

                threadLocalActiveStepId.set(activeStepId);
                threadLocalCurrentStepIsSkipped.set(false);
                persistLocalRuntimeSnapshot(false);
                return;
            }

            TestStepRequest mervStep = new TestStepRequest();
            mervStep.setTeststepName(step.getStep().getText());
            mervStep.setStatus("IN_PROGRESS");
            mervStep.setTestcaseId(activeTestId);
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
            // If activeStepId is null, something went wrong, just return
            if (activeStepId == null) {
                return;
            }

            if (!isMervEnabled()) {
                // Handle local step finish
                LocalTestStep localStep = localTestSteps.get(activeStepId);
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
                        if (activeTestId != null && localTestCases.containsKey(activeTestId)) {
                            localTestCases.get(activeTestId).getTestSteps().remove(localStep);
                        }
                        localTestSteps.remove(activeStepId);
                        activeStepId = null;
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
                    if (activeTestId != null && localTestCases.containsKey(activeTestId)) {
                        localTestCases.get(activeTestId).setStatus("FAILED");
                    }
                }else if(teststep.getResult().getStatus() == Status.PASSED){
                    localStep.setStatus("PASSED");
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
                        client.deleteTestStep(activeStepId);
                        activeStepId = null;
                        threadLocalActiveStepId.remove();
                    } catch (MervClientException e) {
                        System.err.println("Warning: Failed to delete skipped step: " + e.getMessage());
                    }
                } else {
                    // viewInReport is true - keep step but set status to SKIPPED
                    try {
                        TestStepPatchRequest stepUpdateRequest = new TestStepPatchRequest();
                        stepUpdateRequest.setStatus("SKIPPED");
                        client.patchTestStep(activeStepId, stepUpdateRequest);
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
            TestStepPatchRequest stepUpdateRequest = new TestStepPatchRequest();
            PickleStepTestStep step = (PickleStepTestStep)teststep.getTestStep();
            System.out.println(teststep.getResult().getStatus());

            tryCaptureAutomationScreenshotServer(activeStepId);

            if(teststep.getResult().getStatus() == Status.FAILED) {
                stepUpdateRequest.setStatus("FAILED");
                try {
                    client.patchTestStep(activeStepId,stepUpdateRequest);
                    client.updateTestCaseStatus(activeTestId, TestCaseStatus.FAILED);
                } catch (MervClientException e) {
                    throw new RuntimeException(e);
                }
            }else if(teststep.getResult().getStatus() == Status.PASSED){
                stepUpdateRequest.setStatus("PASSED");
                try {
                    client.patchTestStep(activeStepId, stepUpdateRequest);
                } catch (MervClientException e) {
                    throw new RuntimeException(e);
                }
            }

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
                // Store screenshot reference in local step
                LocalTestStep localStep = localTestSteps.get(stepId);
                if (localStep != null) {
                    if (localStep.getScreenshots() == null) {
                        localStep.setScreenshots(new ArrayList<>());
                    }
                    localStep.getScreenshots().add(savedFilePath);
                }
                System.out.println("File saved to report folder: " + savedFilePath);
                // Persist runtime data so live report can pick up newly attached files.
                if (localTestSuite != null) {
                    LocalTestSuite suite = localTestSuite;
                    try {
                        Map<String, Object> jsonReport = new LinkedHashMap<>();
                        jsonReport.put("testSuite", suite);
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

    /**
     * Static method to add a test step with a specific step type
     * This method can be called from external classes without exposing MervClient
     *
     * @param stepName The name/description of the test step
     * @param stepType The type of step (testdata, assertion, information)
     * @param expected Optional expected result
     * @param actual Optional actual result
     * @param testdata Optional test data
     * @param prereq Optional prerequisites
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    public static org.teche.merv.client.dto.TestStepResponse addStep(
            String stepName,
            String stepType,
            String expected,
            String actual,
            String testdata,
            String prereq) throws MervClientException {
        if (!isMervEnabled()) {
            // When Merv is disabled, add step to local storage
            UUID testCaseId = getActiveTestCaseId();
            if (testCaseId == null) {
                throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
            }

            if (stepName == null || stepName.trim().isEmpty()) {
                throw new MervClientException("Step name is required and cannot be empty.");
            }

            LocalTestStep localStep = new LocalTestStep();
            localStep.setId(UUID.randomUUID());
            localStep.setTeststepName(stepName);
            localStep.setStepType(stepType);
            localStep.setStatus("PENDING");
            localStep.setStartTime(new Date());
            localStep.setExpected(expected);
            localStep.setActual(actual);
            localStep.setTestdata(testdata);
            localStep.setPrereq(prereq);

            if (localTestCases.containsKey(testCaseId)) {
                localTestCases.get(testCaseId).getTestSteps().add(localStep);
            }
            localTestSteps.put(localStep.getId(), localStep);

            // Return a mock response (since we can't return null and the return type is required)
            // Note: This won't work perfectly, but it allows the code to continue
            throw new MervClientException("Merv is disabled. Steps are being stored locally and will be included in the local report.");
        }

        MervClient client = sharedClient;
        UUID testCaseId = getActiveTestCaseId();

        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        if (testCaseId == null) {
            throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
        }

        if (stepName == null || stepName.trim().isEmpty()) {
            throw new MervClientException("Step name is required and cannot be empty.");
        }

        // Convert user-friendly step type to API format
        String apiStepType;
        try {
            org.teche.merv.client.dto.StepType stepTypeEnum = org.teche.merv.client.dto.StepType.fromString(stepType);
            apiStepType = stepTypeEnum.getApiValue();
        } catch (IllegalArgumentException e) {
            throw new MervClientException("Invalid step type: " + stepType + ". Valid values are: testdata, assertion, information", e);
        }

        // Create test step request
        org.teche.merv.client.dto.TestStepRequest request = new org.teche.merv.client.dto.TestStepRequest();
        request.setTeststepName(stepName);
        request.setTestcaseId(testCaseId);
        request.setStepType(apiStepType);
        request.setStatus("PENDING");

        if (expected != null) {
            request.setExpected(expected);
        }
        if (actual != null) {
            request.setActual(actual);
        }
        if (testdata != null) {
            request.setTestdata(testdata);
        }
        if (prereq != null) {
            request.setPrereq(prereq);
        }

        return client.createTestStep(request);
    }

    /**
     * Static method to add a test step with minimal parameters (overloaded convenience method)
     *
     * @param stepName The name/description of the test step
     * @param stepType The type of step (testdata, assertion, information)
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    public static org.teche.merv.client.dto.TestStepResponse addStep(String stepName, String stepType) throws MervClientException {
        return addStep(stepName, stepType, null, null, null, null);
    }

    /**
     * Static method to add a test data step with string data
     * Convenience method for creating TEST_DATA type steps with string test data
     *
     * @param stepName The name/description of the test step
     * @param testdata The test data content as string
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    public static org.teche.merv.client.dto.TestStepResponse addDataStep(
            String stepName,
            String testdata
    ) throws MervClientException {

        return addStep(stepName, StepType.TESTDATA.getApiValue(), null, null, testdata, null);
    }
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
    public static org.teche.merv.client.dto.TestStepResponse addDataStep(
            String stepName,
            File file,
            org.teche.merv.client.dto.FileType fileType,
            String prereq) throws MervClientException {
        if (!isMervEnabled()) {
            // When Merv is disabled, add step to local storage
            UUID testCaseId = getActiveTestCaseId();
            if (testCaseId == null) {
                throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
            }

            if (stepName == null || stepName.trim().isEmpty()) {
                throw new MervClientException("Step name is required and cannot be empty.");
            }

            if (file == null || !file.exists()) {
                throw new MervClientException("File does not exist: " + (file != null ? file.getPath() : "null"));
            }

            LocalTestStep localStep = new LocalTestStep();
            localStep.setId(UUID.randomUUID());
            localStep.setTeststepName(stepName);
            localStep.setStepType("TEST_DATA");
            localStep.setStatus("PENDING");
            localStep.setStartTime(new Date());
            localStep.setTestdata("File: " + file.getName() + " (Type: " + (fileType != null ? fileType.getType() : "unknown") + ")");
            localStep.setPrereq(prereq);

            if (localTestCases.containsKey(testCaseId)) {
                localTestCases.get(testCaseId).getTestSteps().add(localStep);
            }
            localTestSteps.put(localStep.getId(), localStep);

            throw new MervClientException("Merv is disabled. Steps are being stored locally and will be included in the local report.");
        }

        MervClient client = sharedClient;
        UUID testCaseId = getActiveTestCaseId();

        if (client == null) {
            throw new MervClientException("MervClient is not initialized. Make sure MervCucumberHandler is properly configured.");
        }

        if (testCaseId == null) {
            throw new MervClientException("No active test case found. Step creation must be called during an active test case execution.");
        }

        if (stepName == null || stepName.trim().isEmpty()) {
            throw new MervClientException("Step name is required and cannot be empty.");
        }

        if (file == null || !file.exists()) {
            throw new MervClientException("File does not exist: " + (file != null ? file.getPath() : "null"));
        }

        // Create test step request with file name in testdata field
        org.teche.merv.client.dto.TestStepRequest request = new org.teche.merv.client.dto.TestStepRequest();
        request.setTeststepName(stepName);
        request.setTestcaseId(testCaseId);
        request.setStepType("TEST_DATA");
        request.setStatus("PENDING");
        request.setTestdata("File: " + file.getName() + " (Type: " + (fileType != null ? fileType.getType() : "unknown") + ")");
        if (prereq != null) {
            request.setPrereq(prereq);
        }

        // Create the step first
        org.teche.merv.client.dto.TestStepResponse stepResponse = client.createTestStep(request);

        // Attach the file to the step
        try {
            client.uploadFile(stepResponse.getId(), file, "Test data file");
        } catch (MervClientException e) {
            // If file upload fails, log but don't fail the step creation
            System.err.println("Warning: Failed to attach file to test step: " + e.getMessage());
        }

        return stepResponse;
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
    public static org.teche.merv.client.dto.TestStepResponse addValidationStep(
            String stepName,
            String expected,
            String actual,
            String testdata,
            String prereq) throws MervClientException {
        return addStep(stepName, StepType.ASSERTION.getApiValue() , expected, actual, testdata, prereq);
    }

    /**
     * Static method to add a validation/assertion step with minimal parameters
     *
     * @param stepName The name/description of the test step
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    public static org.teche.merv.client.dto.TestStepResponse addValidationStep(String stepName) throws MervClientException {
        return addValidationStep(stepName, null, null, null, null);
    }

    /**
     * Static method to add a prerequisite step
     * Convenience method for creating PREREQUISITE type steps
     *
     * @param stepName The name/description of the test step
     * @param prereq Optional prerequisites
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    public static org.teche.merv.client.dto.TestStepResponse addPrerequisiteStep(
            String stepName,
            String prereq) throws MervClientException {
        return addStep(stepName, StepType.INFORMATION.getValue(), null, null, null, prereq);
    }

    /**
     * Static method to add a prerequisite step with minimal parameters
     *
     * @param stepName The name/description of the test step
     * @return TestStepResponse with the created test step details
     * @throws MervClientException if step creation fails or no active test case/client is available
     */
    public static org.teche.merv.client.dto.TestStepResponse addPrerequisiteStep(String stepName) throws MervClientException {
        return addPrerequisiteStep(stepName,  null);
    }

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
    public static void skipStep() {
        skipStep(true);
    }

    /**
     * Mark the current step to be skipped with option to control report visibility
     * This method can be called during step execution from external classes without exposing MervClient
     *
     * @param viewInReport If true, the step will be kept with SKIPPED status (visible in report, won't affect test case status)
     *                     If false, the step will be deleted from the server (not visible in report)
     */
    public static void skipStep(boolean viewInReport) {
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
     * {@code merv.screenshot=on} (or {@code screenshot=on}) is set in {@code merv.properties}.
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
        v = v.trim().toLowerCase(Locale.ROOT);
        return "on".equals(v) || "true".equals(v) || "yes".equals(v) || "1".equals(v);
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
    private String buildLiveHtmlReportContent() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html><head><meta charset=\"UTF-8\"><title>Merv Live Report</title>\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n");
        html.append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n");
        html.append("<link href=\"https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n");
        html.append("<style>");
        html.append(":root{--merv-grad:").append(MERV_REPORT_GRADIENT_CSS).append(";--sidebar-bg:#f2f3f5;--sidebar-border:#e6e8ec;--nav-text:#4a4a4a;--nav-muted:#6b6b6b;--nav-active-bg:#fdeaea;--nav-active-text:#c20000;}");
        html.append("html{scroll-behavior:smooth;}");
        html.append("html{font-family:'Roboto',system-ui,-apple-system,sans-serif;}body{font-family:'Roboto',system-ui,-apple-system,sans-serif;margin:0;padding:0;background:#fafafa;color:#333;}button,input,select,textarea{font-family:inherit;}");
        html.append("h1,h2,h3,h4,h5,h6{letter-spacing:0.5px;}");
        html.append(".main-wrapper{display:flex;min-height:100vh;}");
        html.append(".sidebar{width:300px;background:var(--sidebar-bg);color:var(--nav-text);padding:20px 16px;overflow-y:auto;position:fixed;height:100vh;box-shadow:1px 0 0 var(--sidebar-border);box-sizing:border-box;}");
        html.append(".sidebar-brand{margin-bottom:20px;padding-bottom:18px;border-bottom:1px solid var(--sidebar-border);text-align:center;}");
        html.append(".brand-logo{max-width:180px;height:auto;display:block;margin:0 auto;}");
        html.append(".sidebar-local-label{margin:12px 0 0;padding:0;font-size:13px;font-weight:700;color:var(--nav-active-text);letter-spacing:.04em;text-align:center;}");
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
        html.append(".sidebar-item-name{font-weight:600;color:#222;font-size:14px;}");
        html.append(".sidebar-status-tag{font-size:10px;font-weight:700;letter-spacing:.04em;padding:2px 8px;border-radius:999px;background:#dfe4ea;color:#111;text-transform:uppercase;white-space:nowrap;}");
        html.append(".sidebar-status-tag.pass{background:#e8f5e9;color:#1b5e20;}.sidebar-status-tag.fail{background:#ffebee;color:#b71c1c;}.sidebar-status-tag.skip{background:#fff8e1;color:#e65100;}.sidebar-status-tag.active{background:#e3f2fd;color:#0d47a1;}");
        html.append(".sidebar-item.active .sidebar-item-name,.sidebar-item.active .sidebar-status-tag{color:#222;font-weight:600;}");
        html.append(".content-area{margin-left:300px;flex:1;padding:20px;}");
        html.append(".report-toolbar{margin-bottom:14px;padding-bottom:12px;border-bottom:1px solid #eee;}");
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
        html.append(".test-step{margin:8px 0;padding:8px;background-color:#fff;border-radius:3px;color:#333;}.test-step.passed{border-left:3px solid #4CAF50;}.test-step.failed{border-left:3px solid #f44336;}.test-step.skipped{border-left:3px solid #FF9800;}.test-step.in_progress{border-left:3px solid #2196F3;}");
        html.append(".error{color:#f44336;font-size:.9em;margin-top:5px;background-color:#ffebee;padding:8px;border-radius:3px;border-left:3px solid #f44336;}");
        html.append(".screenshots{margin-top:10px;}.screenshot{margin:10px 0;}.screenshot img{display:block;max-width:800px;margin:10px 0;border:1px solid #ddd;border-radius:4px;cursor:pointer;}");
        html.append(".status-pill{display:inline-block;padding:4px 12px;border-radius:20px;background:").append(MERV_REPORT_GRADIENT_CSS).append(";color:#fff;font-size:11px;font-weight:700;margin-left:8px;text-transform:uppercase;letter-spacing:.04em;}");
        html.append(".status-pill.aborted{background:#6c757d!important;}");
        html.append("</style></head><body>");
        html.append("<div class='main-wrapper'><div class='sidebar'>");
        html.append("<div class='sidebar-brand'><img class='brand-logo' src='").append(MERV_REPORT_LOGO_URL).append("' alt='Merv'><p class='sidebar-local-label'>Merv Local</p></div>");
        html.append("<div class='sidebar-search'><input type='text' id='testcase-search' placeholder='Search test cases...' onkeyup='searchTestCases()'></div>");
        html.append("<div class='sidebar-filters'>");
        html.append("<button class='filter-btn active' data-status='all' onclick=\"filterByStatus('all')\">All</button>");
        html.append("<button class='filter-btn' data-status='passed' onclick=\"filterByStatus('passed')\">Pass</button>");
        html.append("<button class='filter-btn' data-status='failed' onclick=\"filterByStatus('failed')\">Fail</button>");
        html.append("<button class='filter-btn' data-status='skipped' onclick=\"filterByStatus('skipped')\">Skip</button>");
        html.append("</div><h2>Test Cases</h2><div id='sidebar-list'></div></div>");
        html.append("<div class='content-area'><div class='container'>");
        html.append("<div class='report-toolbar'><a class='local-dash' href='../../index.html'>Local Dashboard</a></div>");
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
        html.append("var currentFilter='all';var currentSearch='';var selectedId='';var latestData=null;var pollTimer=null;var requestedTestcaseName=(function(){try{return (new URLSearchParams(window.location.search).get('testcase')||'').trim().toLowerCase();}catch(e){return'';}})();var appliedRequestedCase=false;");
        html.append("function decodeUi(s){if(s==null)return'';try{return decodeURIComponent(String(s));}catch(e){return String(s);}}");
        html.append("function detectReportFolderName(){try{var p=String(window.location.pathname||'').replace(/\\\\/g,'/');var parts=p.split('/').filter(function(x){return x&&x!=='.';});var htmlIdx=parts.lastIndexOf('html');if(htmlIdx>0)return decodeUi(parts[htmlIdx-1]);if(parts.length>1)return decodeUi(parts[parts.length-2]);if(parts.length)return decodeUi(parts[0]);}catch(e){}return'—';}");
        html.append("(function renderFolderMeta(){var el=document.getElementById('report-folder');if(!el)return;el.innerHTML='<strong>Folder name:</strong> '+e(detectReportFolderName());})();");
        html.append("var STALE_MS=").append(LOCAL_RUN_STALE_AFTER_MS).append(";");
        html.append("function lastActivityMs(d){var n=d&&d.lastActivityMillis;if(typeof n==='number'&&n>0)return n;var p=Date.parse(String((d&&d.exportDate)||''));return isNaN(p)?0:p;}");
        html.append("function isStaleAborted(d){if(!d||d.running!==true)return false;if(d.aborted===true)return true;var la=lastActivityMs(d);if(la<=0)return false;return Date.now()-la>STALE_MS;}");
        html.append("function stopPolling(){if(pollTimer!==null){clearInterval(pollTimer);pollTimer=null;}}");
        html.append("function statusClass(st){var x=c(st);return x==='passed'||x==='failed'||x==='skipped'||x==='in_progress'?x:'in_progress';}");
        html.append("function statusTag(st){var x=c(st);if(x==='passed')return{txt:'PASS',cls:'pass'};if(x==='failed')return{txt:'FAIL',cls:'fail'};if(x==='skipped')return{txt:'SKIP',cls:'skip'};return{txt:'ACTIVE',cls:'active'};}");
        html.append("function drawPieChart(passed,failed,skipped){var canvas=document.getElementById('pieChart');if(!canvas)return;var ctx=canvas.getContext('2d');var w=canvas.width,h=canvas.height,cx=w/2,cy=h/2,r=Math.min(w,h)*0.36;ctx.clearRect(0,0,w,h);var total=passed+failed+skipped;if(total===0){ctx.fillStyle='#eee';ctx.beginPath();ctx.arc(cx,cy,r,0,2*Math.PI);ctx.fill();return;}var start=-Math.PI/2;var pa=(passed/total)*2*Math.PI,fa=(failed/total)*2*Math.PI,ka=(skipped/total)*2*Math.PI;if(passed>0){ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+pa);ctx.closePath();ctx.fillStyle='#4CAF50';ctx.fill();start+=pa;}if(failed>0){ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+fa);ctx.closePath();ctx.fillStyle='#f44336';ctx.fill();start+=fa;}if(skipped>0){ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+ka);ctx.closePath();ctx.fillStyle='#FF9800';ctx.fill();}ctx.beginPath();ctx.arc(cx,cy,r,0,2*Math.PI);ctx.strokeStyle='#fff';ctx.lineWidth=2;ctx.stroke();}");
        html.append("function toggleChartBlock(){var body=document.getElementById('chart-block-body');var head=document.getElementById('chart-block-head');if(!body||!head)return;var collapsed=body.classList.toggle('collapsed');head.classList.toggle('collapsed',collapsed);head.setAttribute('aria-expanded',collapsed?'false':'true');if(!collapsed&&typeof drawPieChart==='function'&&latestData&&latestData.testSuite){var tc=latestData.testSuite.testCases||[],p=0,f=0,k=0;tc.forEach(function(t){if(t.status==='PASSED')p++;else if(t.status==='FAILED')f++;else if(t.status==='SKIPPED')k++;});drawPieChart(p,f,k);}}");
        html.append("function parseTs(v){if(v==null||v===undefined)return null;if(typeof v==='number'){var n=v;return new Date(n>1e11?n:n*1000);}if(typeof v==='string'){var s=new Date(v);return isNaN(s.getTime())?null:s;}if(typeof v==='object'&&v){if(typeof v.time==='number')return new Date(v.time);if(Array.isArray(v)&&v.length>=3)return new Date(v[0],(v[1]||1)-1,v[2]||1,v[3]||0,v[4]||0,v[5]||0);}var t=new Date(v);return isNaN(t.getTime())?null:t;}");
        html.append("function fmtHms(ms){var d=new Date(ms);function z(x){return(x<10?'0':'')+x;}return z(d.getHours())+':'+z(d.getMinutes())+':'+z(d.getSeconds());}");
        html.append("function floor5s(t){return Math.floor(t/5000)*5000;}");
        html.append("function drawTrendChart(tc,suite,running){try{var SLOT=5000;var canvas=document.getElementById('trendChart');if(!canvas)return;var ctx=canvas.getContext('2d');if(!ctx)return;var W=canvas.width,H=canvas.height;if(W<10||H<10)return;ctx.clearRect(0,0,W,H);var padL=40,padR=8,padT=4,padB=40,pw=W-padL-padR,ph=H-padT-padB;var passC={},failC={};(tc||[]).forEach(function(row){var et=parseTs(row.endTime);if(!et)return;var bk=floor5s(et.getTime());var st=String(row.status||'').toUpperCase();if(st==='PASSED')passC[bk]=(passC[bk]||0)+1;else if(st==='FAILED')failC[bk]=(failC[bk]||0)+1;});var suiteStart=suite&&parseTs(suite.startTime);var nowMs=Date.now();var minMs=null,maxMs=null;if(suiteStart)minMs=floor5s(suiteStart.getTime());function upd(ms){if(minMs==null||ms<minMs)minMs=ms;if(maxMs==null||ms>maxMs)maxMs=ms;}Object.keys(passC).forEach(function(k){upd(+k);});Object.keys(failC).forEach(function(k){upd(+k);});if(minMs==null){minMs=floor5s(nowMs);maxMs=minMs;}if(maxMs==null)maxMs=minMs;var curSlot=floor5s(nowMs);if(running&&curSlot>maxMs)maxMs=curSlot;if(maxMs<minMs)maxMs=minMs;var buckets=[];for(var m=minMs;m<=maxMs;m+=SLOT)buckets.push({t:m,p:passC[m]||0,f:failC[m]||0});if(buckets.length>180)buckets=buckets.slice(-180);var n=buckets.length;if(n===0){buckets.push({t:minMs,p:0,f:0});n=1;}var peaks=buckets.map(function(bk){return Math.max(bk.p,bk.f);});var maxY=Math.max(1,peaks.length?Math.max.apply(null,peaks):0);function xCenter(i){return padL+(n<=1?pw/2:(i/(n-1))*pw);}function yVal(v){return padT+ph-(v/maxY)*ph;}ctx.strokeStyle='#ddd';ctx.lineWidth=1;ctx.setLineDash([3,4]);var yTicks=4,yi,xi;for(yi=0;yi<=yTicks;yi++){var yy=padT+(yi/yTicks)*ph;ctx.beginPath();ctx.moveTo(padL,yy);ctx.lineTo(padL+pw,yy);ctx.stroke();}var xStep=Math.max(1,Math.ceil(n/8));for(xi=0;xi<n;xi+=xStep){var xx=xCenter(xi);ctx.beginPath();ctx.moveTo(xx,padT);ctx.lineTo(xx,padT+ph);ctx.stroke();}ctx.setLineDash([]);ctx.strokeStyle='#333';ctx.beginPath();ctx.moveTo(padL,padT+ph);ctx.lineTo(padL+pw,padT+ph);ctx.stroke();ctx.beginPath();ctx.moveTo(padL,padT);ctx.lineTo(padL,padT+ph);ctx.stroke();var slotW=pw/Math.max(n,1);var gw=Math.min(slotW*0.85,36);var barW=Math.max(2,(gw-4)/2);buckets.forEach(function(bk,idx){var cx=xCenter(idx),x0=cx-gw/2,y0=padT+ph;if(bk.p>0){var y1=yVal(bk.p);ctx.fillStyle='#4CAF50';ctx.fillRect(x0,y1,barW,y0-y1);}if(bk.f>0){var y2=yVal(bk.f);ctx.fillStyle='#f44336';ctx.fillRect(x0+barW+4,y2,barW,y0-y2);}});ctx.fillStyle='#555';ctx.font='11px Arial';ctx.textAlign='right';ctx.textBaseline='middle';for(yi=0;yi<=yTicks;yi++){var yv=Math.round((yTicks-yi)*(maxY/yTicks));var yy2=padT+(yi/yTicks)*ph;ctx.fillText(String(yv),padL-4,yy2);}ctx.fillStyle='#444';ctx.font='9px Arial';ctx.textAlign='right';ctx.textBaseline='top';for(xi=0;xi<n;xi+=xStep){var lbl=fmtHms(buckets[xi].t);var xx2=xCenter(xi);ctx.save();ctx.translate(xx2,padT+ph+4);ctx.rotate(-Math.PI/5);ctx.fillText(lbl,0,0);ctx.restore();}var tPass=buckets.reduce(function(a,bk){return a+bk.p;},0),tFail=buckets.reduce(function(a,bk){return a+bk.f;},0);var el=document.getElementById('trend-meta');if(el)el.textContent=tPass+' pass · '+tFail+' fail in window · '+n+' × 5s bucket(s)'+(running?' · refresh 5s':'');}catch(err){var em=document.getElementById('trend-meta');if(em)em.textContent='Trend chart could not render: '+(err&&err.message?err.message:String(err));}}");        html.append("function renderFailureReasons(testCases){var panel=document.getElementById('failure-reasons-panel');if(!panel)return;var groups={};(testCases||[]).forEach(function(t,i){if(String(t.status||'').toUpperCase()!=='FAILED')return;var r=String(t.failureReason||'').trim();if(!r)r='(No failure message)';var firstShot='';var steps=t.testSteps||[];for(var si=0;si<steps.length&&!firstShot;si++){var ss=(steps[si]&&steps[si].screenshots)||[];if(ss.length)firstShot=String(ss[0]||'');}if(!groups[r])groups[r]=[];groups[r].push({id:'tc-'+i,name:String(t.testcaseName||('Test case '+(i+1))),shot:firstShot});});var keys=Object.keys(groups).sort(function(a,b){return groups[b].length-groups[a].length;});var h='<h3>Failure reasons</h3>';if(!keys.length){h+='<p class=\"failure-reason-empty\">No failures yet.</p>';}else{keys.forEach(function(k){var arr=groups[k]||[];h+='<div class=\"failure-reason-group\"><div class=\"failure-reason-row\"><span class=\"failure-reason-text\">'+e(k)+'</span><span class=\"failure-reason-count\">'+arr.length+'</span></div><div class=\"failure-related-cases\">';arr.forEach(function(ca){h+='<div class=\"failure-case-item\"><button type=\"button\" class=\"failure-case-link\" data-case-id=\"'+e(ca.id)+'\">'+e(ca.name)+'</button>';if(ca.shot){var sp=('../'+String(ca.shot)).replace(/\\\\/g,'/');h+='<img class=\"failure-case-thumb\" src=\"'+e(sp)+'\" alt=\"Screenshot\" onclick=\"window.open(this.src,\\'_blank\\')\">';}h+='</div>';});h+='</div></div>';});}panel.innerHTML=h;panel.querySelectorAll('.failure-case-link[data-case-id]').forEach(function(btn){btn.addEventListener('click',function(){var cid=btn.getAttribute('data-case-id');if(!cid)return;selectedId=cid;renderSelectedCase((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);renderSidebar((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);setTimeout(scrollToTestcasePanel,0);});});}");
html.append("function renderSidebar(testCases){var h='';testCases.forEach(function(t,i){var id='tc-'+i;var cls=statusClass(t.status);var tg=statusTag(t.status);h+='<div class=\"sidebar-item '+cls+(selectedId===id?' active':'')+'\" data-id=\"'+id+'\" data-status=\"'+cls+'\"><div class=\"sidebar-item-top\"><div class=\"sidebar-item-name\">'+e(t.testcaseName||'N/A')+'</div><span class=\"sidebar-status-tag '+tg.cls+'\">'+tg.txt+'</span></div></div>';});document.getElementById('sidebar-list').innerHTML=h;bindSidebarEvents();applySidebarFilters();}");
        html.append("function renderSelectedCase(testCases){if(!testCases.length){document.getElementById('testcase-content').innerHTML='<p>No runtime data yet.</p>';return;}if(!selectedId||!document.querySelector('[data-id=\"'+selectedId+'\"]')){selectedId='tc-0';}var idx=parseInt(selectedId.replace('tc-',''),10);var t=testCases[idx]||testCases[0];var cls=statusClass(t.status);var statusText=String(t.status||'IN_PROGRESS').replace(/_/g,' ');var st=parseTs(t.startTime),et=parseTs(t.endTime),dur='0m:0s:0ms';if(st&&et&&et.getTime()>=st.getTime()){var ms=et.getTime()-st.getTime();var min=Math.floor(ms/60000),sec=Math.floor((ms%60000)/1000),msec=ms%1000;dur=min+'m:'+sec+'s:'+msec+'ms';}var tags=(t.tags&&t.tags.length)?t.tags.join(', '):'N/A';var machine=t.executionMachine||'N/A';var h='<div class=\"test-case '+cls+'\">';h+='<div class=\"testcase-heading\"><h3 class=\"testcase-title\">'+e(t.testcaseName||'N/A')+'</h3><span class=\"testcase-status-chip '+cls+'\">'+e(statusText)+'</span></div>';h+='<div class=\"testcase-meta-grid\">';h+='<div class=\"testcase-meta-item\"><span class=\"testcase-meta-label\">Status</span><span class=\"testcase-meta-value\">'+e(statusText)+'</span></div>';h+='<div class=\"testcase-meta-item\"><span class=\"testcase-meta-label\">Total time taken</span><span class=\"testcase-meta-value\">'+e(dur)+'</span></div>';h+='<div class=\"testcase-meta-item\"><span class=\"testcase-meta-label\">Executed on machine</span><span class=\"testcase-meta-value\">'+e(machine)+'</span></div>';h+='</div>';if(t.tags&&t.tags.length){h+='<div><strong>Tags:</strong></div>';h+='<div class=\"testcase-tags\">';t.tags.forEach(function(tag){h+='<span class=\"testcase-tag\">'+e(tag)+'</span>';});h+='</div>';}if(t.failureReason){h+='<div class=\"error\"><strong>Error:</strong> '+e(t.failureReason)+'</div>';}var steps=t.testSteps||[];if(steps.length){h+='<h4>Test Steps:</h4>';steps.forEach(function(st){var sc=statusClass(st.status);h+='<div class=\"test-step '+sc+'\"><p><strong>'+e(st.teststepName||'Step')+'</strong> - '+e(st.status||'IN_PROGRESS')+'</p>';if(st.errorMessage){h+='<div class=\"error\">'+e(st.errorMessage)+'</div>';}if(st.screenshots&&st.screenshots.length){h+='<div class=\"screenshots\"><p><strong>Screenshots:</strong></p>';st.screenshots.forEach(function(ss){var p=('../'+String(ss||'')).replace(/\\\\/g,'/');h+='<div class=\"screenshot\"><img src=\"'+e(p)+'\" alt=\"Screenshot\" onclick=\"window.open(this.src,\\'_blank\\')\"><p style=\"font-size:.85em;color:#666;\">'+e(ss)+'</p></div>';});h+='</div>';}h+='</div>';});}h+='</div>';document.getElementById('testcase-content').innerHTML=h;}");
        html.append("function applySidebarFilters(){var items=document.querySelectorAll('#sidebar-list .sidebar-item');items.forEach(function(it){var nm=(it.querySelector('.sidebar-item-name')||{}).textContent||'';var st=it.getAttribute('data-status')||'all';var okSearch=nm.toLowerCase().indexOf(currentSearch)>=0;var okFilter=(currentFilter==='all'||st===currentFilter);it.style.display=(okSearch&&okFilter)?'block':'none';});}");
        html.append("function filterByStatus(st){currentFilter=st||'all';document.querySelectorAll('.filter-btn').forEach(function(b){b.classList.toggle('active',(b.getAttribute('data-status')||'')===currentFilter);});applySidebarFilters();}");
        html.append("function filterShowAllTestCases(){filterByStatus('all');return false;}");
        html.append("function scrollToTestcasePanel(){var tc=document.getElementById('testcase-content');if(!tc)return;try{tc.scrollIntoView({behavior:'smooth',block:'start'});}catch(err){tc.scrollIntoView(true);}try{var top=window.pageYOffset+tc.getBoundingClientRect().top-16;window.scrollTo({top:top,behavior:'smooth'});}catch(err2){window.scrollTo(0,window.pageYOffset+tc.getBoundingClientRect().top-16);}var ca=document.querySelector('.content-area');if(ca&&ca.scrollHeight>ca.clientHeight){var y=Math.max(0,tc.offsetTop-16);try{ca.scrollTo({top:y,behavior:'smooth'});}catch(err3){ca.scrollTop=y;}}}");
        html.append("function bindSidebarEvents(){var items=document.querySelectorAll('#sidebar-list .sidebar-item');items.forEach(function(it){it.onclick=function(){selectedId=it.getAttribute('data-id');renderSelectedCase((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);renderSidebar((latestData&&latestData.testSuite&&latestData.testSuite.testCases)||[]);setTimeout(scrollToTestcasePanel,0);};});}");
        html.append("document.getElementById('testcase-search').addEventListener('input',function(ev){currentSearch=(ev.target.value||'').toLowerCase();applySidebarFilters();});");
        html.append("document.querySelectorAll('.filter-btn').forEach(function(btn){btn.addEventListener('click',function(){document.querySelectorAll('.filter-btn').forEach(function(b){b.classList.remove('active');});btn.classList.add('active');currentFilter=btn.getAttribute('data-status')||'all';applySidebarFilters();});});");
        html.append("function render(d){latestData=d||{};if(!d||!d.testSuite){document.getElementById('live-banner').innerText='No runtime data yet';return;}var s=d.testSuite,tc=s.testCases||[];var p=0,f=0,k=0;tc.forEach(function(t){if(t.status==='PASSED')p++;else if(t.status==='FAILED')f++;else if(t.status==='SKIPPED')k++;});var abortedStale=isStaleAborted(d);var effectiveRun=d.running===true&&!abortedStale;var stLbl=abortedStale?'ABORTED':(d.running?'RUNNING':'COMPLETED');var stExtra=abortedStale?' aborted':'';document.getElementById('suite-title').innerHTML=e(s.title||'Merv Live Runtime Report')+' <span id=\"run-state\" class=\"status-pill'+stExtra+'\">'+stLbl+'</span>';if(abortedStale){document.getElementById('live-banner').innerText='Aborted — no report updates for 1 min (build may have stopped). Test cases: '+tc.length;}else if(d.running){document.getElementById('live-banner').innerText='Live · Last update: '+new Date().toLocaleString()+' | Test cases: '+tc.length;}else{document.getElementById('live-banner').innerText='Execution completed. '+((d.exportDate)?('Snapshot: '+d.exportDate+'. '):'')+'Test cases: '+tc.length;}document.getElementById('total-count').innerText=tc.length;document.getElementById('passed-count').innerText=p;document.getElementById('failed-count').innerText=f;document.getElementById('skipped-count').innerText=k;document.getElementById('leg-p').textContent='Passed ('+p+')';document.getElementById('leg-f').textContent='Failed ('+f+')';document.getElementById('leg-k').textContent='Skipped ('+k+')';drawPieChart(p,f,k);drawTrendChart(tc,s,effectiveRun);renderFailureReasons(tc);if(requestedTestcaseName&&!appliedRequestedCase){for(var qi=0;qi<tc.length;qi++){var nm=String((tc[qi]&&tc[qi].testcaseName)||'').trim().toLowerCase();if(nm===requestedTestcaseName){selectedId='tc-'+qi;appliedRequestedCase=true;break;}}}renderSidebar(tc);renderSelectedCase(tc);if(!effectiveRun){stopPolling();}}");
        html.append("async function load(){var ts='?ts='+Date.now();var paths=['../json/merv-report.json'+ts,'./../json/merv-report.json'+ts,'../../json/merv-report.json'+ts,'./merv-report.json'+ts];var lastErr='';for(var i=0;i<paths.length;i++){try{var r=await fetch(paths[i],{cache:'no-store'});if(!r.ok){lastErr='HTTP '+r.status+' for '+paths[i];continue;}var d=await r.json();render(d);return;}catch(err){lastErr=(err&&err.message)?err.message:String(err);}}var lb=document.getElementById('live-banner');if(lb&&lastErr){lb.innerText='Live data load issue: '+lastErr;}}");
        html.append("pollTimer=setInterval(load,5000);load();");
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

            // Finalize HTML report by copying the live view as requested
            String htmlReportPath = htmlFolderPath + "merv-report.html";
            String liveHtmlReportPath = htmlFolderPath + "merv-report-live.html";
            if (new File(liveHtmlReportPath).isFile()) {
                Files.copy(Paths.get(liveHtmlReportPath), Paths.get(htmlReportPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("HTML report finalized from live report: " + htmlReportPath);
            } else {
                System.err.println("Live report not found, skipping merv-report.html copy: " + liveHtmlReportPath);
            }

            // Generate JSON report in json folder
            String jsonReportPath = jsonFolderPath + "merv-report.json";
            generateJsonReport(jsonReportPath);
            System.out.println("JSON report generated: " + jsonReportPath);

            System.out.println("Merv Report generation completed: " + reportFolderPath);
            refreshReportsIndexListing();

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
            writeReportsIndexHtml(base.trim());
        } catch (Exception e) {
            System.err.println("Could not update reports index: " + e.getMessage());
        }
    }

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
            JsonNode root = objectMapper.readTree(jsonFile);
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

    private static void writeReportsIndexHtml(String baseReportPath) throws Exception {
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
        String grad = MERV_REPORT_GRADIENT_CSS;
        String logo = MERV_REPORT_LOGO_URL;
        int reportsDeletePort = readReportsDeleteApiPort();
        String deleteApiUrl = "http://127.0.0.1:" + reportsDeletePort + "/api/reports/delete";

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
        html.append(".nav a{display:block;padding:12px 14px;border-radius:8px;color:#4a4a4a;text-decoration:none;font-size:14px;font-weight:500;margin:2px 0;}\n");
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
        html.append(".hint{font-size:12px;color:#888;margin-top:32px;text-align:center;}\n");
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
        html.append("<a href=\"#\" class=\"nav-sub-link\" data-view=\"consolidated\" data-cons-tab=\"tags\" data-scroll-target=\"cons-subpanel-tags\">2 — Tag/Group Based Summary</a></div>");
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
            html.append("<div id=\"view-consolidated\" class=\"content-view\"><section class=\"consolidated-panel\" aria-label=\"Consolidated\"><h2>Consolidated Report</h2><div class=\"cons-subtabs\" role=\"tablist\" aria-label=\"Consolidated report views\"><button type=\"button\" class=\"cons-subtab active\" id=\"cons-tab-testcase\" data-cons-sub=\"testcase\" role=\"tab\" aria-selected=\"true\">TestCase View</button><button type=\"button\" class=\"cons-subtab\" id=\"cons-tab-tags\" data-cons-sub=\"tags\" role=\"tab\" aria-selected=\"false\">Tag based Report</button></div><div id=\"cons-subpanel-testcase\" class=\"cons-subpanel active\" role=\"tabpanel\" aria-labelledby=\"cons-tab-testcase\"><p class=\"kpi-sub\">No report runs to consolidate.</p></div><div id=\"cons-subpanel-tags\" class=\"cons-subpanel\" role=\"tabpanel\" aria-labelledby=\"cons-tab-tags\"><p class=\"kpi-sub cons-tag-hint\">Test cases grouped by Cucumber tag. A test may appear under more than one tag.</p><div id=\"consolidated-tag-root\" class=\"consolidated-tag-root\"><p class=\"cons-tag-empty\">No data.</p></div></div></section></div>\n");
            html.append("<div id=\"view-kpis\" class=\"content-view\"><section class=\"kpi-panel\" aria-label=\"KPI reports\"><h2 id=\"kpi-sec-execution-summary\">Test execution summary</h2><div class=\"kpi-build-scope-bar\" id=\"kpi-build-scope-bar\" role=\"region\" aria-label=\"KPI build scope\"><span class=\"kpi-build-scope-label\" id=\"kpi-build-scope-label\">Builds in KPI charts</span><select id=\"kpi-build-scope\" class=\"kpi-build-scope-select\" aria-labelledby=\"kpi-build-scope-label\"><option value=\"20\" selected>Last 20 builds</option><option value=\"30\">Last 30 builds</option><option value=\"50\">Last 50 builds</option><option value=\"all\">All builds</option><option value=\"custom\">Custom…</option></select><span id=\"kpi-build-custom-wrap\" class=\"kpi-build-custom-wrap\"><label for=\"kpi-build-custom\" class=\"kpi-build-custom-label\">Last</label><input type=\"number\" id=\"kpi-build-custom\" class=\"kpi-build-custom-input\" min=\"1\" max=\"999\" value=\"20\" /><span class=\"kpi-build-custom-suffix\">builds</span><button type=\"button\" id=\"kpi-build-custom-apply\" class=\"kpi-build-custom-apply\">Apply</button></span><span id=\"kpi-build-scope-status\" class=\"kpi-build-scope-status\"></span></div><p class=\"kpi-sub\">Charts and KPI metrics will populate after local runs finish writing JSON reports.</p><div class=\"kpi-grid\"><div class=\"kpi-card kpi-card-total\"><span class=\"kpi-label\">Total test cases</span><span class=\"kpi-value\" id=\"kpi-total-tc\">0</span></div><div class=\"kpi-card kpi-card-pass\"><span class=\"kpi-label\">Passed</span><span class=\"kpi-value\" id=\"kpi-passed\">0</span></div><div class=\"kpi-card kpi-card-fail\"><span class=\"kpi-label\">Failed</span><span class=\"kpi-value\" id=\"kpi-failed\">0</span></div><div class=\"kpi-card kpi-card-skip\"><span class=\"kpi-label\">Skipped</span><span class=\"kpi-value\" id=\"kpi-skipped\">0</span></div><div class=\"kpi-card kpi-card-pct\"><span class=\"kpi-label\">Pass %</span><span class=\"kpi-value\" id=\"kpi-pass-pct\">—</span></div></div><div class=\"kpi-charts-row\"><div class=\"kpi-chart-cell\"><canvas id=\"kpiDonutChart\" aria-label=\"Outcome distribution\"></canvas></div><div class=\"kpi-chart-cell\"><canvas id=\"kpiStackedBarChart\" aria-label=\"Pass and fail counts by execution\"></canvas></div></div><section class=\"kpi-perf-section\" id=\"kpi-sec-performance\" aria-label=\"Execution time and performance\"><h3 class=\"kpi-perf-heading\">Execution Time &amp; Performance</h3><p class=\"kpi-perf-sub\">From test case start/end times in each report. Suite P95/P99 use one total duration per listed run (folder).</p><div class=\"kpi-perf-grid\"><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Total suite execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-total-suite\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Avg test case execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-avg-tc\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Suite duration (P95 / P99)</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-pct\">—</span></div><div class=\"kpi-card kpi-card-dur kpi-perf-span2\" id=\"kpi-sec-slow\"><div class=\"kpi-perf-slow-head\"><button type=\"button\" class=\"kpi-perf-slow-toggle\" id=\"kpi-perf-slow-toggle\" aria-expanded=\"true\" aria-controls=\"kpi-perf-slow-wrap\" title=\"Collapse or expand slowest list\"><span class=\"kpi-perf-slow-caret\" aria-hidden=\"true\">▼</span></button><span class=\"kpi-label\">Slowest test cases (max duration per name)</span></div><div class=\"kpi-perf-slow-wrap\" id=\"kpi-perf-slow-wrap\"><ol class=\"kpi-perf-slow\" id=\"kpi-perf-slow\"><li class=\"kpi-perf-slow-empty\">No timing data yet.</li></ol></div></div></div><div class=\"kpi-perf-charts\"><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSuiteDurChart\" aria-label=\"Suite execution time by run\"></canvas></div><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSlowChart\" aria-label=\"Slowest test cases\"></canvas></div></div></section><section class=\"kpi-flaky-section\" id=\"kpi-sec-flaky\" aria-label=\"Flaky test detection\"><h3 class=\"kpi-flaky-heading\">Flaky Test Detection</h3><p class=\"kpi-flaky-sub\">Tests failing intermittently: must show both <strong>passed</strong> and <strong>failed</strong> across builds in your KPI scope. <strong>Failed runs</strong> = failure outcomes (retry proxy; retries are not stored in JSON). <strong>Stability</strong> = passes ÷ (passes + fails).</p><div class=\"kpi-flaky-summary\"><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-intermittent-count\">0</span><span class=\"kpi-flaky-summary-label\"> intermittent tests</span><span class=\"kpi-flaky-summary-sep\">·</span><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-fail-outcomes\">0</span><span class=\"kpi-flaky-summary-label\"> failed runs (those tests)</span></div><div class=\"kpi-flaky-wrap\"><table class=\"kpi-flaky-table\" id=\"kpi-flaky-table\"><thead><tr><th>Test case</th><th>Failed runs</th><th>Passed runs</th><th>Stability</th><th>Pass/fail flips</th></tr></thead><tbody id=\"kpi-flaky-body\"><tr><td colspan=\"5\" class=\"kpi-flaky-empty\">Need at least two builds in scope to compare outcomes.</td></tr></tbody></table></div></section><section class=\"kpi-trend-section\" id=\"kpi-sec-trend\" aria-label=\"Trend analysis\"><h3 class=\"kpi-trend-heading\">Trend Analysis (Build-wise)</h3><p class=\"kpi-sub\">Pass % over time, failures per build, and execution time trend (oldest folder left, newest right).</p><div class=\"kpi-trend-grid\"><div class=\"kpi-trend-cell\" id=\"kpi-chart-pass-pct\"><canvas id=\"kpiTrendPassPct\" aria-label=\"Pass percent over time\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-failures\"><canvas id=\"kpiTrendFailures\" aria-label=\"Failures per build\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-duration\"><canvas id=\"kpiTrendDuration\" aria-label=\"Execution time trend\"></canvas></div></div></section></section></div>\n");
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
                html.append("\" data-q=\"").append(escapeHtml(dataQ)).append("\" data-card-idx=\"").append(i).append("\" data-json=\"").append(enc).append("/json/merv-report.json\" data-folder-name=\"").append(escapeHtml(e.folderName)).append("\">\n");
                html.append("<div class=\"suite-top\"><div class=\"suite-title-block\"><h3 class=\"suite-name\">").append(escapeHtml(s.title)).append("</h3>\n");
                html.append("<p class=\"suite-folder\">").append(escapeHtml(e.folderName)).append("</p></div>\n");
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
                html.append("<dl class=\"suite-meta-dl\"><dt>Environment</dt><dd>").append(escapeHtml(s.env)).append("</dd>");
                html.append("<dt>Release</dt><dd>").append(escapeHtml(s.release)).append("</dd>");
                html.append("<dt>Sprint</dt><dd>").append(escapeHtml(s.sprint)).append("</dd></dl>\n");
                html.append("<div class=\"suite-meta-donut\"><div class=\"donut\" style=\"").append(donutConicStyle(s.pass, s.fail, s.skip)).append("\" title=\"Pass / Fail / Skip\"></div></div>\n");
                html.append("</div>\n<div class=\"suite-tags-block\"><div class=\"tag-row\">");
                if (s.tagPills.isEmpty()) {
                    html.append("<span class=\"tag-pill tag-pill-empty\" style=\"opacity:.5\">—</span>");
                } else {
                    for (String tag : s.tagPills) {
                        html.append("<span class=\"tag-pill\">").append(escapeHtml(tag)).append("</span>");
                    }
                }
                html.append("</div></div></div>\n");
                html.append("<div class=\"suite-foot\"><div class=\"suite-when\">");
                html.append(escapeHtml(footFmt.format(new Date(e.lastModified))));
                html.append("<span class=\"rel\">").append(escapeHtml(relativeTimeAgo(e.lastModified))).append("</span></div>\n");
                html.append("<div class=\"suite-actions\">");
                html.append("<a class=\"btn-view\" href=\"").append(viewHref).append("\">View Cases</a>");
                html.append("<a class=\"icon-btn\" title=\"Open in new tab\" href=\"").append(viewHref).append("\" target=\"_blank\" rel=\"noopener\">↗</a>");
                html.append("<button type=\"button\" class=\"btn-delete\" title=\"Delete this report folder\" data-folder=\"").append(escapeHtml(e.folderName)).append("\" onclick=\"deleteReport(this)\">Delete</button>");
                html.append("</div></div></article>\n");
            }
            html.append("</div>\n");
            html.append("</div>\n");
            html.append("<div id=\"view-kpis\" class=\"content-view\"><section class=\"kpi-panel\" aria-label=\"KPI reports\"><h2 id=\"kpi-sec-execution-summary\">Test execution summary</h2><div class=\"kpi-build-scope-bar\" id=\"kpi-build-scope-bar\" role=\"region\" aria-label=\"KPI build scope\"><span class=\"kpi-build-scope-label\" id=\"kpi-build-scope-label\">Builds in KPI charts</span><select id=\"kpi-build-scope\" class=\"kpi-build-scope-select\" aria-labelledby=\"kpi-build-scope-label\"><option value=\"20\" selected>Last 20 builds</option><option value=\"30\">Last 30 builds</option><option value=\"50\">Last 50 builds</option><option value=\"all\">All builds</option><option value=\"custom\">Custom…</option></select><span id=\"kpi-build-custom-wrap\" class=\"kpi-build-custom-wrap\"><label for=\"kpi-build-custom\" class=\"kpi-build-custom-label\">Last</label><input type=\"number\" id=\"kpi-build-custom\" class=\"kpi-build-custom-input\" min=\"1\" max=\"999\" value=\"20\" /><span class=\"kpi-build-custom-suffix\">builds</span><button type=\"button\" id=\"kpi-build-custom-apply\" class=\"kpi-build-custom-apply\">Apply</button></span><span id=\"kpi-build-scope-status\" class=\"kpi-build-scope-status\"></span></div><p class=\"kpi-sub\">Charts and KPI metrics aggregated across all listed runs (each run contributes its test cases to totals).</p><div class=\"kpi-grid\"><div class=\"kpi-card kpi-card-total\"><span class=\"kpi-label\">Total test cases</span><span class=\"kpi-value\" id=\"kpi-total-tc\">0</span></div><div class=\"kpi-card kpi-card-pass\"><span class=\"kpi-label\">Passed</span><span class=\"kpi-value\" id=\"kpi-passed\">0</span></div><div class=\"kpi-card kpi-card-fail\"><span class=\"kpi-label\">Failed</span><span class=\"kpi-value\" id=\"kpi-failed\">0</span></div><div class=\"kpi-card kpi-card-skip\"><span class=\"kpi-label\">Skipped</span><span class=\"kpi-value\" id=\"kpi-skipped\">0</span></div><div class=\"kpi-card kpi-card-pct\"><span class=\"kpi-label\">Pass %</span><span class=\"kpi-value\" id=\"kpi-pass-pct\">—</span></div></div><div class=\"kpi-charts-row\"><div class=\"kpi-chart-cell\"><canvas id=\"kpiDonutChart\" aria-label=\"Outcome distribution\"></canvas></div><div class=\"kpi-chart-cell\"><canvas id=\"kpiStackedBarChart\" aria-label=\"Pass and fail counts by execution\"></canvas></div></div><section class=\"kpi-perf-section\" id=\"kpi-sec-performance\" aria-label=\"Execution time and performance\"><h3 class=\"kpi-perf-heading\">Execution Time &amp; Performance</h3><p class=\"kpi-perf-sub\">From test case start/end times in each report. Suite P95/P99 use one total duration per listed run (folder).</p><div class=\"kpi-perf-grid\"><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Total suite execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-total-suite\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Avg test case execution time</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-avg-tc\">—</span></div><div class=\"kpi-card kpi-card-dur\"><span class=\"kpi-label\">Suite duration (P95 / P99)</span><span class=\"kpi-value kpi-value-sm\" id=\"kpi-perf-pct\">—</span></div><div class=\"kpi-card kpi-card-dur kpi-perf-span2\" id=\"kpi-sec-slow\"><div class=\"kpi-perf-slow-head\"><button type=\"button\" class=\"kpi-perf-slow-toggle\" id=\"kpi-perf-slow-toggle\" aria-expanded=\"true\" aria-controls=\"kpi-perf-slow-wrap\" title=\"Collapse or expand slowest list\"><span class=\"kpi-perf-slow-caret\" aria-hidden=\"true\">▼</span></button><span class=\"kpi-label\">Slowest test cases (max duration per name)</span></div><div class=\"kpi-perf-slow-wrap\" id=\"kpi-perf-slow-wrap\"><ol class=\"kpi-perf-slow\" id=\"kpi-perf-slow\"><li class=\"kpi-perf-slow-empty\">No timing data yet.</li></ol></div></div></div><div class=\"kpi-perf-charts\"><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSuiteDurChart\" aria-label=\"Suite execution time by run\"></canvas></div><div class=\"kpi-perf-chart-cell\"><canvas id=\"kpiPerfSlowChart\" aria-label=\"Slowest test cases\"></canvas></div></div></section><section class=\"kpi-flaky-section\" id=\"kpi-sec-flaky\" aria-label=\"Flaky test detection\"><h3 class=\"kpi-flaky-heading\">Flaky Test Detection</h3><p class=\"kpi-flaky-sub\">Tests failing intermittently: must show both <strong>passed</strong> and <strong>failed</strong> across builds in your KPI scope. <strong>Failed runs</strong> = failure outcomes (retry proxy; retries are not stored in JSON). <strong>Stability</strong> = passes ÷ (passes + fails).</p><div class=\"kpi-flaky-summary\"><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-intermittent-count\">0</span><span class=\"kpi-flaky-summary-label\"> intermittent tests</span><span class=\"kpi-flaky-summary-sep\">·</span><span class=\"kpi-flaky-summary-num\" id=\"kpi-flaky-fail-outcomes\">0</span><span class=\"kpi-flaky-summary-label\"> failed runs (those tests)</span></div><div class=\"kpi-flaky-wrap\"><table class=\"kpi-flaky-table\" id=\"kpi-flaky-table\"><thead><tr><th>Test case</th><th>Failed runs</th><th>Passed runs</th><th>Stability</th><th>Pass/fail flips</th></tr></thead><tbody id=\"kpi-flaky-body\"><tr><td colspan=\"5\" class=\"kpi-flaky-empty\">Need at least two builds in scope to compare outcomes.</td></tr></tbody></table></div></section><section class=\"kpi-trend-section\" id=\"kpi-sec-trend\" aria-label=\"Trend analysis\"><h3 class=\"kpi-trend-heading\">Trend Analysis (Build-wise)</h3><p class=\"kpi-sub\">Pass % over time, failures per build, and execution time trend (oldest folder left, newest right).</p><div class=\"kpi-trend-grid\"><div class=\"kpi-trend-cell\" id=\"kpi-chart-pass-pct\"><canvas id=\"kpiTrendPassPct\" aria-label=\"Pass percent over time\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-failures\"><canvas id=\"kpiTrendFailures\" aria-label=\"Failures per build\"></canvas></div><div class=\"kpi-trend-cell\" id=\"kpi-chart-duration\"><canvas id=\"kpiTrendDuration\" aria-label=\"Execution time trend\"></canvas></div></div></section></section></div>\n");
            html.append("<div id=\"view-consolidated\" class=\"content-view\">\n");
            html.append("<section class=\"consolidated-panel\" aria-label=\"Consolidated testcase report\">\n");
            html.append("<h2>Consolidated Report</h2>\n");
            html.append("<div class=\"cons-subtabs\" role=\"tablist\" aria-label=\"Consolidated report views\"><button type=\"button\" class=\"cons-subtab active\" id=\"cons-tab-testcase\" data-cons-sub=\"testcase\" role=\"tab\" aria-selected=\"true\">TestCase View</button><button type=\"button\" class=\"cons-subtab\" id=\"cons-tab-tags\" data-cons-sub=\"tags\" role=\"tab\" aria-selected=\"false\">Tag based Report</button></div>\n");
            html.append("<div id=\"cons-subpanel-testcase\" class=\"cons-subpanel active\" role=\"tabpanel\" aria-labelledby=\"cons-tab-testcase\"><div class=\"consolidated-search-wrap\"><input type=\"search\" id=\"consolidated-search\" placeholder=\"Search testcase or tag…\" autocomplete=\"off\"></div><div class=\"consolidated-wrap\">\n");
            html.append("<table class=\"consolidated-table\" id=\"consolidated-table\"><thead><tr><th>Testcase Name</th><th>Current Status<BR/> (Last Run)</th><th>Last 5 Run <BR/>Status</th><th>Last Passed <BR/>Time</th><th>Last Failed <BR/>Time</th><th>Total Pass</th><th>Total Fail</th></tr></thead><tbody id=\"consolidated-body\"><tr><td colspan=\"7\">Loading consolidated data…</td></tr></tbody></table>\n");
            html.append("</div></div>\n");
            html.append("<div id=\"cons-subpanel-tags\" class=\"cons-subpanel\" role=\"tabpanel\" aria-labelledby=\"cons-tab-tags\"><p class=\"kpi-sub cons-tag-hint\">Full testcase rows under each tag (same columns as TestCase View). Tests with multiple tags are listed under each tag. Expand a row for per-suite runs.</p><div id=\"consolidated-tag-root\" class=\"consolidated-tag-root\"></div></div>\n");
            html.append("</section>\n");
            html.append("</div>\n");
        }

        html.append("<p class=\"hint\">Tip: serve this folder with a local HTTP server if the browser blocks JSON in live reports. ");
        html.append("<strong>Delete</strong> needs <code>ReportsDeleteServer</code> on port ").append(reportsDeletePort);
        html.append(" (override with <code>merv.reports.delete.port</code> in merv.properties). Run: ");
        html.append("<code>mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=&quot;org.teche.merv.client.utils.ReportsDeleteServer&quot;</code></p>\n");
        html.append("</div></div></div>\n");
        html.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js\" crossorigin=\"anonymous\"></script>\n");
        html.append("<script>\n");
        html.append("function copyPath(btn){var p=btn.getAttribute('data-copy');if(!p)return;(navigator.clipboard&&navigator.clipboard.writeText?navigator.clipboard.writeText(p):Promise.reject()).catch(function(){var t=document.createElement('textarea');t.value=p;document.body.appendChild(t);t.select();try{document.execCommand('copy');}finally{document.body.removeChild(t);}});}\n");
        html.append("function deleteReport(btn){var folder=btn.getAttribute('data-folder');if(!folder)return;if(!confirm('Delete report folder \"'+folder+'\" permanently? This cannot be undone.'))return;var url=window.MERV_REPORTS_DELETE_API||'';if(!url){alert('Delete API is not configured.');return;}btn.disabled=true;fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({folder:folder})}).then(function(r){return r.text().then(function(t){try{return JSON.parse(t);}catch(e){return{ok:false,error:t||'Bad response'};}});}).then(function(j){if(j&&j.ok)location.reload();else{alert((j&&j.error)||'Delete failed');btn.disabled=false;}}).catch(function(){alert('Could not reach the local delete API. From project root run: mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=\"org.teche.merv.client.utils.ReportsDeleteServer\" (port in merv.properties: merv.reports.delete.port, default 9191).');btn.disabled=false;});}\n");
        html.append("var __navKpiTarget='kpi-sec-execution-summary';\n");
        html.append("function showIndexView(v){var suites=document.getElementById('view-test-suites');var cons=document.getElementById('view-consolidated');var kpis=document.getElementById('view-kpis');if(suites)suites.classList.toggle('active',v==='test-suites');if(cons)cons.classList.toggle('active',v==='consolidated');if(kpis)kpis.classList.toggle('active',v==='kpis');document.querySelectorAll('.nav-main-link[data-view]').forEach(function(a){a.classList.toggle('active',(a.getAttribute('data-view')||'')===v);});var kpiT=typeof __navKpiTarget!=='undefined'?__navKpiTarget:'kpi-sec-execution-summary';document.querySelectorAll('.nav-sub-link[data-view]').forEach(function(a){var dv=a.getAttribute('data-view')||'';if(dv!==v){a.classList.remove('active');return;}if(v==='consolidated'){var want=a.getAttribute('data-cons-tab')||'testcase';var tagTab=document.getElementById('cons-tab-tags');var cur=(tagTab&&tagTab.classList.contains('active'))?'tags':'testcase';a.classList.toggle('active',want===cur);}else if(v==='kpis'){var st=a.getAttribute('data-scroll-target')||'';a.classList.toggle('active',st===kpiT);}else{a.classList.remove('active');}});}\n");
        html.append("document.querySelectorAll('.nav a[data-view]').forEach(function(a){a.addEventListener('click',function(ev){ev.preventDefault();var v=a.getAttribute('data-view')||'test-suites';if(a.classList.contains('nav-sub-link')){if(v==='consolidated'&&typeof setConsSubView==='function')setConsSubView(a.getAttribute('data-cons-tab')||'testcase');if(v==='kpis')__navKpiTarget=a.getAttribute('data-scroll-target')||'kpi-sec-execution-summary';}showIndexView(v);if(a.classList.contains('nav-sub-link')){var tid=a.getAttribute('data-scroll-target');if(tid)requestAnimationFrame(function(){var el=document.getElementById(tid);if(el)el.scrollIntoView({behavior:'smooth',block:'start'});});}});});\n");
        html.append("var ss=document.getElementById('suite-search');if(ss){ss.addEventListener('input',function(){var q=(this.value||'').toLowerCase().trim();document.querySelectorAll('.suite-card').forEach(function(c){var d=c.getAttribute('data-q')||'';c.style.display=!q||d.indexOf(q)>=0?'':'none';});});}\n");
        html.append("(function liveDashboard(){if(typeof Chart!=='undefined'){Chart.defaults.font.family='Roboto';}var folders=window.MERV_REPORT_FOLDERS||[];var kpiDonut=null;var kpiBarChart=null;var kpiTrendPass=null;var kpiTrendFail=null;var kpiTrendDur=null;var kpiPerfSuite=null;var kpiPerfSlow=null;var kpiScopeState={mode:'20',customN:20};var lastSnaps=null;function fmtKpiDur(sec){if(sec==null||isNaN(sec)||sec<0)return'0s';var s=Math.floor(sec);var h=Math.floor(s/3600),m=Math.floor((s%3600)/60),r=s%60;if(h>0)return h+'h '+m+'m '+r+'s';if(m>0)return m+'m '+r+'s';return r+'s';}function getKpiSnaps(snaps){if(!snaps||!snaps.length)return[];var m=kpiScopeState.mode,take;if(m==='all')return snaps.slice();if(m==='custom')take=Math.max(1,parseInt(kpiScopeState.customN,10)||1);else if(m==='20')take=20;else if(m==='30')take=30;else if(m==='50')take=50;else take=snaps.length;take=Math.min(take,snaps.length);return snaps.slice(0,take);}function refreshKpiScopeStatus(){var el=document.getElementById('kpi-build-scope-status');if(!el)return;if(!lastSnaps||!lastSnaps.length){el.textContent='';return;}var k=getKpiSnaps(lastSnaps);el.textContent='Showing '+k.length+' of '+lastSnaps.length+' builds (newest first)';}function computeFlakyRows(snaps){var chron=(snaps||[]).slice().reverse(),byName={},di,tc,row,name,st,seen,j;if(!chron.length)return[];for(di=0;di<chron.length;di++){tc=(chron[di]&&chron[di].testSuite&&chron[di].testSuite.testCases)||[];seen={};for(j=0;j<tc.length;j++){row=tc[j];name=String(row.testcaseName||'Unnamed').trim()||'Unnamed testcase';if(seen[name])continue;seen[name]=1;st=String(row.status||'').toUpperCase();if(!byName[name])byName[name]={seq:[]};byName[name].seq.push(st);}}var out=[],nm,seq,passes,fails,denom,stability,flips,i,a,b,ap,bp;for(nm in byName){if(!Object.prototype.hasOwnProperty.call(byName,nm))continue;seq=byName[nm].seq;passes=0;fails=0;for(i=0;i<seq.length;i++){if(seq[i]==='PASSED')passes++;else if(seq[i]==='FAILED')fails++;}denom=passes+fails;stability=denom>0?Math.round(1000*passes/denom)/10:null;flips=0;for(i=1;i<seq.length;i++){a=seq[i-1];b=seq[i];ap=a==='PASSED'||a==='FAILED';bp=b==='PASSED'||b==='FAILED';if(ap&&bp&&a!==b)flips++;}if(passes>0&&fails>0&&seq.length>=2)out.push({name:nm,passes:passes,fails:fails,stability:stability,flips:flips});}out.sort(function(x,y){return (x.stability-y.stability)||(y.fails-x.fails)});return out;}function updateFlakySection(snaps){var body=document.getElementById('kpi-flaky-body'),cI=document.getElementById('kpi-flaky-intermittent-count'),cF=document.getElementById('kpi-flaky-fail-outcomes'),rows,sumF,i,need;if(!body)return;rows=computeFlakyRows(snaps);if(cI)cI.textContent=String(rows.length);sumF=0;for(i=0;i<rows.length;i++)sumF+=rows[i].fails;if(cF)cF.textContent=String(sumF);if(!rows.length){need=snaps&&snaps.length>=2;body.innerHTML='<tr><td colspan=\"5\" class=\"kpi-flaky-empty\">'+(need?'No intermittent tests in this selection.':'Select at least two builds in KPI scope to compare.')+'</td></tr>';return;}body.innerHTML=rows.map(function(r){return'<tr><td class=\"kpi-flaky-name\">'+escHtml(r.name)+'</td><td class=\"kpi-flaky-num\">'+r.fails+'</td><td class=\"kpi-flaky-num\">'+r.passes+'</td><td class=\"kpi-flaky-num\">'+(r.stability!=null?r.stability+'%':'—')+'</td><td class=\"kpi-flaky-num\">'+r.flips+'</td></tr>';}).join('');}function updateTrendCharts(snaps){var ge=function(id){return document.getElementById(id);};var c1=ge('kpiTrendPassPct'),c2=ge('kpiTrendFailures'),c3=ge('kpiTrendDuration');if(!c1||!c2||!c3||typeof Chart==='undefined')return;var arr=(snaps||[]).slice().reverse();var labels=[],pp=[],ff=[],dd=[];arr.forEach(function(d){var T=tally(d);labels.push((d&&d.__mervFolder)?decodeUi(String(d.__mervFolder)):'\\u2014');var fin=T.pass+T.fail+T.skip;pp.push(fin>0?Math.round(1000*T.pass/fin)/10:0);ff.push(T.fail);var tc=(d&&d.testSuite&&d.testSuite.testCases)||[];var sec=0;tc.forEach(function(row){var a=parseTs(row.startTime),b=parseTs(row.endTime);if(a&&b&&b.getTime()>=a.getTime())sec+=(b.getTime()-a.getTime())/1000;});dd.push(sec);});if(!labels.length){if(kpiTrendPass){kpiTrendPass.destroy();kpiTrendPass=null;}if(kpiTrendFail){kpiTrendFail.destroy();kpiTrendFail=null;}if(kpiTrendDur){kpiTrendDur.destroy();kpiTrendDur=null;}return;}if(!kpiTrendPass){kpiTrendPass=new Chart(c1,{type:'line',data:{labels:labels,datasets:[{label:'Pass %',data:pp,borderColor:'#28a745',backgroundColor:'rgba(40,167,69,0.12)',fill:true,tension:0.3,pointRadius:3}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Pass % over time',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:50,minRotation:0}},y:{min:0,max:100,beginAtZero:true}}}});}else{kpiTrendPass.data.labels=labels;kpiTrendPass.data.datasets[0].data=pp;kpiTrendPass.update('none');}if(!kpiTrendFail){kpiTrendFail=new Chart(c2,{type:'bar',data:{labels:labels,datasets:[{label:'Failures',data:ff,backgroundColor:'rgba(220,53,69,0.88)',borderColor:'#b02a37',borderWidth:1}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Failures per build',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:50,minRotation:0}},y:{beginAtZero:true,ticks:{precision:0}}}}});}else{kpiTrendFail.data.labels=labels;kpiTrendFail.data.datasets[0].data=ff;kpiTrendFail.update('none');}if(!kpiTrendDur){kpiTrendDur=new Chart(c3,{type:'line',data:{labels:labels,datasets:[{label:'Duration (s)',data:dd,borderColor:'#17a2b8',backgroundColor:'rgba(23,162,184,0.12)',fill:true,tension:0.35,pointRadius:3}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Execution time trend',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:50,minRotation:0}},y:{beginAtZero:true,ticks:{callback:function(v){return v+' s';}}}}}});}else{kpiTrendDur.data.labels=labels;kpiTrendDur.data.datasets[0].data=dd;kpiTrendDur.update('none');}}function updatePerfCharts(snaps,perfMap){var ge=function(id){return document.getElementById(id);};var cSu=ge('kpiPerfSuiteDurChart'),cSl=ge('kpiPerfSlowChart');if(!cSu||!cSl||typeof Chart==='undefined')return;var rowS=function(r){var a=parseTs(r.startTime),b=parseTs(r.endTime);if(a&&b&&b.getTime()>=a.getTime())return(b.getTime()-a.getTime())/1000;return null;};var arr=(snaps||[]).slice().reverse();var lab=[],dur=[];arr.forEach(function(d){var tc=(d&&d.testSuite&&d.testSuite.testCases)||[],su=0;tc.forEach(function(r){var x=rowS(r);if(x!=null)su+=x;});lab.push((d&&d.__mervFolder)?decodeUi(String(d.__mervFolder)):'\\u2014');dur.push(su);});if(!lab.length){if(kpiPerfSuite){kpiPerfSuite.destroy();kpiPerfSuite=null;}}else if(!kpiPerfSuite){kpiPerfSuite=new Chart(cSu,{type:'bar',data:{labels:lab,datasets:[{label:'Suite duration (s)',data:dur,backgroundColor:'rgba(23,162,184,0.65)',borderColor:'#138496',borderWidth:1}]},options:{responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Suite execution time by run',font:{size:13,weight:'600'}}},scales:{x:{ticks:{maxRotation:45,minRotation:0}},y:{beginAtZero:true,ticks:{callback:function(v){return v+' s';}}}}}});}else{kpiPerfSuite.data.labels=lab;kpiPerfSuite.data.datasets[0].data=dur;kpiPerfSuite.update('none');}var slow=[],nm;perfMap=perfMap||{};for(nm in perfMap){if(Object.prototype.hasOwnProperty.call(perfMap,nm)&&perfMap[nm]>0)slow.push({name:nm,sec:perfMap[nm]});}slow.sort(function(a,b){return b.sec-a.sec;});slow=slow.slice(0,8);var labS=slow.map(function(x){var n=x.name||'';if(n.length>44)n=n.slice(0,42)+'\\u2026';return n;});var datS=slow.map(function(x){return x.sec;});if(!slow.length){if(kpiPerfSlow){kpiPerfSlow.destroy();kpiPerfSlow=null;}}else if(!kpiPerfSlow){kpiPerfSlow=new Chart(cSl,{type:'bar',data:{labels:labS,datasets:[{label:'Duration (s)',data:datS,backgroundColor:'rgba(220,53,69,0.72)',borderColor:'#b02a37',borderWidth:1}]},options:{indexAxis:'y',responsive:true,maintainAspectRatio:false,plugins:{title:{display:true,text:'Slowest test cases (max per name)',font:{size:13,weight:'600'}}},scales:{x:{beginAtZero:true,ticks:{callback:function(v){return v+' s';}}},y:{ticks:{autoSkip:false}}}}});}else{kpiPerfSlow.data.labels=labS;kpiPerfSlow.data.datasets[0].data=datS;kpiPerfSlow.update('none');}}function updateKpiPanel(snaps){var p=0,f=0,k=0,tcTotal=0,durSec=0,tc,row;(snaps||[]).forEach(function(d){tc=(d&&d.testSuite&&d.testSuite.testCases)||[];tc.forEach(function(row){tcTotal++;var st=String(row.status||'').toUpperCase();if(st==='PASSED')p++;else if(st==='FAILED')f++;else if(st==='SKIPPED')k++;var a=parseTs(row.startTime),b=parseTs(row.endTime);if(a&&b&&b.getTime()>=a.getTime())durSec+=(b.getTime()-a.getTime())/1000;});});var fin=p+f+k;var pct=fin>0?(Math.round(1000*p/fin)/10):null;var ge=function(id){return document.getElementById(id);};if(ge('kpi-total-tc'))ge('kpi-total-tc').textContent=String(tcTotal);if(ge('kpi-passed'))ge('kpi-passed').textContent=String(p);if(ge('kpi-failed'))ge('kpi-failed').textContent=String(f);if(ge('kpi-skipped'))ge('kpi-skipped').textContent=String(k);if(ge('kpi-pass-pct'))ge('kpi-pass-pct').textContent=pct!=null?String(pct)+'%':'—';if(ge('kpi-perf-total-suite'))ge('kpi-perf-total-suite').textContent=(durSec>0||tcTotal>0)?fmtKpiDur(durSec):'—';if(ge('kpi-perf-avg-tc'))ge('kpi-perf-avg-tc').textContent=tcTotal>0?fmtKpiDur(durSec/tcTotal):'—';var suiteDurs=[],perfMap={},rowS=function(r){var a=parseTs(r.startTime),b=parseTs(r.endTime);if(a&&b&&b.getTime()>=a.getTime())return(b.getTime()-a.getTime())/1000;return null;};(snaps||[]).forEach(function(d){var tc=(d&&d.testSuite&&d.testSuite.testCases)||[],su=0;tc.forEach(function(r){var x=rowS(r);if(x!=null)su+=x;});if(su>0)suiteDurs.push(su);tc.forEach(function(r){var x=rowS(r),nm=String(r.testcaseName||'Unnamed').trim()||'Unnamed testcase';if(x!=null&&x>0){if(!perfMap[nm]||x>perfMap[nm])perfMap[nm]=x;}});});var p95=null,p99=null;if(suiteDurs.length){var sd=suiteDurs.slice().sort(function(a,b){return a-b;});var pf=function(p){if(sd.length===1)return sd[0];var r=(p/100)*(sd.length-1),lo=Math.floor(r),hi=Math.ceil(r);if(lo===hi)return sd[lo];return sd[lo]+(sd[hi]-sd[lo])*(r-lo);};p95=pf(95);p99=pf(99);}if(ge('kpi-perf-pct'))ge('kpi-perf-pct').textContent=(p95!=null&&p99!=null)?('P95 '+fmtKpiDur(p95)+' · P99 '+fmtKpiDur(p99)):'—';var elS=ge('kpi-perf-slow');if(elS){var top=Object.keys(perfMap).map(function(n){return{name:n,sec:perfMap[n]};}).sort(function(a,b){return b.sec-a.sec;}).slice(0,5);if(!top.length)elS.innerHTML='<li class=\"kpi-perf-slow-empty\">No timing data</li>';else elS.innerHTML=top.map(function(x){return'<li><span class=\"kpi-perf-slow-name\">'+escHtml(x.name)+'</span><span class=\"kpi-perf-slow-dur\">'+escHtml(fmtKpiDur(x.sec))+'</span></li>';}).join('');}updatePerfCharts(snaps,perfMap);var ctx=document.getElementById('kpiDonutChart');if(ctx&&typeof Chart!=='undefined'){var data=[p,f,k];if(!kpiDonut){kpiDonut=new Chart(ctx,{type:'doughnut',data:{labels:['Passed','Failed','Skipped'],datasets:[{data:data,backgroundColor:['#28a745','#dc3545','#ffc107'],borderWidth:2}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{position:'bottom'}}}});}else{kpiDonut.data.datasets[0].data=data;kpiDonut.update('none');}}var bctx=ge('kpiStackedBarChart');if(bctx&&typeof Chart!=='undefined'){var sn2=snaps||[];var nb=Math.min(5,sn2.length),bl=[],bp=[],bf=[],bx;for(bx=0;bx<nb;bx++){var sx=nb-1-bx;var T2=tally(sn2[sx]);bp.push(T2.pass);bf.push(T2.fail);bl.push((sn2[sx]&&sn2[sx].__mervFolder)?decodeUi(String(sn2[sx].__mervFolder)):'—');}if(nb===0){if(kpiBarChart){kpiBarChart.destroy();kpiBarChart=null;}}else if(!kpiBarChart){kpiBarChart=new Chart(bctx,{type:'bar',data:{labels:bl,datasets:[{label:'Passed',data:bp,backgroundColor:'#4CAF50',stack:'pf'},{label:'Failed',data:bf,backgroundColor:'#2196F3',stack:'pf'}]},options:{responsive:true,maintainAspectRatio:false,datasets:{bar:{categoryPercentage:0.65,barPercentage:0.88}},plugins:{legend:{position:'bottom'},title:{display:true,text:'Pass - Fail Test Case Count',padding:{bottom:10},font:{size:14,weight:'600'}}},scales:{x:{stacked:true,grid:{display:false}},y:{stacked:true,beginAtZero:true,ticks:{precision:0}}}}});}else{kpiBarChart.data.labels=bl;kpiBarChart.data.datasets[0].data=bp;kpiBarChart.data.datasets[1].data=bf;kpiBarChart.update('none');}}updateFlakySection(snaps);updateTrendCharts(snaps);}if(!folders.length){lastSnaps=null;updateKpiPanel([]);refreshKpiScopeStatus();return;}var STALE_MS=");
        html.append(LOCAL_RUN_STALE_AFTER_MS);
        html.append(";var MINUTE=60000,DAY=86400000,POLL=5000;var execBuckets={};var chart=null;var liveEl=document.getElementById('chart-live');var titleEl=document.getElementById('chart-title');var noteEl=document.getElementById('chart-note');var consBody=document.getElementById('consolidated-body');var consSearch=document.getElementById('consolidated-search');var customWrap=document.getElementById('chart-custom-wrap');var customFrom=document.getElementById('chart-custom-from');var customTo=document.getElementById('chart-custom-to');var customApply=document.getElementById('chart-custom-apply');var rangeState={key:'1h',customStart:0,customEnd:0};var expandedCases={};var consolidatedRows=[];var consPanel=document.getElementById('view-consolidated');if(consPanel){consPanel.addEventListener('click',function(ev){var tg=ev.target&&ev.target.closest?ev.target.closest('.cons-tag'):null;if(tg){var tv=(tg.getAttribute('data-tag')||'').trim();if(consSearch&&tv){consSearch.value=tv;setConsSubView('testcase');applyConsolidatedSearch();}return;}var t=ev.target&&ev.target.closest?ev.target.closest('.cons-toggle'):null;if(!t)return;var k=t.getAttribute('data-testcase-key')||'';if(!k)return;expandedCases[k]=!expandedCases[k];renderConsolidated(consolidatedRows);});}document.querySelectorAll('.cons-subtab').forEach(function(btn){btn.addEventListener('click',function(){var sub=this.getAttribute('data-cons-sub')||'testcase';setConsSubView(sub);});});if(consSearch){consSearch.addEventListener('input',applyConsolidatedSearch);}function setRangeUi(key){document.querySelectorAll('.chart-range-btn').forEach(function(btn){var k=btn.getAttribute('data-range');var on=(k===key);btn.classList.toggle('active',on);btn.setAttribute('aria-pressed',on?'true':'false');});}(function bindRangeTags(){document.querySelectorAll('.chart-range-btn').forEach(function(btn){btn.addEventListener('click',function(){var v=this.getAttribute('data-range');if(v==='custom'){if(customWrap)customWrap.classList.add('visible');if(customFrom&&!customFrom.value){var n=Date.now();if(customTo)customTo.value=msToLocal(n);if(customFrom)customFrom.value=msToLocal(n-DAY);}setRangeUi('custom');return;}if(customWrap)customWrap.classList.remove('visible');rangeState.key=v;setRangeUi(v);pollAll();});});})();function msToLocal(ms){var d=new Date(ms);function p(n){return n<10?'0'+n:''+n;}return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+'T'+p(d.getHours())+':'+p(d.getMinutes());}function parseTs(v){if(v==null||v===undefined)return null;if(typeof v==='number'){var n=v;return new Date(n>1e11?n:n*1000);}if(typeof v==='string'){var s=new Date(v);return isNaN(s.getTime())?null:s;}if(typeof v==='object'&&v){if(typeof v.time==='number')return new Date(v.time);if(Array.isArray(v)&&v.length>=3)return new Date(v[0],(v[1]||1)-1,v[2]||1,v[3]||0,v[4]||0,v[5]||0);}var t=new Date(v);return isNaN(t.getTime())?null:t;}function fmtTs(ms){if(!ms||ms<=0)return'—';var d=new Date(ms);if(isNaN(d.getTime()))return'—';var mon=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];var dd=(d.getDate()<10?'0':'')+d.getDate();var h=d.getHours(),amp=h>=12?'PM':'AM';h=h%12;if(h===0)h=12;var mm=(d.getMinutes()<10?'0':'')+d.getMinutes();return dd+'-'+mon[d.getMonth()]+'-'+d.getFullYear()+', '+h+':'+mm+amp;}function decodeUi(s){if(s==null)return'';var x=String(s);try{return decodeURIComponent(x);}catch(e){return x;}}function escHtml(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}function statusCls(st){var x=String(st||'').toLowerCase();if(x==='passed'||x==='failed'||x==='skipped'||x==='in_progress')return x;return'in_progress';}function getWindow(now){if(rangeState.key==='custom')return{start:rangeState.customStart,end:Math.min(now,rangeState.customEnd),live:false};var ms=0;if(rangeState.key==='1h')ms=60*MINUTE;else if(rangeState.key==='6h')ms=6*60*MINUTE;else if(rangeState.key==='1d')ms=24*60*MINUTE;else if(rangeState.key==='1w')ms=7*DAY;else if(rangeState.key==='2w')ms=14*DAY;else if(rangeState.key==='1m')ms=30*DAY;else ms=60*MINUTE;return{start:now-ms,end:now,live:true};}function bucketMsForRange(key,rangeLen){if(key==='1h')return MINUTE;if(key==='6h')return 5*MINUTE;if(key==='1d')return 15*MINUTE;if(key==='1w'||key==='2w'||key==='1m')return DAY;if(rangeLen<=6*60*MINUTE)return 5*MINUTE;if(rangeLen<=2*DAY)return 15*MINUTE;return DAY;}function aggregateBuckets(snapshots,win,bucketMs){var out={},si,j,row,st,et,tms,bk,d,tc,start=win.start,end=win.end;for(si=0;si<snapshots.length;si++){d=snapshots[si];if(!d||!d.testSuite)continue;tc=d.testSuite.testCases||[];for(j=0;j<tc.length;j++){row=tc[j];st=String(row.status||'').toUpperCase();if(st!=='PASSED'&&st!=='FAILED')continue;et=parseTs(row.endTime);if(!et)continue;tms=et.getTime();if(tms<start||tms>end+MINUTE)continue;bk=Math.floor(tms/bucketMs)*bucketMs;if(!out[bk])out[bk]={pass:0,fail:0};if(st==='PASSED')out[bk].pass++;else out[bk].fail++;}}return out;}function formatTick(t,bucketMs){if(bucketMs>=DAY)return new Date(t).toLocaleDateString([],{month:'short',day:'numeric'});if(bucketMs>=3600000)return new Date(t).toLocaleString([],{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});return new Date(t).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});}function buildSeries(){var now=Date.now(),win=getWindow(now),rangeLen=Math.max(win.end-win.start,MINUTE),bucketMs=bucketMsForRange(rangeState.key,rangeLen),b0=Math.floor(win.start/bucketMs)*bucketMs,bLast=Math.floor(win.end/bucketMs)*bucketMs,labels=[],passA=[],failA=[],t;for(t=b0;t<=bLast;t+=bucketMs){labels.push(formatTick(t,bucketMs));var b=execBuckets[t]||{pass:0,fail:0};passA.push(b.pass);failA.push(b.fail);}return{labels:labels,passA:passA,failA:failA,bucketMs:bucketMs};}function noteHtml(bucketMs){var txt=bucketMs>=DAY?'calendar day':(bucketMs>=900000?'15-minute':(bucketMs>=300000?'5-minute':'minute'));return '<strong>Pass</strong> (green area) and <strong>fail</strong> (red line) &mdash; test cases <strong>finished per '+txt+'</strong> in the selected range, from <strong>all listed runs</strong>';}function syncChrome(s){if(titleEl){if(rangeState.key==='custom'&&rangeState.customStart>0&&rangeState.customEnd>0){var a=new Date(rangeState.customStart),b=new Date(rangeState.customEnd);titleEl.textContent='Test cases executed — '+a.toLocaleString()+' – '+b.toLocaleString();}else{var T={'1h':'Test cases executed — last 1 hour','6h':'Test cases executed — last 6 hours','1d':'Test cases executed — last 24 hours','1w':'Test cases executed — last 7 days','2w':'Test cases executed — last 14 days','1m':'Test cases executed — last 30 days'};titleEl.textContent=T[rangeState.key]||T['1h'];}}if(noteEl)noteEl.innerHTML=noteHtml(s.bucketMs);}function aggregateConsolidated(snapshots){var map={},si,j,d,tc,row,name,st,et,m,exp;for(si=0;si<snapshots.length;si++){d=snapshots[si];if(!d||!d.testSuite)continue;exp=parseTs(d.exportDate);m=exp?exp.getTime():0;tc=d.testSuite.testCases||[];for(j=0;j<tc.length;j++){row=tc[j]||{};name=String(row.testcaseName||'Unnamed testcase').trim()||'Unnamed testcase';st=String(row.status||'IN_PROGRESS').toUpperCase();et=parseTs(row.endTime)||parseTs(row.startTime);var tms=et?et.getTime():m;var suiteName=String((d.testSuite&&d.testSuite.title)||'Unnamed suite').trim()||'Unnamed suite';var folder=(d&&d.__mervFolder)||'';var htmlFile=(d&&d.running===true)?'merv-report-live.html':'merv-report.html';var testcaseHref=folder?(folder+'/html/'+htmlFile+'?testcase='+encodeURIComponent(name)):'';if(!map[name])map[name]={name:name,key:('tc-'+Object.keys(map).length),currentStatus:'IN_PROGRESS',lastAt:0,lastPassed:0,lastFailed:0,totalPass:0,totalFail:0,last5:[],latestHref:'',suiteRuns:[],tags:[]};if(tms>=map[name].lastAt){map[name].lastAt=tms;map[name].currentStatus=st;map[name].latestHref=testcaseHref;}map[name].last5.push({t:tms,s:st});map[name].suiteRuns.push({suite:suiteName,folder:folder,status:st,href:testcaseHref,t:tms});(row.tags||[]).forEach(function(tg){var tx=String(tg||'').trim();if(tx&&map[name].tags.indexOf(tx)<0)map[name].tags.push(tx);});if(st==='PASSED'){map[name].totalPass++;if(tms>map[name].lastPassed)map[name].lastPassed=tms;}else if(st==='FAILED'){map[name].totalFail++;if(tms>map[name].lastFailed)map[name].lastFailed=tms;}}}return Object.keys(map).map(function(k){var r=map[k];r.last5=(r.last5||[]).sort(function(a,b){return b.t-a.t;}).slice(0,5).map(function(x){var s=String(x.s||'').toUpperCase();if(s==='PASSED')return'P';if(s==='FAILED')return'F';if(s==='SKIPPED')return'S';return'I';}).join(' ');r.suiteRuns=(r.suiteRuns||[]).sort(function(a,b){return b.t-a.t;});if(expandedCases[r.key]===undefined)expandedCases[r.key]=false;return r;}).sort(function(a,b){return a.name.localeCompare(b.name);});}function setConsSubView(sub){var a=document.getElementById('cons-subpanel-testcase'),b=document.getElementById('cons-subpanel-tags'),t1=document.getElementById('cons-tab-testcase'),t2=document.getElementById('cons-tab-tags');if(!a||!b)return;var onTc=sub==='testcase';a.classList.toggle('active',onTc);b.classList.toggle('active',!onTc);if(t1){t1.classList.toggle('active',onTc);t1.setAttribute('aria-selected',onTc?'true':'false');}if(t2){t2.classList.toggle('active',!onTc);t2.setAttribute('aria-selected',(!onTc)?'true':'false');}if(typeof showIndexView==='function')showIndexView('consolidated');}function consRowsHtmlFor(r){var html='';var ex=!!expandedCases[r.key];var cls=statusCls(r.currentStatus);var tags=(r.tags||[]).map(function(tg){return '<button type=\"button\" class=\"cons-tag\" data-tag=\"'+escHtml(tg)+'\">'+escHtml(tg)+'</button>';}).join('');var tagBlock=tags?('<div class=\"cons-tags\">'+tags+'</div>'):'';var searchable=((r.name||'')+' '+(r.tags||[]).join(' ')).toLowerCase();html+='<tr class=\"cons-testcase-row\" data-kind=\"tc\" data-testcase-key=\"'+escHtml(r.key)+'\" data-name=\"'+escHtml(searchable)+'\">';html+='<td class=\"cons-name\"><div class=\"cons-suite-cell\"><button type=\"button\" class=\"cons-toggle '+(ex?'expanded':'')+'\" data-testcase-key=\"'+escHtml(r.key)+'\" title=\"Show suites for testcase\"><span class=\"arr\">▶</span></button>'+(r.latestHref?('<a class=\"cons-link\" href=\"'+escHtml(r.latestHref)+'\">'+escHtml(r.name)+'</a>'):escHtml(r.name))+'</div>'+tagBlock+'</td>';html+='<td><span class=\"cons-status '+cls+'\">'+escHtml(String(r.currentStatus||'IN_PROGRESS').replace(/_/g,' '))+'</span></td><td>'+escHtml(r.last5||'—')+'</td><td>'+escHtml(fmtTs(r.lastPassed))+'</td><td>'+escHtml(fmtTs(r.lastFailed))+'</td><td class=\"cons-num\">'+(r.totalPass||0)+'</td><td class=\"cons-num\">'+(r.totalFail||0)+'</td></tr>';if(ex){(r.suiteRuns||[]).forEach(function(sr){var scls=statusCls(sr.status);var sLabel=decodeUi(sr.suite);var fLabel=decodeUi(sr.folder);if(fLabel){sLabel+=' ('+fLabel+')';}html+='<tr class=\"cons-suite-detail-row\" data-kind=\"suite-detail\" data-parent-key=\"'+escHtml(r.key)+'\" data-name=\"'+escHtml((decodeUi(sr.suite)+' '+decodeUi(sr.folder)+' '+String(r.name||'')).toLowerCase())+'\"><td class=\"cons-suite-detail-name\">'+(sr.href?('<a class=\"cons-link\" href=\"'+escHtml(sr.href)+'\">'+escHtml(sLabel)+'</a>'):escHtml(sLabel))+'</td><td><span class=\"cons-status '+scls+'\">'+escHtml(String(sr.status||'IN_PROGRESS').replace(/_/g,' '))+'</span></td><td>—</td><td>—</td><td>—</td><td class=\"cons-num\">—</td><td class=\"cons-num\">—</td></tr>';});}return html;}function renderTagBasedReport(rows){var root=document.getElementById('consolidated-tag-root');if(!root)return;if(!rows||!rows.length){root.innerHTML='<p class=\"cons-tag-empty\">No testcase data available yet.</p>';return;}var UNTAG='\u2014 No tag \u2014',byTag={},tg,i,r,j;for(i=0;i<rows.length;i++){r=rows[i];var tgs=(r.tags||[]).map(function(x){return String(x||'').trim();}).filter(function(x){return x;});if(!tgs.length){if(!byTag[UNTAG])byTag[UNTAG]=[];byTag[UNTAG].push(r);}else{for(j=0;j<tgs.length;j++){tg=tgs[j];if(!byTag[tg])byTag[tg]=[];byTag[tg].push(r);}}}var keys=Object.keys(byTag).sort(function(a,b){if(a===UNTAG)return 1;if(b===UNTAG)return -1;return a.localeCompare(b);});var out='',k,secRows,m,ki;for(ki=0;ki<keys.length;ki++){k=keys[ki];secRows=byTag[k];out+='<section class=\"cons-tag-section\" aria-labelledby=\"cons-tag-h-'+ki+'\"><h3 id=\"cons-tag-h-'+ki+'\" class=\"cons-tag-heading\">'+escHtml(k)+' <span class=\"cons-tag-count\">('+secRows.length+')</span></h3>';out+='<div class=\"consolidated-wrap\"><table class=\"consolidated-table consolidated-tag-table\"><thead><tr><th>Testcase Name</th><th>Current Status (Last Run)</th><th>Last 5 Run Status</th><th>Last Passed Time</th><th>Last Failed Time</th><th>Total Pass</th><th>Total Fail</th></tr></thead><tbody>';for(m=0;m<secRows.length;m++){out+=consRowsHtmlFor(secRows[m]);}out+='</tbody></table></div></section>';}root.innerHTML=out;}function renderConsolidated(rows){if(!consBody)return;consolidatedRows=rows||[];if(!rows||!rows.length){consBody.innerHTML='<tr><td colspan=\"7\">No testcase data available yet.</td></tr>';renderTagBasedReport(rows);return;}var html='';rows.forEach(function(r){html+=consRowsHtmlFor(r);});consBody.innerHTML=html;applyConsolidatedSearch();renderTagBasedReport(rows);}function applyConsolidatedSearch(){if(!consBody)return;var q=(consSearch&&consSearch.value?consSearch.value:'').toLowerCase().trim();var cases=consBody.querySelectorAll('tr[data-kind=\"tc\"]');cases.forEach(function(cr){var key=cr.getAttribute('data-testcase-key')||'';var nm=cr.getAttribute('data-name')||'';var kids=consBody.querySelectorAll('tr[data-parent-key=\"'+key.replace(/\"/g,'')+'\"]');var caseMatch=!q||nm.indexOf(q)>=0;var hasKidMatch=false;kids.forEach(function(kr){var kn=kr.getAttribute('data-name')||'';if(!q||kn.indexOf(q)>=0)hasKidMatch=true;});var showCase=caseMatch||hasKidMatch||!q;cr.style.display=showCase?'':'none';kids.forEach(function(kr){if(!showCase){kr.style.display='none';return;}if(!expandedCases[key]){kr.style.display='none';return;}if(!q){kr.style.display='';return;}var kn=kr.getAttribute('data-name')||'';kr.style.display=(caseMatch||kn.indexOf(q)>=0)?'':'none';});});}function donutCss(p,f,k){var t=p+f+k;if(t<=0)return'background:#e9ecef;';var pEnd=360*p/t,fEnd=pEnd+360*f/t;return'background:conic-gradient(#28a745 0deg '+pEnd+'deg, #dc3545 '+pEnd+'deg '+fEnd+'deg, #ffc107 '+fEnd+'deg 360deg);';}function tally(d){var p=0,f=0,k=0,tc=(d&&d.testSuite&&d.testSuite.testCases)||[];tc.forEach(function(t){var s=String(t.status||'').toUpperCase();if(s==='PASSED')p++;else if(s==='FAILED')f++;else if(s==='SKIPPED')k++;});return{pass:p,fail:f,skip:k,total:tc.length};}function tagList(d){var seen={},out=[];(d&&d.testSuite&&d.testSuite.testCases||[]).forEach(function(t){(t.tags||[]).forEach(function(tg){var x=String(tg);if(x&&!seen[x]){seen[x]=1;out.push(x);}});});return out;}function lastActMs(d){var n=d&&d.lastActivityMillis;if(typeof n==='number'&&n>0)return n;var p=Date.parse(String((d&&d.exportDate)||''));return isNaN(p)?0:p;}function markAborted(card){var b=card.querySelector('.status-badge');if(!b)return;b.textContent='Aborted';b.className='status-badge abort';}function updateCard(card,d,enc){if(!card)return;var T=tally(d);var elT=card.querySelector('.cnt-total'),elP=card.querySelector('.cnt-pass'),elF=card.querySelector('.cnt-fail'),elK=card.querySelector('.cnt-skip'),seg=card.querySelector('.cnt-skip-seg');if(elT)elT.textContent=T.total;if(elP)elP.textContent=T.pass;if(elF)elF.textContent=T.fail;if(elK)elK.textContent=T.skip;if(seg)seg.style.display=T.skip>0?'inline':'none';var donut=card.querySelector('.suite-meta .donut');if(donut)donut.setAttribute('style',donutCss(T.pass,T.fail,T.skip));var tags=tagList(d),tagRow=card.querySelector('.suite-tags-block .tag-row');if(tagRow){if(!tags.length)tagRow.innerHTML='<span class=\"tag-pill tag-pill-empty\" style=\"opacity:.5\">—</span>';else tagRow.innerHTML=tags.map(function(t){return'<span class=\"tag-pill\">'+escHtml(t)+'</span>';}).join('');}var fn=card.getAttribute('data-folder-name')||'';var suiteTitleEl=card.querySelector('.suite-top .suite-name');var folderEl=card.querySelector('.suite-top .suite-folder');if(folderEl)folderEl.textContent=decodeUi(fn);if(suiteTitleEl&&d&&d.testSuite){var jst=d.testSuite.title;if(jst!=null&&String(jst)!=='')suiteTitleEl.textContent=decodeUi(String(jst));}var titleText=(suiteTitleEl&&suiteTitleEl.textContent)||'';card.setAttribute('data-q',(titleText+' '+fn+' '+tags.join(' ')).toLowerCase());var live=(d&&d.running===true);var htmlFile=live?'merv-report-live.html':'merv-report.html';var pathSlash=fn.split('\\\\').join('/');var copyBtn=card.querySelector('button[data-copy]');if(copyBtn)copyBtn.setAttribute('data-copy',pathSlash+'/html/'+htmlFile);var viewA=card.querySelector('a.btn-view');var extA=card.querySelector('a.icon-btn[target=\"_blank\"]');if(viewA&&enc)viewA.setAttribute('href',enc+'/html/'+htmlFile);if(extA&&enc)extA.setAttribute('href',enc+'/html/'+htmlFile);var badge=card.querySelector('.status-badge');if(badge){if(d&&d.running===false){badge.textContent='Completed';badge.className='status-badge done';}else if(d&&d.running===true){var la=lastActMs(d);if(la>0&&Date.now()-la>STALE_MS){markAborted(card);}else{badge.textContent='In progress';badge.className='status-badge run';}}}}function updateChartUi(){if(typeof Chart==='undefined')return;var s=buildSeries();syncChrome(s);var ctx=document.getElementById('suiteExecChart');if(!ctx)return;if(!chart){chart=new Chart(ctx,{type:'line',data:{labels:s.labels,datasets:[{label:'Pass',data:s.passA,borderColor:'#28a745',backgroundColor:'rgba(40,167,69,0.22)',fill:true,tension:0.28,pointRadius:3,pointBackgroundColor:'#28a745',borderWidth:2},{label:'Fail',data:s.failA,borderColor:'#dc3545',backgroundColor:'transparent',fill:false,tension:0.25,pointRadius:2,pointBackgroundColor:'#dc3545',borderWidth:2}]},options:{responsive:true,maintainAspectRatio:false,animation:{duration:350},interaction:{mode:'index',intersect:false},plugins:{legend:{position:'top',labels:{color:'#333',font:{size:12,weight:'600'}}}},scales:{x:{grid:{color:'#e9ecef'},ticks:{color:'#495057',maxRotation:0,maxTicksLimit:14},border:{color:'#dee2e6'}},y:{beginAtZero:true,grid:{color:'#e9ecef'},ticks:{color:'#495057',precision:0},border:{color:'#dee2e6'}}}}});}else{chart.data.labels=s.labels;chart.data.datasets[0].data=s.passA;chart.data.datasets[1].data=s.failA;chart.update('none');}}async function pollAll(){var snaps=[],i,r,d,card;for(i=0;i<folders.length;i++){try{r=await fetch(folders[i]+'/json/merv-report.json?ts='+Date.now());if(!r.ok)continue;d=await r.json();d.__mervFolder=folders[i];snaps.push(d);card=document.querySelector('.suite-card[data-card-idx=\"'+i+'\"]');updateCard(card,d,folders[i]);}catch(err){}}var now=Date.now(),win=getWindow(now),rangeLen=Math.max(win.end-win.start,MINUTE),bucketMs=bucketMsForRange(rangeState.key,rangeLen);execBuckets=aggregateBuckets(snaps,win,bucketMs);updateChartUi();renderConsolidated(aggregateConsolidated(snaps));lastSnaps=snaps;updateKpiPanel(getKpiSnaps(snaps));refreshKpiScopeStatus();if(liveEl)liveEl.textContent=(rangeState.key==='custom'?'Updated':'Live')+' · '+new Date().toLocaleTimeString();}if(customApply){customApply.addEventListener('click',function(){var fv=customFrom.value,tv=customTo.value;if(!fv||!tv){alert('Please choose both From and To.');return;}var a=new Date(fv),b=new Date(tv);if(isNaN(a.getTime())||isNaN(b.getTime())){alert('Invalid dates.');return;}if(a.getTime()>=b.getTime()){alert('From must be before To.');return;}rangeState.key='custom';rangeState.customStart=a.getTime();rangeState.customEnd=b.getTime();setRangeUi('custom');pollAll();});}(function bindKpiBuildScope(){var sel=document.getElementById('kpi-build-scope');var cust=document.getElementById('kpi-build-custom');var wrap=document.getElementById('kpi-build-custom-wrap');var applyBtn=document.getElementById('kpi-build-custom-apply');function syncCustomWrap(){if(!wrap)return;if(kpiScopeState.mode==='custom'){wrap.classList.add('visible');if(cust)cust.value=String(kpiScopeState.customN);}else wrap.classList.remove('visible');}function refreshKpiFromCache(){if(!lastSnaps)return;updateKpiPanel(getKpiSnaps(lastSnaps));refreshKpiScopeStatus();}if(sel){sel.value=kpiScopeState.mode;syncCustomWrap();sel.addEventListener('change',function(){kpiScopeState.mode=sel.value||'20';if(kpiScopeState.mode==='custom'&&cust)kpiScopeState.customN=Math.max(1,Math.min(999,parseInt(cust.value,10)||20));syncCustomWrap();refreshKpiFromCache();});}if(cust){cust.addEventListener('change',function(){if(sel&&sel.value==='custom'){kpiScopeState.customN=Math.max(1,Math.min(999,parseInt(cust.value,10)||1));refreshKpiFromCache();}});}if(applyBtn){applyBtn.addEventListener('click',function(){if(sel&&sel.value==='custom'&&cust){kpiScopeState.customN=Math.max(1,Math.min(999,parseInt(cust.value,10)||1));refreshKpiFromCache();}});}})();(function(){var b=document.getElementById('kpi-perf-slow-toggle');var w=document.getElementById('kpi-perf-slow-wrap');if(!b||!w)return;b.addEventListener('click',function(){var open=b.getAttribute('aria-expanded')==='true';var next=!open;b.setAttribute('aria-expanded',next?'true':'false');w.classList.toggle('kpi-perf-slow-collapsed',!next);});})();pollAll();setInterval(pollAll,POLL);})();\n");
        html.append("(function autoReloadIndexWhenStale(){var PERIOD=8000;var baseline=null;function fp(html){var n=(html.match(/data-card-idx=/g)||[]).length;return n+'|'+html.length;}async function tick(){try{var u='./index.html?cb='+Date.now();var r=await fetch(u,{cache:'no-store',credentials:'same-origin'});if(!r.ok)return;var t=await r.text();var s=fp(t);if(baseline===null){baseline=s;return;}if(s!==baseline){location.reload();}}catch(e){}}setTimeout(tick,2500);setInterval(tick,PERIOD);})();\n");
        html.append("</script>\n</body></html>\n");

        FileUtils.writeFile(base + "index.html", html.toString());
        System.out.println("Reports index updated: " + base + "index.html");
    }

    /**
     * Prefer timestamp parsed from folder name ({@code dd-MM-yyyy HH-mm-ss Merv-Report}) so the newest run
     * is first as soon as the folder exists, not only after the final report file is written.
     */
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
        html.append(":root { --merv-grad: ").append(MERV_REPORT_GRADIENT_CSS).append("; --sidebar-bg: #f2f3f5; --sidebar-border: #e6e8ec; --nav-text: #4a4a4a; --nav-muted: #6b6b6b; --nav-active-bg: #fdeaea; --nav-active-text: #c20000; }\n");
        html.append("html { scroll-behavior: smooth; }\n");
        html.append("html { font-family: 'Roboto', system-ui, -apple-system, sans-serif; }\n");
        html.append("body { font-family: 'Roboto', system-ui, -apple-system, sans-serif; margin: 0; padding: 0; background-color: #fafafa; color: #333; }\n");
        html.append("button, input, select, textarea { font-family: inherit; }\n");
        html.append("h1, h2, h3, h4, h5, h6 { letter-spacing: 0.5px; }\n");
        html.append(".main-wrapper { display: flex; min-height: 100vh; }\n");
        html.append(".sidebar { width: 300px; background: var(--sidebar-bg); color: var(--nav-text); padding: 20px 16px; overflow-y: auto; position: fixed; height: 100vh; box-shadow: 1px 0 0 var(--sidebar-border); }\n");
        html.append(".sidebar-brand { margin-bottom: 20px; padding-bottom: 18px; border-bottom: 1px solid var(--sidebar-border); text-align: center; }\n");
        html.append(".brand-logo { max-width: 180px; height: auto; display: block; margin: 0 auto; }\n");
        html.append(".sidebar-local-label { margin: 12px 0 0; padding: 0; font-size: 13px; font-weight: 700; color: var(--nav-active-text); letter-spacing: 0.04em; text-align: center; }\n");
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
        html.append(".content-area { margin-left: 300px; flex: 1; padding: 20px; }\n");
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
        html.append("<div class=\"sidebar-brand\"><img class=\"brand-logo\" src=\"").append(MERV_REPORT_LOGO_URL).append("\" alt=\"Merv\"><p class=\"sidebar-local-label\">Merv Local</p></div>\n");
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
                for (LocalTestStep step : testCase.getTestSteps()) {
                    html.append("<div class=\"test-step ").append(step.getStatus().toLowerCase()).append("\">\n");
                    html.append("<p><strong>").append(escapeHtml(step.getTeststepName())).append("</strong> - ").append(step.getStatus()).append("</p>\n");
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

                    // Display screenshots if any
                    if (step.getScreenshots() != null && !step.getScreenshots().isEmpty()) {
                        html.append("<div class=\"screenshots\">\n");
                        html.append("<p><strong>Screenshots:</strong></p>\n");
                        for (String screenshot : step.getScreenshots()) {
                            // Calculate relative path from html folder to report root
                            String relativePath = ".." + File.separator + screenshot;
                            html.append("<div class=\"screenshot\">\n");
                            html.append("<img src=\"").append(relativePath.replace("\\", "/")).append("\" alt=\"Screenshot\" style=\"max-width: 800px; margin: 10px 0; border: 1px solid #ddd; border-radius: 4px; cursor: pointer;\" onclick=\"window.open(this.src, '_blank')\">\n");
                            html.append("<p style=\"font-size: 0.85em; color: #666;\">").append(escapeHtml(screenshot)).append("</p>\n");
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
    }

    /**
     * Escape HTML special characters
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
