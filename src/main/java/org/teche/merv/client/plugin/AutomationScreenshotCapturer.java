package org.teche.merv.client.plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;

/**
 * Captures screenshots via reflection so {@code merv-client} does not require Selenium or Playwright on the classpath.
 * At runtime, the matching driver libraries must be present for capture to succeed.
 */
final class AutomationScreenshotCapturer {

    private AutomationScreenshotCapturer() {
    }

    /**
     * @return a temporary PNG file, or {@code null} if capture is not possible or libraries are missing
     */
    static File captureToTempPng(AutomationTool tool, Object driverOrPage) {
        if (driverOrPage == null) {
            return null;
        }
        try {
            switch (tool) {
                case SELENIUM:
                case PROTRACTOR:
                case APPIUM:
                    return captureSeleniumStyle(driverOrPage);
                case PLAYWRIGHT:
                    return capturePlaywright(driverOrPage);
                case WEBDRIVERIO:
                    File f = captureSeleniumStyle(driverOrPage);
                    return f != null ? f : capturePlaywright(driverOrPage);
                case CYPRESS:
                    return null;
                case AUTO:
                    File a = captureSeleniumStyle(driverOrPage);
                    if (a != null) {
                        return a;
                    }
                    return capturePlaywright(driverOrPage);
                default:
                    return null;
            }
        } catch (Throwable t) {
            System.err.println("MERV: step screenshot capture failed (" + tool + "): " + t.getMessage());
            return null;
        }
    }

    private static File captureSeleniumStyle(Object driver) throws Exception {
        Class<?> takesClass = Class.forName("org.openqa.selenium.TakesScreenshot");
        if (!takesClass.isInstance(driver)) {
            return null;
        }
        Class<?> outputTypeClass = Class.forName("org.openqa.selenium.OutputType");
        Object fileOutputType = outputTypeClass.getField("FILE").get(null);
        Method getScreenshotAs = takesClass.getMethod("getScreenshotAs", outputTypeClass);
        Object out = getScreenshotAs.invoke(driver, fileOutputType);
        if (out instanceof File) {
            return (File) out;
        }
        return null;
    }

    private static File capturePlaywright(Object pageObj) throws Exception {
        Class<?> pageClass = Class.forName("com.microsoft.playwright.Page");
        if (!pageClass.isInstance(pageObj)) {
            return null;
        }
        byte[] bytes = null;
        try {
            Method noArg = pageClass.getMethod("screenshot");
            bytes = (byte[]) noArg.invoke(pageObj);
        } catch (NoSuchMethodException ignored) {
            Class<?> optClass = Class.forName("com.microsoft.playwright.Page$ScreenshotOptions");
            Object opts = optClass.getDeclaredConstructor().newInstance();
            Method withMethod = pageClass.getMethod("screenshot", optClass);
            bytes = (byte[]) withMethod.invoke(pageObj, opts);
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        File tmp = File.createTempFile("merv-playwright-", ".png");
        Files.write(tmp.toPath(), bytes);
        return tmp;
    }
}
