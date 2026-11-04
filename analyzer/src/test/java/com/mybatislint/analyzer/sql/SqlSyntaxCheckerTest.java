package com.mybatislint.analyzer.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSyntaxCheckerTest {

    private static final SqlSyntaxChecker CHECKER = new SqlSyntaxChecker();

    @AfterAll
    static void close() {
        CHECKER.close();
    }

    // Oracle では列名や別名に使えるが、JSqlParser が予約語として扱う語。「.」の位置で読めなくなっていた。
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A.FINAL, A.OUTPUT, A.STATEMENT FROM T A",
            "SELECT A.SAMPLE FROM T A WHERE A.ERRORS = ?",
            "SELECT INT.ID FROM T INT",
            "SELECT MAP.ID, MAP.NM FROM T MAP WHERE MAP.ID = ? ORDER BY MAP.NM",
            "UPDATE T A SET A.FINAL = ? WHERE A.ID = ?",
            "SELECT S.PKG.FN(A.OUTPUT) FROM T A",
    })
    void doesNotReportNamesThatOracleAllows(String sql) {
        SqlSyntaxChecker.Result result = CHECKER.check(sql, SqlDialect.ORACLE);
        assertTrue(result.checked());
        assertNull(result.error(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A.FINAL FORM T A",
            "SELECT A.ID A.NM FROM T A",
            "SELECT A.OUTPUT FROM T A WHERE (A.ID = ?",
    })
    void stillReportsRealErrorsNextToSuchNames(String sql) {
        assertNotNull(CHECKER.check(sql, SqlDialect.ORACLE).error(), sql);
    }

    // JSqlParser は ?| を PostgreSQL の JSON 演算子として読むので、詰めて書いた連結が読めなくなっていた。
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM T WHERE X LIKE '%'||?||'%'",
            "SELECT * FROM T WHERE X LIKE ?||'%'",
            "SELECT * FROM T WHERE X = ?||?",
            "SELECT ?||A.X FROM T A",
            "SELECT F(?||'x', ?||'y') FROM T",
            "INSERT INTO T (A) VALUES (?||'x')",
            "UPDATE T SET A=?||'x' WHERE B=?",
            "SELECT * FROM T WHERE (A.N LIKE '%'||?||'%' OR\n A.C LIKE '%'||?||'%')",
    })
    void readsParametersConcatenatedWithoutSpaces(String sql) {
        for (SqlDialect dialect : SqlDialect.values()) {
            SqlSyntaxChecker.Result result = CHECKER.check(sql, dialect);
            assertTrue(result.checked());
            assertNull(result.error(), dialect + ": " + sql);
        }
    }

    @Test
    void stillReportsErrorsNextToConcatenatedParameters() {
        SqlSyntaxChecker.SyntaxError error =
                CHECKER.check("SELECT * FROM T WHERE A.IDLIKE '%'||?||'%'", SqlDialect.ORACLE).error();
        assertEquals("'%'", error.near());
        assertEquals(32, error.column());
    }

    @Test
    void showsTheOriginalParameterWhenTheErrorIsAtIt() {
        SqlSyntaxChecker.SyntaxError error = CHECKER.check("SELECT 1 FROM T WHERE\nA ?||'x' = 1", SqlDialect.ORACLE).error();
        assertEquals("?", error.near());
        assertEquals(2, error.line());
    }

    @Test
    void keepsPostgresqlJsonOperatorsAndText() {
        assertEquals("SELECT A ?| B, A ?& B, 0||'x'",
                SqlSyntaxChecker.separateParameters("SELECT A ?| B, A ?& B, ?||'x'", SqlDialect.POSTGRESQL));
        assertEquals("SELECT 0|1, 0&1", SqlSyntaxChecker.separateParameters("SELECT ?|1, ?&1", SqlDialect.MYSQL));
        assertEquals("SELECT '?||' /* ?|| */ FROM T",
                SqlSyntaxChecker.separateParameters("SELECT '?||' /* ?|| */ FROM T", SqlDialect.ORACLE));
    }

    // Oracle では正しいが JSqlParser が読めない書き方は、読まなかったものとして扱う。
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A.ID FROM T PARTITION (P1) A",
            "SELECT A.ID FROM T PARTITION FOR (1) A",
            "SELECT A.ID FROM T SUBPARTITION (P1) A",
            "SELECT A.ID FROM T A PARTITION BY (A.X) RIGHT OUTER JOIN U B ON A.ID = B.ID",
            "SELECT A.ID FROM T AS OF TIMESTAMP SYSTIMESTAMP A",
            "SELECT A.ID FROM T AS OF SCN 12345 A",
            "SELECT A.ID FROM T VERSIONS BETWEEN SCN MINVALUE AND MAXVALUE A",
            "SELECT A.ID FROM T SAMPLE (10) A",
            "SELECT A.ID FROM T SAMPLE BLOCK (10) SEED (1) A",
            "SELECT * FROM T MODEL DIMENSION BY (A) MEASURES (B) RULES (B[1] = 1)",
            "SELECT * FROM T MATCH_RECOGNIZE (PARTITION BY A ORDER BY B MEASURES C AS C PATTERN (X+) DEFINE X AS X.V > 1)",
            "WITH FUNCTION F(N NUMBER) RETURN NUMBER IS BEGIN RETURN N; END; SELECT F(1) FROM DUAL",
            "SELECT TREAT(A.X AS Y) FROM T A",
            "SELECT NEW OBJ(1, 2) FROM DUAL",
            "SELECT A.ID FROM T A WHERE A.X IS NAN",
            "SELECT A.ID FROM T A WHERE A.X IS OF TYPE (Y)",
            "SELECT A.ID FROM T A WHERE A.X LIKEC 'a'",
            "INSERT INTO (SELECT ID FROM T) VALUES (?)",
            "INSERT INTO T (ID) VALUES (?) LOG ERRORS INTO E REJECT LIMIT UNLIMITED",
            "UPDATE (SELECT A.X, B.Y FROM T A, U B WHERE A.ID = B.ID) SET X = Y",
            "UPDATE T A SET A.X = ? WHERE CURRENT OF C",
            "DELETE FROM (SELECT * FROM T) WHERE ID = ?",
            "MERGE INTO T A USING U B ON (A.ID = B.ID) WHEN NOT MATCHED THEN INSERT (ID) VALUES (B.ID) LOG ERRORS",
    })
    void skipsOracleSyntaxTheParserCannotRead(String sql) {
        SqlSyntaxChecker.Result result = CHECKER.check(sql, SqlDialect.ORACLE);
        assertNull(result.error(), sql);
        assertFalse(result.checked(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT SUM(A.X) OVER (PARTITION BY A.Y) FORM T A",
            "SELECT SUM(A.X) OVER ( PARTITION BY A.Y) A.Z FROM T A",
            "UPDATE T SET X = ? WHERE",
            "SELECT A.SAMPLE A.ID FROM T A",
    })
    void stillReportsErrorsWhenThoseWordsAreNotThatSyntax(String sql) {
        assertNotNull(CHECKER.check(sql, SqlDialect.ORACLE).error(), sql);
    }

    @Test
    void otherDatabasesKeepReportingOracleOnlySyntax() {
        assertNotNull(CHECKER.check("SELECT A.ID FROM T AS OF SCN 12345 A", SqlDialect.POSTGRESQL).error());
    }

    @Test
    void quotesOnlyDottedNamesOutsideStringsAndComments() {
        assertEquals("SELECT \"A\".\"FINAL\", 'x.y', \"B\".\"C\" /* d.e */ FROM T A -- f.g\nWHERE 1.5 > ?",
                SqlSyntaxChecker.quoteDottedNames(
                        "SELECT A.FINAL, 'x.y', \"B\".\"C\" /* d.e */ FROM T A -- f.g\nWHERE 1.5 > ?"));
        assertEquals("SELECT \"A\".* FROM \"S\".\"T\" A", SqlSyntaxChecker.quoteDottedNames("SELECT A.* FROM S.T A"));
    }
}
