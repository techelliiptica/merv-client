# Field Mapping Fixes for Client API

## 🐛 Issue Resolved

**Problem**: `UnrecognizedPropertyException: Unrecognized field "uuid"` when deserializing API responses.

**Root Cause**: The MERV API returns JSON with field names that don't match the client DTO field names:
- API returns: `"uuid": "0a6068b4-518a-450a-b2f3-9d4a7ea4cef4"`
- Client expects: `"id": "0a6068b4-518a-450a-b2f3-9d4a7ea4cef4"`

## ✅ Fixes Applied

### 1. Added JsonAlias Annotations

Updated all response DTOs to handle both `id` and `uuid` field names:

#### TestSuiteResponse.java
```java
@JsonProperty("id")
@com.fasterxml.jackson.annotation.JsonAlias("uuid")
private UUID id;
```

#### TestCaseResponse.java
```java
@JsonProperty("id")
@com.fasterxml.jackson.annotation.JsonAlias("uuid")
private UUID id;
```

#### TestStepResponse.java
```java
@JsonProperty("id")
@com.fasterxml.jackson.annotation.JsonAlias("uuid")
private UUID id;
```

### 2. Enhanced Jackson Configuration

Updated `MervClient.java` to ignore unknown properties:

```java
this.objectMapper.configure(
    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, 
    false
);
```

## 🔧 How It Works

### JsonAlias Annotation
- `@JsonProperty("id")` - Primary field name for serialization/deserialization
- `@JsonAlias("uuid")` - Alternative field name that can be used during deserialization
- Jackson will accept either `"id"` or `"uuid"` in the JSON and map it to the `id` field

### FAIL_ON_UNKNOWN_PROPERTIES = false
- Prevents Jackson from throwing exceptions for unknown fields
- Allows the API to add new fields without breaking the client
- Makes the client more resilient to API changes

## 📋 Affected Files

1. **client-api/src/main/java/org/teche/merv/client/dto/TestSuiteResponse.java**
   - Added JsonAlias for `id` field

2. **client-api/src/main/java/org/teche/merv/client/dto/TestCaseResponse.java**
   - Added JsonAlias for `id` field

3. **client-api/src/main/java/org/teche/merv/client/dto/TestStepResponse.java**
   - Added JsonAlias for `id` field

4. **client-api/src/main/java/org/teche/merv/client/MervClient.java**
   - Added Jackson configuration to ignore unknown properties

## 🧪 Testing

The fixes handle these scenarios:

### ✅ API Returns "uuid"
```json
{
  "uuid": "0a6068b4-518a-450a-b2f3-9d4a7ea4cef4",
  "title": "test",
  "environment": "qa"
}
```
**Result**: Successfully deserialized to `TestSuiteResponse.id`

### ✅ API Returns "id"
```json
{
  "id": "0a6068b4-518a-450a-b2f3-9d4a7ea4cef4",
  "title": "test",
  "environment": "qa"
}
```
**Result**: Successfully deserialized to `TestSuiteResponse.id`

### ✅ API Returns Additional Fields
```json
{
  "uuid": "0a6068b4-518a-450a-b2f3-9d4a7ea4cef4",
  "title": "test",
  "environment": "qa",
  "newField": "value",
  "anotherField": 123
}
```
**Result**: Successfully deserialized, unknown fields ignored

## 🚀 Benefits

1. **Backward Compatibility** - Works with both `id` and `uuid` field names
2. **Forward Compatibility** - Ignores new API fields gracefully
3. **Resilient** - Won't break if API adds new fields
4. **Consistent** - All response DTOs handle field mapping consistently

## 📦 Updated JAR

The updated JAR file `merv-client-api-1.0.0-jar-with-dependencies.jar` includes all these fixes and is ready for use.

## 🔍 Verification

To verify the fix works:

```bash
# Test with the updated JAR
java -jar target/merv-client-api-1.0.0-jar-with-dependencies.jar http://localhost:7777/api/v1 admin password
```

The client should now successfully connect and handle API responses without field mapping errors.
