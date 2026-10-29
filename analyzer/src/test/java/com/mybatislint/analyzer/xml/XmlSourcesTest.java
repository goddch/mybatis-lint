package com.mybatislint.analyzer.xml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 解析 1 回の間だけ、同じ XML を読み直さない。 */
class XmlSourcesTest {

    @TempDir
    Path workspace;

    @Test
    void reusesTheDocumentOnlyInsideTheScope() throws Exception {
        Path file = Files.writeString(workspace.resolve("UserMapper.xml"), "<mapper namespace=\"a.B\"/>");

        try (XmlSources.Scope ignored = XmlSources.cached()) {
            assertSame(XmlSources.parse(file), XmlSources.parse(file));
            assertSame(XmlSources.readString(file), XmlSources.readString(file));
        }
        assertNotSame(XmlSources.parse(file), XmlSources.parse(file));
    }

    @Test
    void rethrowsTheSameErrorForABrokenFile() throws Exception {
        Path file = Files.writeString(workspace.resolve("Broken.xml"), "<mapper namespace=\"a.B\">");

        try (XmlSources.Scope ignored = XmlSources.cached()) {
            SAXException first = assertThrows(SAXException.class, () -> XmlSources.parse(file));
            SAXException second = assertThrows(SAXException.class, () -> XmlSources.parse(file));
            assertSame(first, second);
            // 文字列としては読めるので、壊れた mapper の namespace を探すのに使える。
            assertEquals("<mapper namespace=\"a.B\">", XmlSources.readString(file));
        }
    }
}
