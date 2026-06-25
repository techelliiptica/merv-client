package org.teche.merv.client.utils;

import java.util.Locale;
import java.util.Properties;

/**
 * Shared parsing for common {@code merv.properties} boolean flags.
 */
public final class MervPropertyFlags {

    private MervPropertyFlags() {
    }

    /**
     * Whether per-step screenshots are enabled.
     * Reads {@code merv.screenshot} (preferred) or legacy {@code screenshot}.
     * Enabled when the value is {@code true}, {@code on}, {@code yes}, or {@code 1} (case-insensitive).
     */
    public static boolean isScreenshotEnabled(Properties properties) {
        if (properties == null) {
            return false;
        }
        String value = properties.getProperty("merv.screenshot");
        if (value == null) {
            value = properties.getProperty("screenshot");
        }
        return isTruthyValue(value);
    }

    /** @see #isScreenshotEnabled(Properties) */
    public static boolean isTruthyValue(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
                || "on".equals(normalized)
                || "yes".equals(normalized)
                || "1".equals(normalized);
    }
}
