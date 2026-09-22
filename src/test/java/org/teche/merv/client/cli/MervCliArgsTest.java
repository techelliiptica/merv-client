package org.teche.merv.client.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MervCliArgsTest {

    @Test
    void parsesShowReport() {
        MervCliArgs args = MervCliArgs.parse(new String[]{"show-report", "./merv-reports", "--port", "8080", "--no-open"});
        assertEquals(MervCliArgs.Command.SHOW_REPORT, args.getCommand());
        assertEquals("./merv-reports", args.getReportDir());
        assertEquals(8080, args.getPort());
        assertFalse(args.isOpenBrowser());
    }

    @Test
    void parsesShowLogs() {
        MervCliArgs args = MervCliArgs.parse(new String[]{"show-logs", "--host", "0.0.0.0"});
        assertEquals(MervCliArgs.Command.SHOW_LOGS, args.getCommand());
        assertEquals("0.0.0.0", args.getHost());
    }

    @Test
    void parsesDoctorSetConsole() {
        MervCliArgs args = MervCliArgs.parse(new String[]{"doctor", "set", "console"});
        assertEquals(MervCliArgs.Command.DOCTOR_SET_CONSOLE, args.getCommand());
    }

    @Test
    void parsesDoctorUnsetConsole() {
        MervCliArgs args = MervCliArgs.parse(new String[]{"doctor", "unset", "console"});
        assertEquals(MervCliArgs.Command.DOCTOR_UNSET_CONSOLE, args.getCommand());
    }

    @Test
    void parsesDoctorSetupLog() {
        MervCliArgs args = MervCliArgs.parse(new String[]{"doctor", "setup-log", "--force"});
        assertEquals(MervCliArgs.Command.DOCTOR_SETUP_LOG, args.getCommand());
        assertTrue(args.isForce());
    }

    @Test
    void parsesDoctorSetup() {
        MervCliArgs args = MervCliArgs.parse(new String[]{"doctor", "setup", "--suite-title", "API Suite"});
        assertEquals(MervCliArgs.Command.DOCTOR_SETUP, args.getCommand());
        assertEquals("API Suite", args.getSuiteTitle());
    }
}
