package org.teche.merv.client.exception;

/**
 * Custom exception for MERV client API operations
 */
public class MervClientException extends Exception {
    
    public MervClientException(String message) {
        super(message);
    }
    
    public MervClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
