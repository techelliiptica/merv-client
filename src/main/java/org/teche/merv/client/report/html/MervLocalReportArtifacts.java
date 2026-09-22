package org.teche.merv.client.report.html;

import java.io.File;

/**
 * Shared post-finalize hooks for local report artifacts (emailable HTML, etc.).
 * Offline zip is built on download only — not on every run finalize (matches merv-client-js).
 */
public final class MervLocalReportArtifacts {

    private MervLocalReportArtifacts() {
    }

    /**
     * After local suite finalize (upload zip may already have been written separately).
     * Writes emailable HTML when {@code merv.emailable.html=true}.
     */
    public static void afterLocalFinalize(File runFolder) {
        MervEmailableHtmlWriter.writeIfEnabled(runFolder);
    }
}
