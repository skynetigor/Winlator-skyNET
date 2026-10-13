package com.winlator.cmod.linux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shortcut's launch options, written the way Steam's are:
 *
 * <pre>
 *   MANGOHUD=1 BOX64_LOG=1 %command% -windowed -width 1280
 * </pre>
 *
 * What stands before {@code %command%} is variables (NAME=value) and, after them, a wrapper that starts the program
 * (taskset -c 4-7 %command%); what stands after it is the program's arguments. Without {@code %command%} the leading
 * NAME=value words are variables and everything else is arguments. Single and double quotes and a backslash keep a
 * space inside a word.
 */
public final class LinuxLaunchOptions {
    /** The shortcut's extra (a line of its .desktop file) that holds the text. */
    public static final String EXTRA = "launchOptions";
    private static final String COMMAND = "%command%";

    public final Map<String, String> env = new LinkedHashMap<>();
    public final List<String> wrapper = new ArrayList<>();
    public final List<String> args = new ArrayList<>();

    private LinuxLaunchOptions() {}

    /**
     * What a game's profile says laid under what the shortcut says: the variables of both (the shortcut's win), the profile's
     * arguments and then the shortcut's, and the shortcut's wrapper.
     */
    public static LinuxLaunchOptions merged(Map<String, String> profileEnv, List<String> profileArgs, LinuxLaunchOptions own) {
        LinuxLaunchOptions merged = new LinuxLaunchOptions();
        merged.env.putAll(profileEnv);
        merged.env.putAll(own.env);
        merged.wrapper.addAll(own.wrapper);
        merged.args.addAll(profileArgs);
        merged.args.addAll(own.args);
        return merged;
    }

    public static LinuxLaunchOptions parse(String text) {
        LinuxLaunchOptions options = new LinuxLaunchOptions();
        if (text == null || text.trim().isEmpty()) return options;
        List<String> words = split(text);
        int command = words.indexOf(COMMAND);
        int before = command >= 0 ? command : 0;
        int i = 0;
        // Variables first, in either form.
        for (; i < words.size() && (command < 0 || i < command); i++) {
            int equals = assignmentEnd(words.get(i));
            if (equals < 0) break;
            options.env.put(words.get(i).substring(0, equals), words.get(i).substring(equals + 1));
        }
        if (command >= 0) {
            // What is left before %command% starts the program.
            for (; i < command; i++) options.wrapper.add(words.get(i));
            for (i = command + 1; i < words.size(); i++) options.args.add(words.get(i));
        } else {
            for (; i < words.size(); i++) options.args.add(words.get(i));
        }
        return options;
    }

    /** The index of the "=" of a NAME=value word, or -1 when the word is not an assignment. */
    private static int assignmentEnd(String word) {
        int equals = word.indexOf('=');
        if (equals <= 0) return -1;
        if (!(Character.isLetter(word.charAt(0)) || word.charAt(0) == '_')) return -1;
        for (int i = 1; i < equals; i++) {
            char c = word.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return -1;
        }
        return equals;
    }

    /** Splits like a shell would, without expanding anything. */
    static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < text.length() && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\\')) word.append(text.charAt(++i));
                else word.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < text.length()) {
                word.append(text.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) words.add(word.toString());
        return words;
    }
}
