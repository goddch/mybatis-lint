package com.mybatislint.analyzer.model;

/** The language of diagnostic messages ({@code --lang}). */
public enum Language {
    EN,
    JA;

    /** {@code en} / {@code ja}(大文字小文字は問わない)。それ以外は null。 */
    public static Language fromName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "en" -> EN;
            case "ja" -> JA;
            default -> null;
        };
    }
}
