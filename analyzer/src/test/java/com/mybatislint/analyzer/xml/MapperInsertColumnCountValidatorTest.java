package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** INSERT の列の数と、VALUES / SELECT の値の数。 */
class MapperInsertColumnCountValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void countsColumnsAndValuesWithoutBeingFooledByCommasInsideValues() {
        assertArrayEquals(new int[] {3, 3, 1}, MapperInsertColumnCountValidator.countColumnsAndValues("""
                INSERT INTO orders (ORDER_ID, AMOUNT, NOTE)
                VALUES (#{orderId,jdbcType=NUMERIC}, NVL(#{amount}, 0), 'a, b') -- , c
                """));
        assertArrayEquals(new int[] {3, 2, 2}, MapperInsertColumnCountValidator.countColumnsAndValues("""
                INSERT INTO orders_history (ORDER_ID, AMOUNT, CREATED_BY)
                SELECT DISTINCT o.ORDER_ID, SUM(o.AMOUNT) /* , x */
                  FROM orders o, (SELECT 1 FROM dual) d
                """));
    }

    @Test
    void cannotCountWhatIsNotWrittenOut() {
        // 列の一覧がない、途中に <if> などがある(先頭のテキストで閉じていない)、* や ${} がある。
        assertNull(MapperInsertColumnCountValidator.countColumnsAndValues("INSERT INTO orders VALUES (1, 2)"));
        assertNull(MapperInsertColumnCountValidator.countColumnsAndValues("INSERT INTO orders (A, B) VALUES (1, "));
        assertNull(MapperInsertColumnCountValidator.countColumnsAndValues("INSERT INTO orders (A, B) SELECT * FROM t"));
        assertNull(MapperInsertColumnCountValidator.countColumnsAndValues("INSERT INTO orders (A, B) SELECT ${cols} FROM t"));
        assertNull(MapperInsertColumnCountValidator.countColumnsAndValues("INSERT INTO orders (A, B) SELECT a, b"));
    }

    @Test
    void reportsAMismatchAtTheInsertKeyword() throws IOException {
        Path file = writeMapper("""
                <insert id="insert">
                    INSERT INTO orders (ORDER_ID, AMOUNT, NOTE)
                    VALUES (#{orderId}, #{amount})
                </insert>
                <insert id="insertDynamic">
                    INSERT INTO orders (ORDER_ID, AMOUNT
                    <if test="note != null">, NOTE</if>)
                    VALUES (#{orderId})
                </insert>
                <insert id="insertMatching">
                    INSERT INTO orders (ORDER_ID) SELECT MAX(ORDER_ID) + 1 FROM orders
                </insert>
                """);

        List<Diagnostic> diagnostics = new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.INSERT_COLUMN_COUNT.equals(d.getRuleId()))
                .toList();
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        Diagnostic error = diagnostics.get(0);
        assertEquals(Severity.ERROR, error.getSeverity());
        assertEquals("INSERT lists 3 column(s) but VALUES has 2 value(s).", error.getMessage());
        assertEquals(4, error.getRange().getStart().getLine());
        assertEquals(Files.readAllLines(file).get(3).indexOf("INSERT") + 1, error.getRange().getStart().getCharacter());
    }

    private Path writeMapper(String statements) throws IOException {
        Path file = workspace.resolve("src/main/resources/com/example/OrderMapper.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"com.example.OrderMapper\">\n"
                + statements + "</mapper>\n");
        return file;
    }
}
