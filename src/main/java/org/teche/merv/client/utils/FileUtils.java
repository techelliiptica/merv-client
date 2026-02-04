package org.teche.merv.client.utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for file operations in Merv Client.
 * Provides methods for writing files and managing file system operations.
 * 
 * @author MERV Client Team
 * @version 1.0.0
 */
public class FileUtils {
    
    /**
     * Write content to a file at the specified path.
     * Creates parent directories if they don't exist.
     * 
     * @param filePath the path where the file should be written
     * @param content the content to write to the file
     * @throws IOException if an I/O error occurs
     */
    public static void writeFile(String filePath, String content) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
        
        if (content == null) {
            content = "";
        }
        
        // Create parent directories if they don't exist
        Path path = Paths.get(filePath);
        Path parentDir = path.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        
        // Write content to file
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        }
    }
    
    /**
     * Check if a file exists at the specified path.
     * 
     * @param filePath the path to check
     * @return true if the file exists, false otherwise
     */
    public static boolean fileExists(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return Files.exists(Paths.get(filePath));
    }
    
    /**
     * Create a directory at the specified path if it doesn't exist.
     * 
     * @param dirPath the path of the directory to create
     * @return true if the directory was created or already exists, false otherwise
     */
    public static boolean createDirectory(String dirPath) {
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return false;
        }
        
        try {
            Path path = Paths.get(dirPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return true;
        } catch (IOException e) {
            System.err.println("Error creating directory: " + dirPath + " - " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the parent directory path of a file.
     * 
     * @param filePath the file path
     * @return the parent directory path, or null if no parent exists
     */
    public static String getParentDirectory(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        
        Path path = Paths.get(filePath);
        Path parent = path.getParent();
        return parent != null ? parent.toString() : null;
    }
}

