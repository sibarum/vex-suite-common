package dev.sibarum.suite.update;

import java.util.Locale;

/**
 * Which of two versions is newer, as the installer's own updater decides it ({@code ViCompare} in
 * {@code update.ps1}), so the application and the script never disagree about whether there is an update.
 *
 * <p>A leading {@code v} is dropped; the numbers before any {@code -} or {@code +} are compared as numbers, a missing
 * one counting as 0; with the numbers equal, a prerelease ({@code -rc1}) is older than the release. Two prereleases of
 * one release are equal, as they are to the script. Text that is not a version counts as 0.0.
 */
public final class Versions {

    private Versions() {
    }

    /** Negative if {@code a} is older than {@code b}, positive if newer, 0 if the same. */
    public static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) {
                return c;
            }
        }
        return Boolean.compare(!prerelease(a), !prerelease(b));
    }

    private static String core(String v) {
        String s = v.strip();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int cut = indexOfAny(s, "-+");
        return cut < 0 ? s : s.substring(0, cut);
    }

    private static boolean prerelease(String v) {
        return v.indexOf('-') >= 0;
    }

    private static int[] numbers(String v) {
        String[] parts = core(v).split("\\.", -1);
        // [version] in the script takes two to four numbers; anything else does not parse, and is 0.0.
        if (parts.length > 4) {
            return new int[] {0, 0};
        }
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].toLowerCase(Locale.ROOT);
            if (p.isEmpty() || !p.chars().allMatch(Character::isDigit)) {
                return new int[] {0, 0};
            }
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return new int[] {0, 0};
            }
        }
        return out;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
