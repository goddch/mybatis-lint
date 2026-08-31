package com.mybatislint.analyzer.model;

/** A start/end range within a text file. */
public final class Range {
    private final Position start;
    private final Position end;

    public Range(Position start, Position end) {
        this.start = start;
        this.end = end;
    }

    /** Convenience factory for a zero-width range at a single position. */
    public static Range at(int line, int character) {
        Position position = new Position(line, character);
        return new Range(position, position);
    }

    public Position getStart() {
        return start;
    }

    public Position getEnd() {
        return end;
    }
}
