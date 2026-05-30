# Local Merv HTML reports — implementation context

This document is the **single source of truth** for how local reports are structured and how **additional test frameworks** (TestNG, JUnit, etc.) should integrate without duplicating HTML.

## Goals

- One shared **reports dashboard** (`{reportRoot}/index.html`) for all runs.
- Per-run folders under the configured report root, each with **HTML + JSON** so the live dashboard and KPI views stay consistent.
- **Reuse** the `org.teche.merv.client.report.html` package; do not paste large `html.append(...)` blocks into new framework handlers.

## Report layout on disk

Assume `reportRoot` = value from `MervConfig.getReportFolder()` (trailing separator optional).

| Path | Purpose |
|------|---------|
| `{reportRoot}/index.html` | Local dashboard: suite cards, KPI/consolidated views, Chart.js. Regenerated when runs finish or folders change. |
| `{reportRoot}/{runFolder}/html/merv-report.html` | Final suite report (often finalized from live). |
| `{reportRoot}/{runFolder}/html/merv-report-live.html` | Live-updating suite report during execution. |
| `{reportRoot}/{runFolder}/json/merv-report.json` | **Source of truth** for the dashboard and tooling. |
| `{reportRoot}/{runFolder}/json/failure-test.json` | Failed testcases only (updated during the run). Linked from suite HTML. |
| `{reportRoot}/{runFolder}/failure-test.json` | Copy of the same file when the suite **completes**. |
| `{reportRoot}/failure-test.json` | Latest completed run’s failures (overwritten each time a suite finishes). |

Run folder naming (Cucumber today): `dd-MM-yyyy HH-mm-ss Merv-Report`. Other frameworks should use a **single directory segment** name and the same relative layout under it (`html/`, `json/`).

## Shared Java modules (`org.teche.merv.client.report.html`)

| Type | Responsibility |
|------|----------------|
| `MervReportBranding` | Logo URL, gradient CSS, stale-run threshold for live UI. |
| `MervHtmlEscape` | Escaping user-controlled text in generated HTML. |
| `MervReportsIndexHtmlWriter` | Builds/refreshes **`index.html`**. Call **`write(String reportRoot)`** after JSON exists or after deleting a run folder. |

**Do not** reimplement `index.html` inside a TestNG/JUnit listener; call `MervReportsIndexHtmlWriter.write(...)`.

## JSON contract (`merv-report.json`)

The dashboard and `MervReportsIndexHtmlWriter` expect a JSON document compatible with what the Cucumber local path produces. At minimum:

- **Root**
  - `running` (boolean) — in-flight vs completed.
  - `lastActivityMillis` (number, optional) — used for “aborted” detection when updates stop.
  - `exportDate` (string, optional) — shown in live UI.
  - `testSuite` (object)
    - `title` (string)
    - `testCases` (array)
      - `testcaseName`, `status` (`PASSED` / `FAILED` / `SKIPPED` / `IN_PROGRESS`), `failureReason`, `tags` (array of strings), `startTime` / `endTime`, `executionMachine`, `testSteps` (array with steps, logs, screenshots paths), etc.

For exact fields, align with **`MervCucumberHandler.generateJsonReport`** and runtime snapshots that feed **`merv-report.json`**. The index page also reads each run’s JSON for summary cards (pass/fail/skip, tags).

If a new framework omits optional fields, keep **status** and **testcaseName** correct so counts and navigation behave.

## Integration checklist (new framework)

1. Resolve **`reportRoot`** the same way as Cucumber (`MervConfig`).
2. Create **`{runFolder}/html/`** and **`{runFolder}/json/`** as needed.
3. Write **`merv-report.json`** following the contract above (reuse DTOs from `org.teche.merv.client.dto` where possible instead of ad-hoc maps).
4. Generate suite HTML by **reusing** branding (`MervReportBranding`) and escaping (`MervHtmlEscape`). Prefer extracting shared **suite shell CSS/JS** into new helpers in `report.html` rather than copying `MervCucumberHandler` verbatim.
5. After each meaningful update (or on suite end), call **`MervReportsIndexHtmlWriter.write(reportRoot)`** so **`index.html`** lists the new run and KPI data refreshes.
6. On delete of a run folder, refresh the index the same way (Cucumber uses `refreshReportsIndexListing` → `MervReportsIndexHtmlWriter.write`).

## What remains Cucumber-specific today

Suite-level HTML generation (**live** + **static** `merv-report.html`) is still largely in **`MervCucumberHandler`**. When adding TestNG/JUnit, either:

- Extract shared **suite report** fragments into new classes under `report.html` (recommended), or
- Generate JSON-only first and reuse a thin HTML template that reads JSON (align with live report pattern).

## Related code references

- Index writer: `org.teche.merv.client.report.html.MervReportsIndexHtmlWriter`
- Cucumber handler (suite + JSON): `org.teche.merv.client.plugin.MervCucumberHandler`
- Delete API for dashboard: `org.teche.merv.client.utils.ReportsDeleteServer`

---

**Maintainers:** When you change report HTML, CSS, or JSON shape, update **this file** and the **`package-info.java`** in `report.html` so other frameworks stay aligned.
