# MERV Client

## 🎯 Overview

MERV Client is a Java client library for the MERV (Metrics Engine for Reports & Visualization) Test Management System. It provides a simple and intuitive API for integrating with the MERV backend services from Java applications.

## 🚀 Features

### ✅ **Easy Integration**
- **Simple API**: Clean and intuitive client interface
- **Auto-Configuration**: Automatic connection management
- **Error Handling**: Comprehensive error handling and recovery
- **Type Safety**: Strongly typed Java objects

### ✅ **Test Management Operations**
- **Test Suite Management**: Create, read, update, delete test suites
- **Test Case Management**: Manage test cases with metadata
- **Test Step Management**: Handle detailed test steps
- **Hierarchy Management**: Work with organizational structures

### ✅ **Authentication & Security**
- **JWT Authentication**: Automatic token management
- **API Key Authentication**: Alternative authentication using API keys
- **Token Refresh**: Seamless token renewal
- **Secure Communication**: HTTPS support
- **Credential Management**: Safe credential handling

### ✅ **Data Handling**
- **DTO Objects**: Clean data transfer objects
- **JSON Serialization**: Automatic JSON handling
- **Field Mapping**: Flexible field name mapping
- **Error Recovery**: Robust error handling

## 🛠️ Technology Stack

- **Java**: Core language (Java 11+)
- **Jackson**: JSON serialization/deserialization
- **OkHttp**: HTTP client
- **Maven**: Build tool
- **JUnit**: Testing framework

## 📦 Installation

### Prerequisites
- Java 11 or higher
- Maven 3.6+

### Maven Dependency
Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.teche</groupId>
    <artifactId>merv-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Manual Installation
```bash
# Clone the repository
git clone <repository-url>
cd merv-client

# Build the project
mvn clean install

# Create JAR with dependencies
mvn assembly:single
```

## 🔧 Configuration

### Basic Setup

#### Option 1: Username/Password Authentication
```java
// Create client instance with username and password
MervClient client = new MervClient(
    "http://103.145.50.107:7777/api/v1",
    "your-username",
    "your-password"
);
```

#### Option 2: API Key Authentication
```java
// Create client instance with API key (alternative to username/password)
MervClient client = new MervClient(
    "http://103.145.50.107:7777/api/v1",
    "merv_your_api_key_here",
    true  // indicates API key authentication
);
```

#### Option 3: Configuration File
```java
// Load from merv.properties file (supports both username/password and API key)
MervClient client = MervClient.fromConfig();
```

**Note**: API key authentication is recommended for automated scripts and CI/CD pipelines as it doesn't require token refresh.

### Advanced Configuration
```java
// With custom timeout
MervClient client = new MervClient(
    "http://103.145.50.107:7777/api/v1",
    "your-username", 
    "your-password",
    30000, // connection timeout
    60000  // read timeout
);
```

## 🚀 Usage Examples

### Authentication
```java
try (MervClient client = new MervClient(baseUrl, username, password)) {
    // Client automatically handles authentication
    System.out.println("Connected successfully!");
}
```

### Test Suite Operations
```java
// Get all test suites
List<TestSuiteResponse> testSuites = client.getAllTestSuites();

// Create a new test suite
TestSuiteRequest request = new TestSuiteRequest();
request.setTitle("My Test Suite");
request.setEnvironment("Production");
request.setReleaseName("v1.0.0");
request.setSprint("Sprint 1");

TestSuiteResponse response = client.createTestSuite(request);
```

### Test Case Operations
```java
// Get test cases by test suite
List<TestCaseResponse> testCases = client.getTestCasesBySuite(testSuiteId);

// Create a new test case
TestCaseRequest request = new TestCaseRequest();
request.setTestcaseName("Login Test");
request.setDescription("Test user login functionality");
request.setTestSuiteId(testSuiteId);

TestCaseResponse response = client.createTestCase(request);
```

### Test Step Operations
```java
// Get test steps by test case
List<TestStepResponse> testSteps = client.getTestStepsByTestCase(testCaseId);

// Create a new test step
TestStepRequest request = new TestStepRequest();
request.setTeststepName("Enter Username");
request.setExpected("Username field should accept input");
request.setTestcaseId(testCaseId);

TestStepResponse response = client.createTestStep(request);
```

## 📊 Data Models

### TestSuiteRequest
```java
public class TestSuiteRequest {
    private String title;
    private String environment;
    private String releaseName;
    private String sprint;
    private List<String> tags;
}
```

### TestCaseRequest
```java
public class TestCaseRequest {
    private String testcaseName;
    private String description;
    private String testSuiteId;
    private List<String> tags;
    private List<String> executionMachine;
    private String status;
    private boolean debug;
}
```

### TestStepRequest
```java
public class TestStepRequest {
    private String teststepName;
    private String testcaseId;
    private String expected;
    private String actual;
    private String prereq;
    private String testdata;
    private String status;
}
```

## 🔧 Advanced Features

### Error Handling
```java
try {
    TestSuiteResponse response = client.createTestSuite(request);
} catch (MervClientException e) {
    System.err.println("Error: " + e.getMessage());
    System.err.println("Status Code: " + e.getStatusCode());
}
```

### Custom ObjectMapper
```java
// Configure custom JSON handling
ObjectMapper mapper = new ObjectMapper();
mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
mapper.registerModule(new JavaTimeModule());

MervClient client = new MervClient(baseUrl, username, password, mapper);
```

### Field Mapping
The client automatically handles field name variations:
- `id` ↔ `uuid` (automatic mapping)
- Unknown properties are ignored
- Flexible JSON structure support

## 🚀 Building

### Create JAR with Dependencies
```bash
mvn assembly:single
```

This creates a fat JAR file that includes all dependencies.

### Run Demo Application
```bash
java -jar target/merv-client-1.0.0-jar-with-dependencies.jar \
    --baseUrl http://103.145.50.107:7777/api/v1 \
    --username testuser \
    --password testpass
```

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn verify
```

### Manual Testing
Use the included demo application:
```bash
mvn exec:java -Dexec.mainClass="org.teche.merv.client.MervClientApplication"
```

## 🔧 Configuration Options

### Connection Settings
- **Base URL**: MERV API endpoint
- **Username**: Authentication username
- **Password**: Authentication password
- **Connection Timeout**: Default 30 seconds
- **Read Timeout**: Default 60 seconds

### JSON Handling
- **Unknown Properties**: Ignored by default
- **Field Mapping**: Automatic `id` ↔ `uuid` mapping
- **Date Format**: ISO 8601 timestamp support
- **Null Handling**: Flexible null value handling

## 🚀 Deployment

### JAR Distribution
```bash
# Build fat JAR
mvn assembly:single

# Distribute the JAR file
cp target/merv-client-1.0.0-jar-with-dependencies.jar /path/to/distribution/
```

### Maven Repository
```bash
# Deploy to Maven repository
mvn deploy
```

## 🔧 Troubleshooting

### Common Issues
1. **Connection Errors**: Check API URL and network connectivity
2. **Authentication Errors**: Verify username and password
3. **JSON Parsing Errors**: Check field mapping configuration
4. **Timeout Errors**: Increase timeout values

### Debug Mode
```java
// Enable debug logging
System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "DEBUG");
```

### Error Recovery
The client includes automatic retry logic for:
- Network timeouts
- Authentication failures
- Temporary server errors

## 📄 License

This project is part of the MERV Test Management System.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

---

**MERV Client** - Simple Java Integration Library 🚀
