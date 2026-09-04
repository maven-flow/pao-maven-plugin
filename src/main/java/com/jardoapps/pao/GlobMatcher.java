package com.jardoapps.pao;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Glob matching for branch patterns, mirroring bash {@code [[ $branch == $pattern ]]}:
 * {@code *} matches any run of characters including {@code /}, {@code ?} matches a
 * single character, and {@code [...]} is a character class.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static boolean matches(String pattern, String value) {
        return toRegex(pattern).matcher(value).matches();
    }

    static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 16);
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> i = appendCharacterClass(regex, glob, i);
                default -> {
                    if ("\\.^$+{}|()".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
            i++;
        }
        try {
            return Pattern.compile(regex.toString(), Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            // These patterns come from user input, so the pattern at fault has to be
            // named - an unadorned PatternSyntaxException says nothing about which
            // branch pattern or config row produced it.
            throw new PaoException("Invalid branch pattern '" + glob + "': " + e.getDescription(), e);
        }
    }

    /**
     * Appends the class starting at {@code open}, and returns the index of its closing
     * bracket so the caller can continue after it. An unmatched {@code [} is a literal,
     * as it is in bash.
     */
    private static int appendCharacterClass(StringBuilder regex, String glob, int open) {
        int bodyStart = open + 1;
        boolean negated = bodyStart < glob.length()
                && (glob.charAt(bodyStart) == '!' || glob.charAt(bodyStart) == '^');
        if (negated) {
            bodyStart++;
        }

        // A ']' in the first position is a literal - bash's way of writing a class that
        // contains one - so it cannot be the terminator.
        int searchFrom = bodyStart < glob.length() && glob.charAt(bodyStart) == ']' ? bodyStart + 1 : bodyStart;
        int close = glob.indexOf(']', searchFrom);
        if (close < 0) {
            regex.append("\\[");
            return open;
        }

        regex.append('[');
        if (negated) {
            regex.append('^');
        }
        appendClassBody(regex, glob, bodyStart, close);
        regex.append(']');
        return close;
    }

    /**
     * Copies a class body across, escaping what Java reads differently from bash. A
     * bare '&' pairs up into Java's intersection operator, '\' and '[' change the
     * meaning of what follows, and '^' would negate if it landed first. '-' is left
     * alone, so ranges keep working and a leading or trailing '-' stays literal in both.
     */
    private static void appendClassBody(StringBuilder regex, String glob, int start, int end) {
        for (int j = start; j < end; j++) {
            char c = glob.charAt(j);
            if (c == '\\' || c == '[' || c == ']' || c == '&' || c == '^') {
                regex.append('\\');
            }
            regex.append(c);
        }
    }
}
