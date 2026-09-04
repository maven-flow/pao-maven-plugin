package com.jardoapps.pao;

import java.util.regex.Pattern;

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
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        regex.append("\\[");
                    } else {
                        String body = glob.substring(i + 1, close);
                        if (body.startsWith("!")) {
                            body = "^" + body.substring(1);
                        }
                        regex.append('[').append(body).append(']');
                        i = close;
                    }
                }
                default -> {
                    if ("\\.^$+{}|()".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
            i++;
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
