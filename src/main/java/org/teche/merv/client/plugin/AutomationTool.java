package org.teche.merv.client.plugin;

/**
 * Automation frameworks whose driver/page object can be passed to
 * {@link MervCucumberHandler#setAutomationToolObject(AutomationTool, Object)} for optional per-step screenshots.
 */
public enum AutomationTool {

    /** Selenium {@code WebDriver} implementing {@code TakesScreenshot}. */
    SELENIUM,

    /** Playwright Java {@code com.microsoft.playwright.Page}. */
    PLAYWRIGHT,

    /**
     * Same capture path as Selenium when the underlying object is a {@code WebDriver}
     * (e.g. JS Protractor setups are not driven from this JVM).
     */
    PROTRACTOR,

    /** Appium drivers that implement Selenium {@code TakesScreenshot}. */
    APPIUM,

    /**
     * Reserved for WebdriverIO; from Java use Selenium protocol drivers when applicable,
     * otherwise capture may not be available.
     */
    WEBDRIVERIO,

    /** Reserved for Cypress; screenshot from Java is usually not available. */
    CYPRESS,

    /**
     * Attempts Selenium-style capture first, then Playwright {@code Page.screenshot}.
     */
    AUTO
}
