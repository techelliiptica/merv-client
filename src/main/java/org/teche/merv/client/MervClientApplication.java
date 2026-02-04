package org.teche.merv.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teche.merv.client.dto.TestSuiteRequest;
import org.teche.merv.client.dto.TestSuiteResponse;
import org.teche.merv.client.exception.MervClientException;

/**
 * Main application class for demonstrating the MERV Client API usage
 * This class serves as an entry point for the executable JAR
 */
public class MervClientApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(MervClientApplication.class);
    
    public static void main(String[] args) {
        logger.info("MERV Client API Demo Application Starting...");

        // Check if required arguments are provided
        /*if (args.length < 3) {
            System.out.println("Usage: java -jar merv-client-api.jar <baseUrl> <username> <password>");
            System.out.println("Example: java -jar merv-client-api.jar http://localhost:7777/api/v1 admin password");
            System.exit(1);
        }*/

        String baseUrl = "http://localhost:7777/api/v1";
        String username = "admin";
        String password = "password";
        try (MervClient client = new MervClient(baseUrl, username, password)) {
            logger.info("Successfully connected to MERV API at: {}", baseUrl);
            
            // Demo: Get all test suites
            logger.info("Fetching all test suites...");
            var testSuites = client.getAllTestSuites();
            logger.info("Found {} test suites", testSuites.size());
            
            // Demo: Get a test suite by ID (if any exist)
            if (!testSuites.isEmpty()) {
                TestSuiteResponse firstSuite = testSuites.get(0);
                logger.info("Retrieving test suite by ID: {}", firstSuite.getId());
                TestSuiteResponse suiteById = client.getTestSuiteById(firstSuite.getId());
                logger.info("Retrieved test suite: '{}' (Environment: {}, Release: {})", 
                    suiteById.getTitle(), suiteById.getEnvironment(), suiteById.getReleaseName());
            }
            
            // Demo: Create a test suite
            logger.info("Creating a demo test suite...");
            TestSuiteRequest request = new TestSuiteRequest();
            request.setTitle("Demo Test Suite from Client API");
            request.setEnvironment("Development");
            request.setReleaseName("v1.0.0");
            request.setSprint("Sprint 1");
            
            // Use hierarchy ID from existing test suite if available, otherwise skip creation
            if (!testSuites.isEmpty() && testSuites.get(0).getHierarchyId() != null) {
                request.setHierarchyId(testSuites.get(0).getHierarchyId());
                logger.info("Using hierarchy ID from existing test suite: {}", request.getHierarchyId());
            
                TestSuiteResponse response = client.createTestSuite(request);
                logger.info("Created test suite with ID: {}", response.getId());
            } else {
                logger.warn("Skipping test suite creation: No existing test suites with hierarchy ID found");
            }
            
            logger.info("Demo completed successfully!");
            
        } catch (MervClientException e) {
            logger.error("Error during demo: {}", e.getMessage(), e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
