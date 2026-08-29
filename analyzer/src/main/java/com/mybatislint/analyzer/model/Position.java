package com.mybatislint.analyzer.model;

/**
 * A single position within a text file, using 1-based line numbers and
 * 1-based character (column) offsets, matching common editor conventions.
 */
public final class Position {
    private final int line;
    private final int character;

    public Position(int line, int character) {
        this.line = line;
        this.character = character;
    }

    public int getLine() {
        return line;
    }

    public int getCharacter() {
        return character;
    }
}
