package com.mybatislint.analyzer.model;

import java.util.Objects;

/**
 * A diagnostic message in English and Japanese.
 *
 * <p>どちらの言語で出すかは JSON に書き出すときに決める。validator に言語を渡して回らなくて
 * 済むように、診断は両方の文を持っておく。サーバーモードでは要求ごとに言語が変わりうる。
 */
public record Message(String en, String ja) {

    public Message {
        Objects.requireNonNull(en, "en");
        Objects.requireNonNull(ja, "ja");
    }

    public static Message of(String en, String ja) {
        return new Message(en, ja);
    }

    /** 2 つの文をつなげる。「もしかして」の候補などを後ろに足すときに使う。 */
    public Message plus(Message other) {
        return new Message(en + other.en, ja + other.ja);
    }

    public String in(Language language) {
        return language == Language.JA ? ja : en;
    }
}
