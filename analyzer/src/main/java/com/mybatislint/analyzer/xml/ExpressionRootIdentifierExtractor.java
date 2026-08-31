package com.mybatislint.analyzer.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the top-level/root identifiers from the small OGNL subset supported
 * by this analyzer. It intentionally fails open: if the expression isn't clearly
 * a variable chain, no root names are emitted.
 */
public final class ExpressionRootIdentifierExtractor {

    private static final Set<String> IGNORED_IDENTIFIERS = Set.of(
            "null", "true", "false", "and", "or", "not", "in", "like", "is",
            "as", "case", "when", "then", "else", "end", "new", "this",
            "eq", "ne", "gt", "ge", "lt", "le", "instanceof", "typeof",
            "empty", "size", "contains", "startsWith", "endsWith", "where",
            "trim", "foreach", "if", "choose", "otherwise", "bind"
    );

    private ExpressionRootIdentifierExtractor() {
    }

    public static Set<String> extractRoots(String expression) {
        if (expression == null || expression.isBlank()) {
            return Collections.emptySet();
        }
        String text = expression.trim();
        List<IdentifierToken> tokens = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isJavaIdentifierStart(c) || c == '_' || c == '$') {
                int start = i;
                i++;
                while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '$')) {
                    i++;
                }
                String identifier = text.substring(start, i);
                if (!IGNORED_IDENTIFIERS.contains(identifier)) {
                    int prev = previousSignificantCharacter(text, start);
                    int next = nextSignificantCharacter(text, i);
                    boolean propertyAccess = prev == '.';
                    boolean methodCall = next == '(';
                    tokens.add(new IdentifierToken(identifier, propertyAccess, methodCall));
                }
                continue;
            }
            if (c == '.' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || c == ','
                    || c == ':' || c == ';' || c == '?' || c == '+' || c == '-' || c == '*' || c == '/'
                    || c == '%' || c == '&' || c == '|' || c == '^' || c == '!' || c == '<' || c == '>'
                    || c == '=' || c == '\'' || c == '"' || c == '@') {
                i++;
                continue;
            }
            i++;
        }

        Set<String> roots = new LinkedHashSet<>();
        for (IdentifierToken token : tokens) {
            if (token.propertyAccess || token.methodCall) {
                continue;
            }
            roots.add(token.value);
        }
        return roots;
    }

    private static int previousSignificantCharacter(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char candidate = text.charAt(i);
            if (!Character.isWhitespace(candidate)) {
                return candidate;
            }
        }
        return -1;
    }

    private static int nextSignificantCharacter(String text, int index) {
        for (int i = index; i < text.length(); i++) {
            char candidate = text.charAt(i);
            if (!Character.isWhitespace(candidate)) {
                return candidate;
            }
        }
        return -1;
    }

    private record IdentifierToken(String value, boolean propertyAccess, boolean methodCall) {
    }
}
