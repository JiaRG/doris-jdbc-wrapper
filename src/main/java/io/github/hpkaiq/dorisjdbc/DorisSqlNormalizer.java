package io.github.hpkaiq.dorisjdbc;

final class DorisSqlNormalizer {
    private static final String REWRITE_DOUBLE_QUOTED_IDENTIFIERS_PROPERTY =
            "doris.jdbc.rewrite.double.quoted.identifiers";

    private DorisSqlNormalizer() {
    }

    static String normalize(String sql) {
        if (sql == null || sql.indexOf('"') < 0 || !rewriteDoubleQuotedIdentifiers()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean changed = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                i = appendSingleQuoted(sql, out, i);
            } else if (ch == '`') {
                i = appendBacktickQuoted(sql, out, i);
            } else if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = appendLineComment(sql, out, i);
            } else if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = appendBlockComment(sql, out, i);
            } else if (ch == '"') {
                int end = findDoubleQuoteEnd(sql, i + 1);
                if (end < 0) {
                    out.append(ch);
                } else {
                    out.append('`');
                    appendBacktickEscaped(out, sql, i + 1, end);
                    out.append('`');
                    i = end;
                    changed = true;
                }
            } else {
                out.append(ch);
            }
        }
        return changed ? out.toString() : sql;
    }

    private static boolean rewriteDoubleQuotedIdentifiers() {
        return Boolean.parseBoolean(System.getProperty(REWRITE_DOUBLE_QUOTED_IDENTIFIERS_PROPERTY, "true"));
    }

    private static int appendSingleQuoted(String sql, StringBuilder out, int start) {
        out.append('\'');
        for (int i = start + 1; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            out.append(ch);
            if (ch == '\\' && i + 1 < sql.length()) {
                out.append(sql.charAt(++i));
            } else if (ch == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append(sql.charAt(++i));
                } else {
                    return i;
                }
            }
        }
        return sql.length() - 1;
    }

    private static int appendBacktickQuoted(String sql, StringBuilder out, int start) {
        out.append('`');
        for (int i = start + 1; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            out.append(ch);
            if (ch == '`') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    out.append(sql.charAt(++i));
                } else {
                    return i;
                }
            }
        }
        return sql.length() - 1;
    }

    private static int appendLineComment(String sql, StringBuilder out, int start) {
        out.append('-').append('-');
        int i = start + 2;
        for (; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            out.append(ch);
            if (ch == '\n' || ch == '\r') {
                return i;
            }
        }
        return sql.length() - 1;
    }

    private static int appendBlockComment(String sql, StringBuilder out, int start) {
        out.append('/').append('*');
        int i = start + 2;
        for (; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            out.append(ch);
            if (ch == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                out.append('/');
                return i + 1;
            }
        }
        return sql.length() - 1;
    }

    private static int findDoubleQuoteEnd(String sql, int start) {
        for (int i = start; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '"') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void appendBacktickEscaped(StringBuilder out, String sql, int start, int end) {
        for (int i = start; i < end; i++) {
            char ch = sql.charAt(i);
            if (ch == '"' && i + 1 < end && sql.charAt(i + 1) == '"') {
                out.append('"');
                i++;
            } else if (ch == '`') {
                out.append("``");
            } else {
                out.append(ch);
            }
        }
    }
}
