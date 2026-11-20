package dev.rinchan.structuredfucache.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal zero-dependency TOML parser for flat boolean, integer, and string configuration files.
 */
public final class SimpleToml {
    private SimpleToml() {
    }

    public static Map<String, Object> parse(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) {
            return result;
        }

        int i = 0;
        int len = content.length();
        while (i < len) {
            i = skipWhitespaceAndComments(content, i);
            if (i >= len) {
                break;
            }
            if (content.charAt(i) == '[') {
                i = skipToNextLine(content, i);
                continue;
            }

            int keyStart = i;
            while (i < len && content.charAt(i) != '=' && content.charAt(i) != '\n' && content.charAt(i) != '\r') {
                i++;
            }
            if (i >= len || content.charAt(i) != '=') {
                i = skipToNextLine(content, i);
                continue;
            }
            String key = content.substring(keyStart, i).trim();
            i++; // consume '='
            i = skipInlineWhitespace(content, i);
            if (i >= len || key.isEmpty()) {
                break;
            }

            char c = content.charAt(i);
            if (c == '"' || c == '\'') {
                int[] next = new int[1];
                result.put(key, readString(content, i, next));
                i = next[0];
            } else {
                int valStart = i;
                while (i < len && content.charAt(i) != '#' && content.charAt(i) != '\n' && content.charAt(i) != '\r') {
                    i++;
                }
                String raw = content.substring(valStart, i).trim();
                if ("true".equalsIgnoreCase(raw)) {
                    result.put(key, Boolean.TRUE);
                } else if ("false".equalsIgnoreCase(raw)) {
                    result.put(key, Boolean.FALSE);
                } else if (!raw.isEmpty()) {
                    try {
                        result.put(key, Integer.parseInt(raw));
                    } catch (NumberFormatException ignored) {
                        result.put(key, raw);
                    }
                }
            }
            i = skipToNextLine(content, i);
        }
        return result;
    }

    private static String readString(String s, int start, int[] nextOut) {
        char quote = s.charAt(start);
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (quote == '"' && c == '\\' && i + 1 < len) {
                char esc = s.charAt(i + 1);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(esc);
                }
                i += 2;
                continue;
            }
            if (c == quote) {
                i++;
                break;
            }
            if (c == '\n' || c == '\r') {
                break;
            }
            sb.append(c);
            i++;
        }
        nextOut[0] = i;
        return sb.toString();
    }

    private static int skipWhitespaceAndComments(String s, int i) {
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else if (c == '#') {
                i = skipToNextLine(s, i);
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipInlineWhitespace(String s, int i) {
        int len = s.length();
        while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static int skipToNextLine(String s, int i) {
        int len = s.length();
        while (i < len && s.charAt(i) != '\n') {
            i++;
        }
        return i < len ? i + 1 : len;
    }
}
