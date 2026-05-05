package org.teche.merv.client.report.html;

/**
 * HTML escaping for generated local reports. Shared by index, suite, and future runner-specific pages.
 */
public final class MervHtmlEscape {

    private MervHtmlEscape() {}

    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
