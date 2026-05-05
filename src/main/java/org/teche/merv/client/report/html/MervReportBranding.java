package org.teche.merv.client.report.html;

/**
 * Shared chrome for locally generated HTML reports (sidebar, gradients). Used by Cucumber and any future local runners.
 */
public final class MervReportBranding {

    private MervReportBranding() {}

    /** Sidebar logo — red variant for light nav. */
    public static final String LOGO_URL = "https://merv.online/images/logo-red.png";

    /** Primary UI gradient for local report chrome (sidebar, accents). */
    public static final String GRADIENT_CSS = "linear-gradient(135deg,#e90101,#c20000)";

    /**
     * If {@code running} stays true but JSON is not refreshed for this long, live UI treats the run as aborted
     * (killed/stopped JVM).
     */
    public static final long LOCAL_RUN_STALE_AFTER_MS = 60_000L;
}
