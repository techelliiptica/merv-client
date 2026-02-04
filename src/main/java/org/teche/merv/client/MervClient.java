package org.teche.merv.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teche.merv.client.auth.AuthManager;
import org.teche.merv.client.dto.*;
import org.teche.merv.client.exception.MervClientException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Main client class for interacting with the MERV Test Management System API
 */
public class MervClient implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(MervClient.class);
    
    private final String baseUrl;
    private final AuthManager authManager;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String legacyAccessToken; // For legacy constructor support
    private String apiKey; // For API key authentication
    
    /**
     * Constructor for MervClient with credentials for automatic token management
     * 
     * @param baseUrl The base URL of the MERV API (e.g., "http://localhost:7777/api/v1")
     * @param username The username for authentication
     * @param password The password for authentication
     */
    public MervClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authManager = new AuthManager(baseUrl, username, password);
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    /**
     * Constructor for MervClient with API key authentication
     * 
     * @param baseUrl The base URL of the MERV API (e.g., "http://localhost:7777/api/v1")
     * @param apiKey The API key for authentication
     */
    public MervClient(String baseUrl, String apiKey, boolean useApiKey) {
        if (!useApiKey) {
            throw new IllegalArgumentException("useApiKey must be true when using API key constructor");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.authManager = null; // No auth manager for API key
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    /**
     * Constructor for MervClient with existing token (legacy support)
     * 
     * @param baseUrl The base URL of the MERV API (e.g., "http://localhost:7777/api/v1")
     * @param accessToken The JWT access token for authentication
     * @deprecated Use MervClient(baseUrl, username, password) for automatic token management
     */
    @Deprecated
    public MervClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authManager = null; // No auth manager for legacy constructor
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Store the token directly (legacy behavior)
        this.legacyAccessToken = accessToken;
    }
    
    /**
     * Get the current access token, using AuthManager if available
     * 
     * @return The access token
     * @throws MervClientException if authentication fails
     */
    private String getAccessToken() throws MervClientException {
        if (authManager != null) {
            return authManager.getValidAccessToken();
        } else {
            return legacyAccessToken;
        }
    }
    
    /**
     * Get the API key if using API key authentication
     * 
     * @return The API key, or null if not using API key
     */
    private String getApiKey() {
        return apiKey;
    }
    
    /**
     * Check if using API key authentication
     * 
     * @return true if using API key, false otherwise
     */
    private boolean isUsingApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }
    
    /**
     * Set authentication header on HTTP request
     * Uses API key if available, otherwise uses JWT token
     * 
     * @param request The HTTP request to set header on
     * @throws MervClientException if authentication fails
     */
    private void setAuthHeader(org.apache.hc.core5.http.HttpRequest request) throws MervClientException {
        if (isUsingApiKey()) {
            // Use API key authentication
            request.setHeader("X-API-Key", getApiKey());
        } else {
            // Use JWT token authentication
            request.setHeader("Authorization", "Bearer " + getAccessToken());
        }
    }
    
    /**
     * Create a new test suite
     * 
     * @param request The test suite request
     * @return The created test suite response
     * @throws MervClientException if the request fails
     */
    public TestSuiteResponse createTestSuite(TestSuiteRequest request) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(request);
            HttpPost httpPost = new HttpPost(baseUrl + "/testsuites");
            httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPost);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 201) {
                    return objectMapper.readValue(responseBody, TestSuiteResponse.class);
                } else {
                    throw new MervClientException("Failed to create test suite: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error creating test suite", e);
        }
    }
    
    /**
     * Get all test suites
     * 
     * @return List of test suites
     * @throws MervClientException if the request fails
     */
    public List<TestSuiteResponse> getAllTestSuites() throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/testsuites");
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TestSuiteResponse.class));
                } else {
                    throw new MervClientException("Failed to get test suites: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test suites", e);
        }
    }
    
    /**
     * Get a test suite by ID
     * 
     * @param id The test suite ID
     * @return The test suite response
     * @throws MervClientException if the request fails
     */
    public TestSuiteResponse getTestSuiteById(UUID id) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/testsuites/" + id);
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestSuiteResponse.class);
                } else {
                    throw new MervClientException("Failed to get test suite: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test suite", e);
        }
    }
    
    /**
     * Update test suite status (PATCH)
     * 
     * @param id The test suite ID
     * @param patchRequest The test suite status patch request
     * @return The updated test suite response
     * @throws MervClientException if the request fails
     */
    public TestSuiteResponse patchTestSuiteStatus(UUID id, TestSuiteStatusPatchRequest patchRequest) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(patchRequest);
            HttpPatch httpPatch = new HttpPatch(baseUrl + "/testsuites/" + id + "/status");
            httpPatch.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPatch);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPatch)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestSuiteResponse.class);
                } else {
                    throw new MervClientException("Failed to patch test suite status: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error patching test suite status", e);
        }
    }
    
    /**
     * Partially update a test suite (PATCH)
     * Only provided fields will be updated
     * 
     * @param id The test suite ID
     * @param patchRequest The test suite patch request with only fields to update
     * @return The updated test suite response
     * @throws MervClientException if the request fails
     */
    public TestSuiteResponse patchTestSuite(UUID id, TestSuitePatchRequest patchRequest) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(patchRequest);
            HttpPatch httpPatch = new HttpPatch(baseUrl + "/testsuites/" + id);
            httpPatch.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPatch);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPatch)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestSuiteResponse.class);
                } else {
                    throw new MervClientException("Failed to patch test suite: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error patching test suite", e);
        }
    }
    
    /**
     * Get test suite ID by alias
     * Since aliases are unique, this returns a single test suite ID
     * 
     * @param alias The test suite alias
     * @return The test suite UUID
     * @throws MervClientException if the request fails or alias not found
     */
    public UUID getTestSuiteIdByAlias(String alias) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/testsuites/alias/" + alias);
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    // Parse the response to get testSuiteId
                    com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(responseBody);
                    String uuidStr = jsonNode.get("test_suite_id").asText();
                    return UUID.fromString(uuidStr);
                } else {
                    throw new MervClientException("Failed to get test suite by alias: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test suite by alias", e);
        }
    }
    
    /**
     * Get test suite ID from alias configured in merv.properties file
     * Reads merv.suite_alias from merv.properties and returns the corresponding test suite ID
     * 
     * @return The test suite UUID from configured alias
     * @throws MervClientException if the request fails, alias not found, or merv.properties not configured
     */
    public UUID getTestSuiteIdFromConfig() throws MervClientException {
        try {
            org.teche.merv.client.config.MervConfig.loadConfiguration();
            String alias = org.teche.merv.client.config.MervConfig.getSuiteAlias();
            
            if (alias == null || alias.trim().isEmpty()) {
                throw new MervClientException("merv.suite_alias is not configured in merv.properties");
            }
            
            return getTestSuiteIdByAlias(alias.trim());
        } catch (MervClientException e) {
            throw e;
        } catch (Exception e) {
            throw new MervClientException("Error getting test suite ID from configuration: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create a new test case
     * 
     * @param request The test case request
     * @return The created test case response
     * @throws MervClientException if the request fails
     */
    public TestCaseResponse createTestCase(TestCaseRequest request) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(request);
            HttpPost httpPost = new HttpPost(baseUrl + "/testcases");
            httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPost);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 201) {
                    return objectMapper.readValue(responseBody, TestCaseResponse.class);
                } else {
                    throw new MervClientException("Failed to create test case: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error creating test case", e);
        }
    }
    
    /**
     * Get all test cases
     * 
     * @return List of test cases
     * @throws MervClientException if the request fails
     */
    public List<TestCaseResponse> getAllTestCases() throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/testcases");
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TestCaseResponse.class));
                } else {
                    throw new MervClientException("Failed to get test cases: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test cases", e);
        }
    }
    
    /**
     * Get test cases by test suite ID
     * 
     * @param testSuiteId The test suite ID
     * @return List of test cases
     * @throws MervClientException if the request fails
     */
    public List<TestCaseResponse> getTestCasesByTestSuite(UUID testSuiteId) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/testcases/testsuite/" + testSuiteId);
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TestCaseResponse.class));
                } else {
                    throw new MervClientException("Failed to get test cases: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test cases", e);
        }
    }
    
    /**
     * Update test case status
     * 
     * @param id The test case ID
     * @param status The new status
     * @return The updated test case response
     * @throws MervClientException if the request fails
     * @deprecated Use finishTestCase instead. Status is now calculated automatically based on test steps.
     */
    @Deprecated
    public TestCaseResponse updateTestCaseStatus(UUID id, org.teche.merv.client.dto.TestCaseStatus status) throws MervClientException {
        try {
            // Use the enum's toValue() method to get the backend-compatible string
            String json = objectMapper.writeValueAsString(new TestCaseStatusUpdateRequest(status.toValue()));
            HttpPut httpPut = new HttpPut(baseUrl + "/testcases/" + id + "/status");
            httpPut.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPut);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestCaseResponse.class);
                } else {
                    throw new MervClientException("Failed to update test case status: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error updating test case status", e);
        }
    }
    
    /**
     * Finish a test case by calculating its status based on all test steps.
     * The status is calculated according to the following rules:
     * 1. If any step is IN_PROGRESS → status = INPROGRESS
     * 2. If no IN_PROGRESS and any FAILED → status = FAILED
     * 3. If no IN_PROGRESS and no PASSED, only SKIPPED → status = SKIPPED
     * 4. Otherwise → status = PASSED
     * 
     * @param testCaseId The test case ID
     * @return The calculated test case status
     * @throws MervClientException if the request fails
     */
    public org.teche.merv.client.dto.TestCaseStatus finishTestCase(UUID testCaseId) throws MervClientException {
        try {
            HttpPut httpPut = new HttpPut(baseUrl + "/testcases/" + testCaseId + "/finish");
            setAuthHeader(httpPut);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    TestCaseResponse testCaseResponse = objectMapper.readValue(responseBody, TestCaseResponse.class);
                    return testCaseResponse.getStatus();
                } else {
                    throw new MervClientException("Failed to finish test case: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error finishing test case", e);
        }
    }
    
    /**
     * Restart a test case by resetting its status back to INPROGRESS.
     * This allows test step status updates after a test case has been finished.
     * After restarting, you can update test steps and then finish the test case again.
     * 
     * @param testCaseId The test case ID
     * @return The updated test case response with INPROGRESS status
     * @throws MervClientException if the request fails
     */
    public TestCaseResponse restartTestCase(UUID testCaseId) throws MervClientException {
        try {
            HttpPut httpPut = new HttpPut(baseUrl + "/testcases/" + testCaseId + "/restart");
            setAuthHeader(httpPut);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestCaseResponse.class);
                } else {
                    throw new MervClientException("Failed to restart test case: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error restarting test case", e);
        }
    }
    
    /**
     * Create a new test step
     * 
     * @param request The test step request
     * @return The created test step response
     * @throws MervClientException if the request fails
     */
    public TestStepResponse createTestStep(TestStepRequest request) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(request);
            HttpPost httpPost = new HttpPost(baseUrl + "/teststeps");
            httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPost);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 201) {
                    return objectMapper.readValue(responseBody, TestStepResponse.class);
                } else {
                    throw new MervClientException("Failed to create test step: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error creating test step", e);
        }
    }
    
    /**
     * Get all test steps
     * 
     * @return List of test steps
     * @throws MervClientException if the request fails
     */
    public List<TestStepResponse> getAllTestSteps() throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/teststeps");
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TestStepResponse.class));
                } else {
                    throw new MervClientException("Failed to get test steps: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test steps", e);
        }
    }
    
    /**
     * Get test step by ID
     * 
     * @param id The test step ID
     * @return Test step response
     * @throws MervClientException if the request fails
     */
    public TestStepResponse getTestStepById(UUID id) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/teststeps/" + id);
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
               
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestStepResponse.class);
                } else {
                    throw new MervClientException("Failed to get test step: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test step", e);
        }
    }
    
    /**
     * Get test steps by test case ID
     * 
     * @param testCaseId The test case ID
     * @return List of test steps
     * @throws MervClientException if the request fails
     */
    public List<TestStepResponse> getTestStepsByTestCase(UUID testCaseId) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/teststeps/testcase/" + testCaseId);
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
               
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TestStepResponse.class));
                } else {
                    throw new MervClientException("Failed to get test steps: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting test steps", e);
        }
    }
    
    /**
     * Update a test step
     * 
     * @param id The test step ID
     * @param request The test step update request
     * @return The updated test step response
     * @throws MervClientException if the request fails
     */
    public TestStepResponse updateTestStep(UUID id, TestStepRequest request) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(request);
            HttpPut httpPut = new HttpPut(baseUrl + "/teststeps/" + id);
            httpPut.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPut);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestStepResponse.class);
                } else {
                    throw new MervClientException("Failed to update test step: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error updating test step", e);
        }
    }
    
    /**
     * Partially update a test step (PATCH)
     * Only provided fields will be updated
     * 
     * @param id The test step ID
     * @param patchRequest The test step patch request with only fields to update
     * @return The updated test step response
     * @throws MervClientException if the request fails
     */
    public TestStepResponse patchTestStep(UUID id, TestStepPatchRequest patchRequest) throws MervClientException {
        try {
            String json = objectMapper.writeValueAsString(patchRequest);
            HttpPatch httpPatch = new HttpPatch(baseUrl + "/teststeps/" + id);
            httpPatch.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            setAuthHeader(httpPatch);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPatch)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, TestStepResponse.class);
                } else {
                    throw new MervClientException("Failed to patch test step: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error patching test step", e);
        }
    }
    
    /**
     * Delete a test step
     * 
     * @param id The test step ID
     * @throws MervClientException if the request fails
     */
    public void deleteTestStep(UUID id) throws MervClientException {
        try {
            HttpDelete httpDelete = new HttpDelete(baseUrl + "/teststeps/" + id);
            setAuthHeader(httpDelete);
            
            try (CloseableHttpResponse response = httpClient.execute(httpDelete)) {
                if (response.getCode() == 204) {
                    // Successfully deleted
                    return;
                } else {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    throw new MervClientException("Failed to delete test step: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error deleting test step", e);
        }
    }
    
    /**
     * Check if the client is connected to the server
     * This method verifies both server connectivity and authentication
     * 
     * @return true if connected and authenticated, false otherwise
     */
    public boolean isConnected() {
        try {
            // Make a lightweight API call to verify server is responding
            HttpGet httpGet = new HttpGet(baseUrl + "/testsuites");
            
            if (isUsingApiKey()) {
                // Use API key authentication
                setAuthHeader(httpGet);
            } else {
            // Try to get a valid access token (tests authentication and connectivity)
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                logger.debug("Connection check failed: No valid access token");
                return false;
            }
                // For connection check, use token directly
            httpGet.setHeader("Authorization", "Bearer " + token);
            }
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                
                // 200 OK means connected and authenticated
                // 401/403 means server is reachable but auth failed (shouldn't happen if token is valid)
                // Other status codes might indicate server issues but server is reachable
                boolean connected = statusCode >= 200 && statusCode < 500;
                
                if (connected) {
                    logger.debug("Connection check successful: Server is reachable and responding");
                } else {
                    logger.debug("Connection check failed: Server returned status code {}", statusCode);
                }
                
                return connected;
            }
        } catch (MervClientException e) {
            logger.debug("Connection check failed: {}", e.getMessage());
            return false;
        } catch (IOException e) {
            logger.debug("Connection check failed: Server unreachable or network error - {}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.debug("Connection check failed: Unexpected error - {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if the client is connected to the server
     * This method verifies both server connectivity and authentication
     * Throws exception if not connected (for explicit error handling)
     * 
     * @throws MervClientException if connection check fails
     */
    public void verifyConnection() throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/testsuites");
            
            if (isUsingApiKey()) {
                // Use API key authentication
                setAuthHeader(httpGet);
            } else {
            // Try to get a valid access token
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                throw new MervClientException("Connection check failed: No valid access token");
            }
            
                // For connection check, use token directly (not API key)
            httpGet.setHeader("Authorization", "Bearer " + token);
            }
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                
                if (statusCode == 401 || statusCode == 403) {
                    throw new MervClientException("Connection check failed: Authentication failed (status: " + statusCode + ")");
                } else if (statusCode >= 500) {
                    throw new MervClientException("Connection check failed: Server error (status: " + statusCode + ")");
                } else if (statusCode < 200 || statusCode >= 400) {
                    throw new MervClientException("Connection check failed: Unexpected response (status: " + statusCode + ")");
                }
                
                // Status 200-299 means success
                logger.debug("Connection verified: Server is reachable and responding");
            }
        } catch (MervClientException e) {
            throw e; // Re-throw MervClientException as-is
        } catch (IOException e) {
            throw new MervClientException("Connection check failed: Server unreachable or network error", e);
        } catch (Exception e) {
            throw new MervClientException("Connection check failed: Unexpected error", e);
        }
    }
    
    /**
     * Upload a file to a test step (from File object)
     * 
     * @param testStepId The test step ID
     * @param file The file to upload
     * @param description Optional file description
     * @return The file attachment response
     * @throws MervClientException if the upload fails
     */
    public FileAttachmentResponse uploadFile(UUID testStepId, File file, String description) throws MervClientException {
        if (file == null || !file.exists()) {
            throw new MervClientException("File does not exist: " + (file != null ? file.getPath() : "null"));
        }
        
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            return uploadFile(testStepId, fileInputStream, file.getName(), description);
        } catch (IOException e) {
            throw new MervClientException("Error reading file: " + file.getPath(), e);
        }
    }
    
    /**
     * Upload a file to a test step (from byte array/binary data)
     * 
     * @param testStepId The test step ID
     * @param fileData The file content as byte array
     * @param filename The filename (should include extension)
     * @param description Optional file description
     * @return The file attachment response
     * @throws MervClientException if the upload fails
     */
    public FileAttachmentResponse uploadFile(UUID testStepId, byte[] fileData, String filename, String description) throws MervClientException {
        if (fileData == null || fileData.length == 0) {
            throw new MervClientException("File data is empty");
        }
        if (filename == null || filename.trim().isEmpty()) {
            throw new MervClientException("Filename is required");
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileData)) {
            return uploadFile(testStepId, inputStream, filename, description);
        } catch (IOException e) {
            throw new MervClientException("Error uploading file from byte array", e);
        }
    }
    
    /**
     * Upload a file to a test step (from InputStream)
     * 
     * @param testStepId The test step ID
     * @param inputStream The input stream containing file data
     * @param filename The filename (should include extension)
     * @param description Optional file description
     * @return The file attachment response
     * @throws MervClientException if the upload fails
     */
    public FileAttachmentResponse uploadFile(UUID testStepId, InputStream inputStream, String filename, String description) throws MervClientException {
        if (inputStream == null) {
            throw new MervClientException("Input stream is null");
        }
        if (filename == null || filename.trim().isEmpty()) {
            throw new MervClientException("Filename is required");
        }
        
        try {
            // Read the input stream into a byte array
            byte[] fileData = inputStream.readAllBytes();
            
            // Create multipart form data manually
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            String lineEnd = "\r\n";
            String twoHyphens = "--";
            
            // Build multipart body using ByteArrayOutputStream for better handling
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            
            // File part header
            outputStream.write((twoHyphens + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
            outputStream.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + lineEnd).getBytes(StandardCharsets.UTF_8));
            outputStream.write(("Content-Type: application/octet-stream" + lineEnd).getBytes(StandardCharsets.UTF_8));
            outputStream.write(lineEnd.getBytes(StandardCharsets.UTF_8));
            
            // File data
            outputStream.write(fileData);
            
            // Description part (if provided)
            if (description != null && !description.trim().isEmpty()) {
                outputStream.write(lineEnd.getBytes(StandardCharsets.UTF_8));
                outputStream.write((twoHyphens + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Disposition: form-data; name=\"description\"" + lineEnd).getBytes(StandardCharsets.UTF_8));
                outputStream.write(lineEnd.getBytes(StandardCharsets.UTF_8));
                outputStream.write(description.getBytes(StandardCharsets.UTF_8));
            }
            
            // Footer
            outputStream.write(lineEnd.getBytes(StandardCharsets.UTF_8));
            outputStream.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes(StandardCharsets.UTF_8));
            
            byte[] multipartBody = outputStream.toByteArray();
            
            // Create HTTP POST request
            HttpPost httpPost = new HttpPost(baseUrl + "/teststeps/" + testStepId + "/files");
            setAuthHeader(httpPost);
            // Set Content-Type header with boundary parameter
            httpPost.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
            // Create entity with multipart/form-data content type (without boundary parameter)
            httpPost.setEntity(new org.apache.hc.core5.http.io.entity.ByteArrayEntity(
                multipartBody, 
                ContentType.MULTIPART_FORM_DATA
            ));
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 201) {
                    return objectMapper.readValue(responseBody, FileAttachmentResponse.class);
                } else {
                    throw new MervClientException("Failed to upload file: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error uploading file", e);
        }
    }
    
    /**
     * Get all files attached to a test step
     * 
     * @param testStepId The test step ID
     * @return List of file attachments
     * @throws MervClientException if the request fails
     */
    public List<FileAttachmentResponse> getFilesByTestStepId(UUID testStepId) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/teststeps/" + testStepId + "/files");
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    return objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FileAttachmentResponse.class));
                } else {
                    throw new MervClientException("Failed to get files: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error getting files", e);
        }
    }
    
    /**
     * Download a file by its ID
     * 
     * @param fileId The file attachment ID
     * @return The file content as byte array
     * @throws MervClientException if the download fails
     */
    public byte[] downloadFile(UUID fileId) throws MervClientException {
        try {
            HttpGet httpGet = new HttpGet(baseUrl + "/files/" + fileId + "/download");
            setAuthHeader(httpGet);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                if (response.getCode() == 200) {
                    return EntityUtils.toByteArray(response.getEntity());
                } else {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    throw new MervClientException("Failed to download file: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error downloading file", e);
        }
    }
    
    /**
     * Delete a file attachment
     * 
     * @param fileId The file attachment ID
     * @throws MervClientException if the deletion fails
     */
    public void deleteFile(UUID fileId) throws MervClientException {
        try {
            HttpDelete httpDelete = new HttpDelete(baseUrl + "/files/" + fileId);
            setAuthHeader(httpDelete);
            
            try (CloseableHttpResponse response = httpClient.execute(httpDelete)) {
                if (response.getCode() != 204) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    throw new MervClientException("Failed to delete file: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error deleting file", e);
        }
    }
    
    /**
     * Create a MervClient instance from configuration file
     * Supports both username/password and API key authentication
     * API key takes precedence if both are provided
     * 
     * @return MervClient instance configured from merv.properties
     * @throws MervClientException if configuration is invalid
     */
    public static MervClient fromConfig() throws MervClientException {
        try {
            org.teche.merv.client.config.MervConfig.loadConfiguration();
            String baseUrl = org.teche.merv.client.config.MervConfig.getServer();
            String apiKey = org.teche.merv.client.config.MervConfig.getApiKey();
            String username = org.teche.merv.client.config.MervConfig.getUsername();
            String password = org.teche.merv.client.config.MervConfig.getPassword();
            
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new MervClientException("merv.server is not configured in merv.properties");
            }
            
            // API key takes precedence
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                return new MervClient(baseUrl, apiKey.trim(), true);
            } else if (username != null && password != null && 
                      !username.trim().isEmpty() && !password.trim().isEmpty()) {
                return new MervClient(baseUrl, username.trim(), password.trim());
            } else {
                throw new MervClientException("Either merv.api_key or (merv.username and merv.password) must be configured in merv.properties");
            }
        } catch (Exception e) {
            throw new MervClientException("Error loading configuration: " + e.getMessage(), e);
        }
    }
    
    /**
     * Close the HTTP client and AuthManager
     */
    public void close() {
        try {
            httpClient.close();
            if (authManager != null) {
                authManager.close();
            }
        } catch (IOException e) {
            logger.error("Error closing HTTP client", e);
        }
    }
    
    /**
     * Inner class for test case status update request
     */
    private static class TestCaseStatusUpdateRequest {
        private String status;
        
        public TestCaseStatusUpdateRequest(String status) {
            this.status = status;
        }
        
        public String getStatus() {
            return status;
        }
        
        public void setStatus(String status) {
            this.status = status;
        }
    }
}
