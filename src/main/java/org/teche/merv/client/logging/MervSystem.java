package org.teche.merv.client.logging;

/**
 * Drop-in replacement for {@link System#out} print methods — writes to the console
 * and mirrors each line into Merv-Logs (NDJSON under {@code merv.report.folder}/log/).
 *
 * <p>Wire automatically with {@code merv-client doctor set console}.
 */
public final class MervSystem {

    private static final String LOGGER_NAME = "system";

    private MervSystem() {
    }

    public static void println() {
        System.out.println();
        mirror("INFO", "");
    }

    public static void println(Object x) {
        String s = x == null ? "null" : String.valueOf(x);
        System.out.println(s);
        mirror("INFO", s);
    }

    public static void print(Object x) {
        String s = x == null ? "null" : String.valueOf(x);
        System.out.print(s);
        mirror("INFO", s);
    }

    public static void printf(String format, Object... args) {
        String s = args == null || args.length == 0
                ? String.format(format)
                : String.format(format, args);
        System.out.printf(format, args);
        mirror("INFO", s);
    }

    private static void mirror(String level, String msg) {
        try {
            MervLogRecord record = new MervLogRecord();
            record.setTs(java.time.Instant.now().toString());
            record.setLevel(level);
            record.setName(LOGGER_NAME);
            record.setMsg(msg != null ? msg : "");
            MervLogSink.appendMervLogRecord(record);
        } catch (Exception ignored) {
            /* never fail caller */
        }
    }
}
