package org.teche.merv.client.dto;

/**
 * Enum representing file types for attachment purposes
 */
public enum FileType {
    IMAGE("image", "image/*"),
    JSON("json", "application/json"),
    EXCEL("excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    XML("xml", "application/xml"),
    TXT("txt", "text/plain"),
    HTML("html", "text/html"),
    OTHERS("others", "application/octet-stream");

    private final String type;
    private final String mimeType;

    FileType(String type, String mimeType) {
        this.type = type;
        this.mimeType = mimeType;
    }

    /**
     * Get the file type string value
     * 
     * @return The file type string
     */
    public String getType() {
        return type;
    }

    /**
     * Get the MIME type for this file type
     * 
     * @return The MIME type string
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * Determine FileType from file extension
     * 
     * @param extension The file extension (e.g., "png", "json", "xlsx")
     * @return The corresponding FileType, or OTHERS if not recognized
     */
    public static FileType fromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return OTHERS;
        }
        
        String ext = extension.toLowerCase().trim();
        return switch (ext) {
            case "json" -> JSON;
            case "xml" -> XML;
            case "xlsx", "xls" -> EXCEL;
            case "txt" -> TXT;
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico" -> IMAGE;
            case "html", "htm" -> HTML;
            default -> OTHERS;
        };
    }

    /**
     * Determine FileType from filename
     * 
     * @param filename The filename (e.g., "document.pdf", "image.png")
     * @return The corresponding FileType, or OTHERS if extension not recognized
     */
    public static FileType fromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return OTHERS;
        }
        
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return OTHERS;
        }
        
        String extension = filename.substring(lastDotIndex + 1);
        return fromExtension(extension);
    }
}

