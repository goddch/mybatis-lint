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
