package org.teche.merv.client.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teche.merv.client.exception.MervClientException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Manages authentication and token refresh for the MERV client
 */
public class AuthManager {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthManager.class);
    
    private final String baseUrl;
    private final String username;
    private final String password;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private String accessToken;
    private String refreshToken;
    private LocalDateTime tokenExpiry;
    
    public AuthManager(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Login and get initial tokens
     * 
     * @throws MervClientException if login fails
     */
    public void login() throws MervClientException {
        try {
            LoginRequest loginRequest = new LoginRequest(username, password);
            String json = objectMapper.writeValueAsString(loginRequest);
            
            HttpPost httpPost = new HttpPost(baseUrl + "/login");
            httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);
                    this.accessToken = loginResponse.getAccessToken();
                    this.refreshToken = loginResponse.getToken(); // refresh token
                    
                    // Set token expiry to 1 hour from now (adjust based on your JWT expiry)
                    this.tokenExpiry = LocalDateTime.now().plus(1, ChronoUnit.HOURS);
                    
                    logger.info("Successfully logged in and obtained tokens");
                } else {
                    throw new MervClientException("Login failed: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error during login", e);
        }
    }
    
    /**
     * Refresh the access token using the refresh token
     * 
     * @throws MervClientException if refresh fails
     */
    public void refreshToken() throws MervClientException {
        try {
            RefreshTokenRequest refreshRequest = new RefreshTokenRequest(refreshToken);
            String json = objectMapper.writeValueAsString(refreshRequest);
            
            HttpPost httpPost = new HttpPost(baseUrl + "/refresh");
            httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (response.getCode() == 200) {
                    LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);
                    this.accessToken = loginResponse.getAccessToken();
                    this.refreshToken = loginResponse.getToken(); // new refresh token
                    
                    // Set token expiry to 1 hour from now
                    this.tokenExpiry = LocalDateTime.now().plus(1, ChronoUnit.HOURS);
                    
                    logger.info("Successfully refreshed access token");
                } else {
                    throw new MervClientException("Token refresh failed: " + responseBody);
                }
            }
        } catch (IOException | ParseException e) {
            throw new MervClientException("Error during token refresh", e);
        }
    }
    
    /**
     * Get a valid access token, refreshing if necessary
     * 
     * @return The access token
     * @throws MervClientException if authentication fails
     */
    public String getValidAccessToken() throws MervClientException {
        // If no token exists, login first
        if (accessToken == null) {
            login();
            return accessToken;
        }
        
        // If token is expired or will expire soon (within 5 minutes), refresh it
        if (tokenExpiry == null || LocalDateTime.now().plus(5, ChronoUnit.MINUTES).isAfter(tokenExpiry)) {
            try {
                refreshToken();
            } catch (MervClientException e) {
                // If refresh fails, try to login again
                logger.warn("Token refresh failed, attempting to login again", e);
                login();
            }
        }
        
        return accessToken;
    }
    
    /**
     * Check if the current token is valid
     * 
     * @return true if token is valid, false otherwise
     */
    public boolean isTokenValid() {
        return accessToken != null && tokenExpiry != null && 
               LocalDateTime.now().isBefore(tokenExpiry);
    }
    
    /**
     * Close the HTTP client
     */
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            logger.error("Error closing HTTP client", e);
        }
    }
    
    // Inner classes for request/response DTOs
    
    private static class LoginRequest {
        private String username;
        private String password;
        
        public LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
        
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
    
    private static class RefreshTokenRequest {
        private String token;
        
        public RefreshTokenRequest(String token) {
            this.token = token;
        }
        
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
    
    private static class LoginResponse {
        private String accessToken;
        private String token; // refresh token
        
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
