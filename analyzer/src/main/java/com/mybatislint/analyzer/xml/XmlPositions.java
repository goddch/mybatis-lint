package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Position;
import com.mybatislint.analyzer.model.Range;

/**
 * DOM では元の XML 上の位置が分からないため、元の文字列から診断位置を探す処理をまとめたもの。
 * 各 validator が同じ処理をそれぞれ持っていたので、ここに寄せている。
 */
final class XmlPositions {

    private XmlPositions() {
    }

    /**
     * 開始タグ内の属性値の範囲を返す。エディタで値全体に波線を引けるよう、
     * 開始と終了の両方を求める。属性値が見つからなければ要素の先頭を指す幅 0 の範囲にする。
     */
    static Range rangeForAttributeValue(String xml, String attributeName, String attributeValue, int elementStart) {
        int valueStart = findAttributeValueStart(xml, elementStart, attributeName, attributeValue);
        if (valueStart < 0) {
            Position position = toPosition(xml, elementStart);
            return Range.at(position.getLine(), position.getCharacter());
        }
        return range(xml, valueStart, valueStart + attributeValue.length());
    }

    /** {@code start} から {@code end} の直前までの範囲(end は含まない)。 */
    static Range range(String xml, int start, int end) {
        return new Range(toPosition(xml, start), toPosition(xml, Math.max(start, end)));
    }

    static Position toPosition(String xml, int index) {
        int safeIndex = Math.max(0, Math.min(index, xml.length()));
        int line = 1;
        int lastLineStart = 0;
        for (int i = 0; i < safeIndex; i++) {
            if (xml.charAt(i) == '\n') {
                line++;
                lastLineStart = i + 1;
            }
        }
        // column は 1-based。最後に見つけた改行の次の文字を 1 桁目として数えると、
        // 「改行直後の文字は 1 列目」という editor 表示と同じ定義になる。
        int column = safeIndex - lastLineStart + 1;
        return new Position(line, column);
    }

    static int findAttributeValueStart(String xml, int elementStart, String attributeName, String attributeValue) {
        int tagEnd = findStartTagEnd(xml, elementStart);
        if (tagEnd < 0) {
            return -1;
        }
        String startTag = xml.substring(elementStart, tagEnd + 1);
        int attrIndex = startTag.indexOf(attributeName);
        while (attrIndex >= 0) {
            // 単純な indexOf だけだと、たとえば "id" を探したときに "refid" の
            // 末尾にも一致してしまう。XML 属性名としての境界を確認する。
            if (!isAttributeNameBoundaryBefore(startTag, attrIndex)) {
                attrIndex = startTag.indexOf(attributeName, attrIndex + attributeName.length());
                continue;
            }
            int cursor = attrIndex + attributeName.length();
            if (cursor < startTag.length() && isXmlNameChar(startTag.charAt(cursor))) {
                attrIndex = startTag.indexOf(attributeName, attrIndex + attributeName.length());
                continue;
            }
            while (cursor < startTag.length() && Character.isWhitespace(startTag.charAt(cursor))) {
                cursor++;
            }
            if (cursor < startTag.length() && startTag.charAt(cursor) == '=') {
                cursor++;
                while (cursor < startTag.length() && Character.isWhitespace(startTag.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < startTag.length() && (startTag.charAt(cursor) == '"' || startTag.charAt(cursor) == '\'')) {
                    char quote = startTag.charAt(cursor);
                    int valueStartInTag = cursor + 1;
                    int valueEndInTag = startTag.indexOf(quote, valueStartInTag);
                    if (valueEndInTag >= 0 && startTag.substring(valueStartInTag, valueEndInTag).equals(attributeValue)) {
                        return elementStart + valueStartInTag;
                    }
                }
            }
            attrIndex = startTag.indexOf(attributeName, attrIndex + attributeName.length());
        }
        return -1;
    }

    static int findStartTagEnd(String xml, int elementStart) {
        char quote = 0;
        for (int i = elementStart; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAttributeNameBoundaryBefore(String startTag, int attrIndex) {
        if (attrIndex <= 0) {
            return true;
        }
        return !isXmlNameChar(startTag.charAt(attrIndex - 1));
    }

    static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.';
    }
}
