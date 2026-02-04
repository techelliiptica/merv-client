package org.teche.merv.client.plugin;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.*;
import org.teche.merv.client.MervClient;
import org.teche.merv.client.config.MervConfig;
import org.teche.merv.client.dto.*;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
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
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

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
                        localTestCase.setFailureReason(testcase.getResult().getError().getMessage());
                    }
                }else if(testcase.getResult().getStatus() == Status.SKIPPED){
                    localTestCase.setStatus("SKIPPED");
                }else{
                    localTestCase.setStatus("PASSED");
                }
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
                    }
                    threadLocalCurrentStepIsSkipped.remove();
                    return;
                }
                
                // Normal step finish (not skipped)
                PickleStepTestStep step = (PickleStepTestStep)teststep.getTestStep();
                System.out.println(teststep.getResult().getStatus());
                
                if(teststep.getResult().getStatus() == Status.FAILED) {
                    localStep.setStatus("FAILED");
                    if (teststep.getResult().getError() != null) {
                        localStep.setErrorMessage(teststep.getResult().getError().getMessage());
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
                
                // Clear all flags for next step
                threadLocalCurrentStepIsSkipped.remove();
                threadLocalSkipNextStep.remove();
                threadLocalSkipStepViewInReport.remove();
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
            UUID stepId = threadLocalActiveStepId.get();
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
            
            // Generate HTML report in html folder
            String htmlReportPath = htmlFolderPath + "merv-report.html";
            generateHtmlReport(htmlReportPath, reportFolderPath);
            System.out.println("HTML report generated: " + htmlReportPath);
            
            // Generate JSON report in json folder
            String jsonReportPath = jsonFolderPath + "merv-report.json";
            generateJsonReport(jsonReportPath);
            System.out.println("JSON report generated: " + jsonReportPath);
            
            System.out.println("Merv Report generation completed: " + reportFolderPath);
            
        } catch (Exception e) {
            System.err.println("Error generating local reports: " + e.getMessage());
            e.printStackTrace();
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
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 0; padding: 0; background-color: #f5f5f5; color: #333; }\n");
        html.append(".main-wrapper { display: flex; min-height: 100vh; }\n");
        html.append(".sidebar { width: 300px; background-color: #2c3e50; color: white; padding: 20px; overflow-y: auto; position: fixed; height: 100vh; box-shadow: 2px 0 5px rgba(0,0,0,0.1); }\n");
        html.append(".sidebar-brand { color: white; font-size: 28px; font-weight: bold; margin-bottom: 20px; padding-bottom: 15px; border-bottom: 2px solid #34495e; text-align: center; }\n");
        html.append(".sidebar-search { margin-bottom: 20px; }\n");
        html.append(".sidebar-search input { width: 100%; padding: 10px; border: none; border-radius: 5px; background-color: #34495e; color: white; font-size: 14px; box-sizing: border-box; }\n");
        html.append(".sidebar-search input::placeholder { color: #95a5a6; }\n");
        html.append(".sidebar-search input:focus { outline: none; background-color: #3d566e; }\n");
        html.append(".sidebar-filters { margin-bottom: 20px; display: flex; gap: 5px; flex-wrap: wrap; }\n");
        html.append(".filter-btn { flex: 1; min-width: 60px; padding: 8px 12px; border: none; border-radius: 5px; background-color: #34495e; color: white; font-size: 12px; cursor: pointer; transition: background-color 0.3s; }\n");
        html.append(".filter-btn:hover { background-color: #3d566e; }\n");
        html.append(".filter-btn.active { background-color: #3498db; font-weight: bold; }\n");
        html.append(".sidebar h2 { color: white; margin-top: 0; margin-bottom: 15px; font-size: 18px; }\n");
        html.append(".sidebar-item { padding: 12px 15px; margin: 5px 0; background-color: #34495e; border-radius: 5px; cursor: pointer; transition: background-color 0.3s; border-left: 4px solid transparent; }\n");
        html.append(".sidebar-item:hover { background-color: #3d566e; }\n");
        html.append(".sidebar-item.active { background-color: #3498db; border-left-color: #2980b9; }\n");
        html.append(".sidebar-item.passed { border-left-color: #4CAF50; }\n");
        html.append(".sidebar-item.failed { border-left-color: #f44336; }\n");
        html.append(".sidebar-item.skipped { border-left-color: #FF9800; }\n");
        html.append(".sidebar-item-name { font-weight: bold; color: white; margin-bottom: 5px; }\n");
        html.append(".sidebar-item-status { font-size: 0.85em; color: #ecf0f1; text-transform: uppercase; }\n");
        html.append(".content-area { margin-left: 300px; flex: 1; padding: 20px; }\n");
        html.append(".container { max-width: 1200px; margin: 0 auto; background-color: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); color: #333; }\n");
        html.append("h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }\n");
        html.append("h2 { color: #555; margin-top: 30px; }\n");
        html.append("h3 { color: #333; margin: 0 0 10px 0; }\n");
        html.append("h4 { color: #555; margin: 15px 0 10px 0; }\n");
        html.append("p { color: #333; margin: 5px 0; }\n");
        html.append(".summary { display: flex; gap: 20px; margin: 20px 0; }\n");
        html.append(".summary-card { flex: 1; padding: 15px; border-radius: 5px; text-align: center; }\n");
        html.append(".summary-card h3 { color: white; margin: 0 0 10px 0; font-size: 16px; font-weight: bold; }\n");
        html.append(".summary-card p { color: white; font-weight: bold; }\n");
        html.append(".total { background-color: #2196F3; color: white; }\n");
        html.append(".passed { background-color: #4CAF50; color: white; }\n");
        html.append(".failed { background-color: #f44336; color: white; }\n");
        html.append(".skipped { background-color: #FF9800; color: white; }\n");
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
        html.append(".metadata { color: #666; font-size: 0.9em; margin-top: 10px; }\n");
        html.append(".metadata p { color: #666; }\n");
        html.append(".metadata strong { color: #333; }\n");
        html.append(".screenshots { margin-top: 10px; }\n");
        html.append(".screenshots p { color: #333; font-weight: bold; }\n");
        html.append(".screenshot { margin: 10px 0; }\n");
        html.append(".screenshot img { display: block; }\n");
        html.append(".screenshot p { color: #666; font-size: 0.85em; margin-top: 5px; }\n");
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
        html.append(".stats-section { margin: 20px 0; padding: 20px; background-color: #f9f9f9; border-radius: 8px; display: flex; gap: 30px; align-items: flex-start; }\n");
        html.append(".stats-left { flex: 1; }\n");
        html.append(".stats-right { flex: 1; }\n");
        html.append(".stats-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 15px; }\n");
        html.append(".stat-card { background-color: white; padding: 20px; border-radius: 5px; text-align: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append(".stat-card h3 { margin: 0 0 10px 0; color: #666; font-size: 14px; font-weight: normal; }\n");
        html.append(".stat-card .stat-value { font-size: 36px; font-weight: bold; color: #333; }\n");
        html.append(".stat-card.total .stat-value { color: #2196F3; }\n");
        html.append(".stat-card.passed .stat-value { color: #4CAF50; }\n");
        html.append(".stat-card.failed .stat-value { color: #f44336; }\n");
        html.append(".stat-card.skipped .stat-value { color: #FF9800; }\n");
        html.append(".pie-chart-container { text-align: center; background-color: white; padding: 20px; border-radius: 5px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append(".pie-chart { display: inline-block; }\n");
        html.append(".pie-legend { display: flex; justify-content: center; gap: 30px; margin-top: 20px; flex-wrap: wrap; }\n");
        html.append(".pie-legend-item { display: flex; align-items: center; gap: 8px; }\n");
        html.append(".pie-legend-color { width: 20px; height: 20px; border-radius: 3px; }\n");
        html.append(".pie-legend-label { font-size: 14px; color: #333; }\n");
        html.append(".test-case-summary { cursor: pointer; padding: 15px; margin: 10px 0; background-color: #f9f9f9; border-radius: 5px; border-left: 4px solid #ddd; transition: all 0.3s; }\n");
        html.append(".test-case-summary:hover { background-color: #f0f0f0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append(".test-case-summary.passed { border-left-color: #4CAF50; }\n");
        html.append(".test-case-summary.failed { border-left-color: #f44336; }\n");
        html.append(".test-case-summary.skipped { border-left-color: #FF9800; }\n");
        html.append("</style>\n");
        html.append("<script>\n");
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
        html.append("}\n");
        html.append("var currentFilter = 'all';\n");
        html.append("function searchTestCases() {\n");
        html.append("    var searchTerm = document.getElementById('testcase-search').value.toLowerCase();\n");
        html.append("    applyFilters(searchTerm, currentFilter);\n");
        html.append("}\n");
        html.append("function filterByStatus(status) {\n");
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
        html.append("        if (matchesSearch && matchesFilter) {\n");
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
        html.append("<div class=\"sidebar-brand\">Merv</div>\n");
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
        
        // Build sidebar with test cases
        int testCaseIndex = 0;
        for (LocalTestCase testCase : localTestSuite.getTestCases()) {
            String testCaseId = "tc-" + testCaseIndex;
            html.append("<div class=\"sidebar-item ").append(testCase.getStatus().toLowerCase());
            if (testCaseIndex == 0) {
                html.append(" active"); // First test case is active by default
            }
            html.append("\" id=\"sidebar-").append(testCaseId).append("\" onclick=\"showTestCase('").append(testCaseId).append("')\">\n");
            html.append("<div class=\"sidebar-item-name\">").append(escapeHtml(testCase.getTestcaseName())).append("</div>\n");
            html.append("<div class=\"sidebar-item-status\">").append(testCase.getStatus()).append("</div>\n");
            html.append("</div>\n");
            testCaseIndex++;
        }
        html.append("</div>\n"); // Close sidebar
        
        // Content area
        html.append("<div class=\"content-area\">\n");
        html.append("<div class=\"container\">\n");
        
        // Suite Name
        html.append("<h1>").append(escapeHtml(localTestSuite.getTitle())).append("</h1>\n");
        
        // Calculate statistics
        int total = localTestSuite.getTestCases().size();
        long passed = localTestSuite.getTestCases().stream().filter(tc -> "PASSED".equals(tc.getStatus())).count();
        long failed = localTestSuite.getTestCases().stream().filter(tc -> "FAILED".equals(tc.getStatus())).count();
        long skipped = localTestSuite.getTestCases().stream().filter(tc -> "SKIPPED".equals(tc.getStatus())).count();
        
        // Stats Section
        html.append("<div class=\"stats-section\">\n");
        
        // Left side - 4 stat boxes in 2x2 grid
        html.append("<div class=\"stats-left\">\n");
        html.append("<div class=\"stats-grid\">\n");
        html.append("<div class=\"stat-card total\"><h3>Total Test Cases</h3><div class=\"stat-value\">").append(total).append("</div></div>\n");
        html.append("<div class=\"stat-card passed\"><h3>Passed</h3><div class=\"stat-value\">").append(passed).append("</div></div>\n");
        html.append("<div class=\"stat-card failed\"><h3>Failed</h3><div class=\"stat-value\">").append(failed).append("</div></div>\n");
        html.append("<div class=\"stat-card skipped\"><h3>Skipped</h3><div class=\"stat-value\">").append(skipped).append("</div></div>\n");
        html.append("</div>\n");
        html.append("</div>\n"); // Close stats-left
        
        // Right side - Pie Chart
        html.append("<div class=\"stats-right\">\n");
        html.append("<div class=\"pie-chart-container\">\n");
        html.append("<canvas id=\"pieChart\" width=\"300\" height=\"300\"></canvas>\n");
        html.append("<div class=\"pie-legend\">\n");
        html.append("<div class=\"pie-legend-item\"><div class=\"pie-legend-color\" style=\"background-color: #4CAF50;\"></div><span class=\"pie-legend-label\">Passed (").append(passed).append(")</span></div>\n");
        html.append("<div class=\"pie-legend-item\"><div class=\"pie-legend-color\" style=\"background-color: #f44336;\"></div><span class=\"pie-legend-label\">Failed (").append(failed).append(")</span></div>\n");
        html.append("<div class=\"pie-legend-item\"><div class=\"pie-legend-color\" style=\"background-color: #FF9800;\"></div><span class=\"pie-legend-label\">Skipped (").append(skipped).append(")</span></div>\n");
        html.append("</div>\n");
        html.append("</div>\n"); // Close pie-chart-container
        html.append("</div>\n"); // Close stats-right
        
        html.append("</div>\n"); // Close stats-section
        
        // Metadata
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        html.append("<div class=\"metadata\">\n");
        html.append("<p><strong>Start Time:</strong> ").append(dateFormat.format(localTestSuite.getStartTime())).append("</p>\n");
        html.append("<p><strong>End Time:</strong> ").append(dateFormat.format(localTestSuite.getEndTime())).append("</p>\n");
        long duration = localTestSuite.getEndTime().getTime() - localTestSuite.getStartTime().getTime();
        html.append("<p><strong>Duration:</strong> ").append(formatDuration(duration)).append("</p>\n");
        html.append("</div>\n");
        
        // Test Cases Content
        testCaseIndex = 0;
        for (LocalTestCase testCase : localTestSuite.getTestCases()) {
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
            
            html.append("</div>\n"); // Close test-case
            html.append("</div>\n"); // Close test-case-content
            testCaseIndex++;
        }
        
        html.append("</div>\n"); // Close container
        html.append("</div>\n"); // Close content-area
        html.append("</div>\n"); // Close main-wrapper
        html.append("<script>\n");
        html.append("// Draw pie chart on page load\n");
        html.append("window.onload = function() {\n");
        html.append("    drawPieChart(").append(passed).append(", ").append(failed).append(", ").append(skipped).append(");\n");
        html.append("};\n");
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
        
        String json = objectMapper.writeValueAsString(jsonReport);
        FileUtils.writeFile(filePath, json);
    }

    /**
     * Escape HTML special characters
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * Format duration in milliseconds to readable string
     */
    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%d hours, %d minutes, %d seconds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d minutes, %d seconds", minutes, seconds % 60);
        } else {
            return String.format("%d seconds", seconds);
        }
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
