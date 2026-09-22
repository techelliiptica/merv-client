package org.teche.merv.client.cli;

import java.util.List;

/** Simple doctor progress output for the terminal. */
final class MervDoctorConsole {

    private MervDoctorConsole() {
    }

    static void printReport(String title, String subtitle, List<MervDoctorStep> steps) {
        System.out.println();
        System.out.println("╭──────────────────────────────────────────────╮");
        System.out.printf("│  %-42s │%n", title);
        System.out.println("╰──────────────────────────────────────────────╯");
        if (subtitle != null && !subtitle.isBlank()) {
            System.out.println("  " + subtitle);
        }
        System.out.println();

        int n = steps.size();
        int i = 0;
        boolean failed = false;
        for (MervDoctorStep s : steps) {
            i++;
            int pct = n > 0 ? (i * 100) / n : 100;
            String bar = progressBar(pct);
            String icon = switch (s.getAction()) {
                case CREATED -> "✓ created";
                case UPDATED -> "✓ corrected";
                case FAILED -> "✗ failed";
                default -> "○ ok";
            };
            if (s.getAction() == MervDoctorStep.Action.FAILED) {
                failed = true;
            }
            System.out.printf("  [%s] %3d%%  %-12s  %s", bar, pct, icon, s.getStep());
            if (s.getDetail() != null && !s.getDetail().isBlank()) {
                System.out.print("  (" + s.getDetail() + ")");
            }
            System.out.println();
        }
        System.out.println();
        if (failed) {
            System.out.println("  ✗ Doctor finished with errors.");
        } else {
            System.out.println("  ✓ Doctor setup finished successfully.");
        }
        System.out.println();
    }

    private static String progressBar(int pct) {
        int filled = Math.max(0, Math.min(12, pct * 12 / 100));
        return "█".repeat(filled) + "░".repeat(12 - filled);
    }
}
