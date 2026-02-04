package org.teche.merv.client.example;

import org.teche.merv.client.MervClient;
import org.teche.merv.client.dto.*;
import org.teche.merv.client.exception.MervClientException;
import org.teche.merv.client.utils.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Example class demonstrating how to use the MERV Client API
 */
public class MervClientExample {
    
    public static void main(String[] args) {
        // Option 1: Initialize the client with credentials for automatic token management
        String baseUrl = "http://localhost:7777/api/v1";
        String username = "admin";
        String password = "password";
        
        // Option 2: Initialize the client with API key (alternative to username/password)
        // String apiKey = "merv_your_api_key_here";
        // MervClient client = new MervClient(baseUrl, apiKey, true);
        
        // Option 3: Initialize from configuration file (supports both username/password and API key)
        // MervClient client = MervClient.fromConfig();
        
        try (MervClient client = new MervClient(baseUrl, username, password)) {
            
            // Example 1: Create a test suite
            TestSuiteRequest testSuiteRequest = TestSuiteBuilder.create()
                .hierarchyId(UUID.fromString("your-hierarchy-id"))
                .title("Regression Test Suite")
                .environment("QA")
                .releaseName("v2.1.0")
                .sprint("Sprint 15")
                .addTag("regression")
                .addTag("smoke")
                .build();
            
            TestSuiteResponse testSuite = client.createTestSuite(testSuiteRequest);
            System.out.println("Created test suite: " + testSuite.getId());
            
            // Example 2: Create a test case
            TestCaseRequest testCaseRequest = TestCaseBuilder.create()
                .testcaseName("Login Functionality Test")
                .description("Verify user can login with valid credentials")
                .testSuiteId(testSuite.getId())
                .addTag("login")
                .addTag("authentication")
                .addExecutionMachine("Windows-10")
                .addExecutionMachine("MacOS")
                .status(TestCaseStatus.INPROGRESS)
                .addTestManagementId("TC-001")
                .debug(false)
                .build();
            
            TestCaseResponse testCase = client.createTestCase(testCaseRequest);
            System.out.println("Created test case: " + testCase.getId());
            
            // Example 3: Create test steps
            TestStepRequest step1Request = TestStepBuilder.create()
                .teststepName("Navigate to login page")
                .testcaseId(testCase.getId())
                .expected("Login page should be displayed")
                .actual("Login page displayed successfully")
                .prereq("Browser should be open")
                .testdata("URL: https://example.com/login")
                .status("PASSED")
                .build();
            
            TestStepResponse step1 = client.createTestStep(step1Request);
            System.out.println("Created test step: " + step1.getId());
            
            TestStepRequest step2Request = TestStepBuilder.create()
                .teststepName("Enter valid credentials")
                .testcaseId(testCase.getId())
                .expected("Credentials should be entered")
                .actual("Username and password entered")
                .prereq("Login page should be displayed")
                .testdata("Username: testuser, Password: testpass")
                .status("PASSED")
                .build();
            
            TestStepResponse step2 = client.createTestStep(step2Request);
            System.out.println("Created test step: " + step2.getId());
            
            // Example 4: Update test case status
            TestCaseResponse updatedTestCase = client.updateTestCaseStatus(testCase.getId(), TestCaseStatus.PASSED);
            System.out.println("Updated test case status to: " + updatedTestCase.getStatus());
            
            // Example 5: Retrieve data
            List<TestSuiteResponse> allTestSuites = client.getAllTestSuites();
            System.out.println("Total test suites: " + allTestSuites.size());
            
            List<TestCaseResponse> testCasesInSuite = client.getTestCasesByTestSuite(testSuite.getId());
            System.out.println("Test cases in suite: " + testCasesInSuite.size());
            
            List<TestStepResponse> testStepsInCase = client.getTestStepsByTestCase(testCase.getId());
            System.out.println("Test steps in case: " + testStepsInCase.size());
            
        } catch (MervClientException e) {
            System.err.println("Error using MERV client: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Example method for automation framework integration
     */
    public static void automationFrameworkExample(String username, String password, UUID hierarchyId) {
        try (MervClient client = new MervClient("http://localhost:7777/api/v1", username, password)) {
            
            // Create test suite for automation run
            TestSuiteResponse testSuite = client.createTestSuite(
                TestSuiteBuilder.create()
                    .hierarchyId(hierarchyId)
                    .title("Automated Test Run - " + System.currentTimeMillis())
                    .environment("AUTOMATION")
                    .releaseName("Auto-Release")
                    .addTag("automated")
                    .addTag("selenium")
                    .build()
            );
            
            // Create test case for each test scenario
            String[] testScenarios = {"Login Test", "Search Test", "Logout Test"};
            
            for (String scenario : testScenarios) {
                TestCaseResponse testCase = client.createTestCase(
                    TestCaseBuilder.create()
                        .testcaseName(scenario)
                        .testSuiteId(testSuite.getId())
                        .addTag("automated")
                        .status(TestCaseStatus.INPROGRESS)
                        .build()
                );
                
                // Update status when test starts
                client.updateTestCaseStatus(testCase.getId(), TestCaseStatus.INPROGRESS);
                
                // Simulate test execution
                try {
                    // Your automation test code here
                    Thread.sleep(1000); // Simulate test execution
                    
                    // Create test steps for the test case
                    client.createTestStep(
                        TestStepBuilder.create()
                            .teststepName("Execute " + scenario)
                            .testcaseId(testCase.getId())
                            .expected("Test should pass")
                            .actual("Test passed successfully")
                            .status("PASSED")
                            .build()
                    );
                    
                    // Update status when test completes
                    client.updateTestCaseStatus(testCase.getId(), TestCaseStatus.PASSED);
                    
                } catch (Exception e) {
                    // Handle test failure
                    client.createTestStep(
                        TestStepBuilder.create()
                            .teststepName("Execute " + scenario)
                            .testcaseId(testCase.getId())
                            .expected("Test should pass")
                            .actual("Test failed: " + e.getMessage())
                            .status("FAILED")
                            .build()
                    );
                    
                    client.updateTestCaseStatus(testCase.getId(), TestCaseStatus.FAILED);
                }
            }
            
        } catch (MervClientException e) {
            System.err.println("Error in automation framework: " + e.getMessage());
        }
    }
}
