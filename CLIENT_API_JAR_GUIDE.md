# MERV Client API JAR Guide

## 🎯 Overview

The MERV Client API JAR is a standalone executable JAR file that contains all dependencies and provides a Java client library for interacting with the MERV Test Management System API.

## 📦 Generated JAR Files

After building the project, you'll find these JAR files in the `target/` directory:

1. **`merv-client-api-3.0.0-jar-with-dependencies.jar`** (4.5 MB) - **Main executable JAR with all dependencies**
2. `merv-client-api-3.0.0.jar` (40 KB) - Standard JAR without dependencies
3. `merv-client-api-3.0.0-sources.jar` (16 KB) - Source code JAR
4. `merv-client-api-3.0.0-javadoc.jar` (190 KB) - JavaDoc documentation JAR

## 🚀 Quick Start

### Prerequisites
- Java 17 or higher
- MERV API server running (default: http://localhost:7777)

### Running the Demo Application

```bash
# Basic usage
java -jar merv-client-api-3.0.0-jar-with-dependencies.jar <baseUrl> <username> <password>

# Example with local server
java -jar merv-client-api-3.0.0-jar-with-dependencies.jar http://localhost:7777/api/v1 admin password

# Example with remote server
java -jar merv-client-api-3.0.0-jar-with-dependencies.jar http://your-server.com/api/v1 youruser yourpass
```

### What the Demo Does
1. Connects to the MERV API using provided credentials
2. Fetches all existing test suites
3. Creates a new demo test suite
4. Displays the results

## 📚 Using as a Library

### Adding to Your Project

#### Option 1: Maven Dependency
```xml
<dependency>
    <groupId>io.github.techelliiptica</groupId>
    <artifactId>merv-client-api</artifactId>
    <version>3.0.0</version>
</dependency>
```

#### Option 2: Direct JAR Inclusion
Add `merv-client-api-3.0.0-jar-with-dependencies.jar` to your project's classpath.

### Basic Usage Example

```java
import org.teche.merv.client.MervClient;
import org.teche.merv.client.dto.TestSuiteRequest;
import org.teche.merv.client.dto.TestSuiteResponse;

public class MyTestManager {
    public static void main(String[] args) {
        // Create client with automatic token management
        try (MervClient client = new MervClient(
                "http://localhost:7777/api/v1", 
                "admin", 
                "password")) {
            
            // Get all test suites
            var testSuites = client.getAllTestSuites();
            System.out.println("Found " + testSuites.size() + " test suites");
            
            // Create a new test suite
            TestSuiteRequest request = new TestSuiteRequest();
            request.setTitle("My Test Suite");
            request.setEnvironment("Production");
            request.setReleaseName("v2.0.0");
            
            TestSuiteResponse response = client.createTestSuite(request);
            System.out.println("Created test suite with ID: " + response.getId());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

## 🔧 Available Features

### Authentication
- **Automatic token management** - Handles JWT token refresh automatically
- **Credential-based authentication** - Login with username/password
- **Token-based authentication** - Use existing JWT token (legacy)

### Test Suite Management
- `getAllTestSuites()` - Fetch all test suites
- `getTestSuiteById(UUID id)` - Get specific test suite
- `createTestSuite(TestSuiteRequest request)` - Create new test suite
- `updateTestSuite(UUID id, TestSuiteRequest request)` - Update existing test suite
- `deleteTestSuite(UUID id)` - Delete test suite

### Test Case Management
- `getAllTestCases()` - Fetch all test cases
- `getTestCaseById(UUID id)` - Get specific test case
- `getTestCasesByTestSuite(UUID testSuiteId)` - Get test cases for a suite
- `createTestCase(TestCaseRequest request)` - Create new test case
- `updateTestCase(UUID id, TestCaseRequest request)` - Update existing test case
- `updateTestCaseStatus(UUID id, String status)` - Update test case status
- `deleteTestCase(UUID id)` - Delete test case

### Test Step Management
- `getAllTestSteps()` - Fetch all test steps
- `getTestStepById(UUID id)` - Get specific test step
- `getTestStepsByTestCase(UUID testCaseId)` - Get test steps for a test case
- `createTestStep(TestStepRequest request)` - Create new test step
- `updateTestStep(UUID id, TestStepRequest request)` - Update existing test step
- `deleteTestStep(UUID id)` - Delete test step

### Hierarchy Management
- `getAllHierarchies()` - Fetch all hierarchies
- `getHierarchyById(UUID id)` - Get specific hierarchy
- `createHierarchy(HierarchyRequest request)` - Create new hierarchy
- `updateHierarchy(UUID id, HierarchyRequest request)` - Update existing hierarchy
- `deleteHierarchy(UUID id)` - Delete hierarchy

## 🛠️ Builder Classes

The client API includes convenient builder classes for creating requests:

### TestSuiteBuilder
```java
TestSuiteRequest request = TestSuiteBuilder.create()
    .title("My Test Suite")
    .environment("Development")
    .releaseName("v1.0.0")
    .sprint("Sprint 1")
    .addTag("automation")
    .addTag("regression")
    .build();
```

### TestCaseBuilder
```java
TestCaseRequest request = TestCaseBuilder.create()
    .testcaseName("Login Test")
    .description("Test user login functionality")
    .testSuiteId(testSuiteId)
    .status("DRAFT")
    .addTag("smoke")
    .addExecutionMachine("Windows")
    .build();
```

### TestStepBuilder
```java
TestStepRequest request = TestStepBuilder.create()
    .teststepName("Enter Username")
    .testcaseId(testCaseId)
    .testdata("testuser@example.com")
    .expected("Username field should be populated")
    .actual("Username field populated successfully")
    .status("PASSED")
    .build();
```

## 🔍 Error Handling

The client throws `MervClientException` for API-related errors:

```java
try {
    var testSuites = client.getAllTestSuites();
} catch (MervClientException e) {
    System.err.println("API Error: " + e.getMessage());
    // Handle specific error cases
    if (e.getMessage().contains("401")) {
        System.err.println("Authentication failed");
    }
}
```

## 📊 Logging

The client uses SLF4J for logging. To enable logging, add a logging implementation to your classpath:

### Logback (Recommended)
```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.4.7</version>
</dependency>
```

### Simple Logging
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.7</version>
</dependency>
```

## 🐛 Troubleshooting

### Common Issues

1. **"SLF4J: No SLF4J providers were found"**
   - Add a logging implementation to your classpath
   - This warning doesn't affect functionality

2. **"Connection refused"**
   - Ensure the MERV API server is running
   - Check the base URL is correct

3. **"401 Unauthorized"**
   - Verify username and password are correct
   - Check if the user has proper permissions

4. **"403 Forbidden"**
   - Ensure the user has the required roles (ROLE_ADMIN)
   - Check API endpoint permissions

### Debug Mode
Enable debug logging by setting the log level:
```bash
# For simple logging
export SLF4J_SIMPLE_LOG_LEVEL=DEBUG

# For logback, add to logback.xml
<logger name="org.teche.merv.client" level="DEBUG"/>
```

## 📝 Examples

### Complete Automation Example
```java
import org.teche.merv.client.*;
import org.teche.merv.client.dto.*;
import org.teche.merv.client.utils.*;

public class TestAutomation {
    public static void main(String[] args) {
        try (MervClient client = new MervClient(
                "http://localhost:7777/api/v1", 
                "admin", 
                "password")) {
            
            // Create test suite
            TestSuiteRequest suiteRequest = TestSuiteBuilder.create()
                .title("Automated Test Suite")
                .environment("QA")
                .releaseName("v1.0.0")
                .build();
            
            TestSuiteResponse suite = client.createTestSuite(suiteRequest);
            
            // Create test case
            TestCaseRequest caseRequest = TestCaseBuilder.create()
                .testcaseName("User Login Test")
                .description("Automated login test")
                .testSuiteId(suite.getId())
                .status("DRAFT")
                .build();
            
            TestCaseResponse testCase = client.createTestCase(caseRequest);
            
            // Create test steps
            TestStepRequest step1 = TestStepBuilder.create()
                .teststepName("Navigate to Login Page")
                .testcaseId(testCase.getId())
                .expected("Login page should load")
                .status("PASSED")
                .build();
            
            client.createTestStep(step1);
            
            System.out.println("Test automation setup completed!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

## 🎯 Next Steps

1. **Test the JAR** - Run the demo with your API server
2. **Integrate into your project** - Use as a library in your Java applications
3. **Customize** - Extend the client for your specific needs
4. **Automate** - Build test automation frameworks using this client

## 📞 Support

For issues or questions:
1. Check the troubleshooting section
2. Review the JavaDoc documentation
3. Examine the example code in the source
4. Check the MERV API documentation for endpoint details
