/**
 * Local HTML report building blocks shared across test frameworks (Cucumber today; TestNG/JUnit later).
 *
 * <p><b>Before adding a new runner or duplicating report HTML</b>, read
 * {@code merv-client/LOCAL_REPORTS_CONTEXT.md} at the repository root (module-relative path from project root).
 * It describes folder layout, {@code merv-report.json} expectations, and the integration checklist.
 *
 * <h2>Main entry points</h2>
 * <ul>
 *   <li>{@link org.teche.merv.client.report.html.MervReportsIndexHtmlWriter} — writes/refreshes the reports dashboard {@code index.html}</li>
 *   <li>{@link org.teche.merv.client.report.html.MervReportBranding} — shared logo, gradients, live-run staleness constant</li>
 *   <li>{@link org.teche.merv.client.report.html.MervHtmlEscape} — HTML escaping for generated markup</li>
 *   <li>{@link org.teche.merv.client.report.html.MervEmailableHtmlWriter} — interactive {@code emailable-report.html} ({@code merv.emailable.html})</li>
 *   <li>{@link org.teche.merv.client.report.html.MervOfflineReportZipWriter} — offline share zip (download only)</li>
 *   <li>{@link org.teche.merv.client.report.html.MervLocalReportArtifacts} — post-finalize emailable hook</li>
 *   <li>{@link org.teche.merv.client.report.html.MervSuiteShareDownloadHtml} — Share/Download suite chrome</li>
 * </ul>
 */
package org.teche.merv.client.report.html;
