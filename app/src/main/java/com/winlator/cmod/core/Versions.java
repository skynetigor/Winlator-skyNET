package com.winlator.cmod.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Dotted version numbers ("0.39.4.0", "2.4", "r9"): compared part by part, the numeric parts as numbers and a missing
 * numeric part as 0, so that 0.39 and 0.39.0.0 are the same version and 0.39.4.0 is between 0.39 and 0.40.
 */
public final class Versions {
    private Versions() {}

    /** Negative, zero or positive as {@code a} is older than, the same as or newer than {@code b}. */
    public static int compare(String a, String b) {
        List<String> left = parts(a);
        List<String> right = parts(b);
        int count = Math.max(left.size(), right.size());
        for (int i = 0; i < count; i++) {
            String x = i < left.size() ? left.get(i) : "0";
            String y = i < right.size() ? right.get(i) : "0";
            int result = comparePart(x, y);
            if (result != 0) return result;
        }
        return 0;
    }

    /** True when {@code from <= version < until}; an empty or null bound is no bound. */
    public static boolean inRange(String version, String from, String until) {
        if (version == null || version.isEmpty()) return false;
        if (from != null && !from.isEmpty() && compare(version, from) < 0) return false;
        if (until != null && !until.isEmpty() && compare(version, until) >= 0) return false;
        return true;
    }

    private static int comparePart(String x, String y) {
        boolean xNumber = isNumber(x);
        boolean yNumber = isNumber(y);
        if (xNumber && yNumber) {
            // Numbers of any length: by length once the leading zeros are gone, then digit by digit.
            String p = strip(x);
            String q = strip(y);
            if (p.length() != q.length()) return p.length() - q.length();
            return p.compareTo(q);
        }
        // A number sorts before a word; only a stable order between the two matters.
        if (xNumber) return -1;
        if (yNumber) return 1;
        return x.compareToIgnoreCase(y);
    }

    private static boolean isNumber(String part) {
        if (part.isEmpty()) return false;
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) return false;
        }
        return true;
    }

    private static String strip(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;
        return digits.substring(start);
    }

    /** "0.39.4.0" -> [0, 39, 4, 0]; "r9" -> [r, 9]; separators are anything that is not a letter or a digit. */
    private static List<String> parts(String version) {
        List<String> parts = new ArrayList<>();
        if (version == null) return parts;
        StringBuilder current = new StringBuilder();
        boolean digits = false;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(parts, current);
                continue;
            }
            boolean isDigit = Character.isDigit(c);
            if (current.length() > 0 && isDigit != digits) flush(parts, current);
            digits = isDigit;
            current.append(c);
        }
        flush(parts, current);
        return parts;
    }

    private static void flush(List<String> parts, StringBuilder current) {
        if (current.length() == 0) return;
        parts.add(current.toString());
        current.setLength(0);
    }
}
