package com.mybatislint.analyzer.sql;

import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSyntaxCheckerTest {

    private static final SqlSyntaxChecker CHECKER = new SqlSyntaxChecker();

    private static final Set<String> ORACLE_RESERVED = Set.of(("ACCESS ADD ALL ALTER AND ANY AS ASC AUDIT BETWEEN BY CHAR "
            + "CHECK CLUSTER COLUMN COLUMN_VALUE COMMENT COMPRESS CONNECT CREATE CURRENT DATE DECIMAL DEFAULT DELETE DESC "
            + "DISTINCT DROP ELSE EXCLUSIVE EXISTS FILE FLOAT FOR FROM GRANT GROUP HAVING IDENTIFIED IMMEDIATE IN INCREMENT "
            + "INDEX INITIAL INSERT INTEGER INTERSECT INTO IS LEVEL LIKE LOCK LONG MAXEXTENTS MINUS MLSLABEL MODE MODIFY "
            + "NESTED_TABLE_ID NOAUDIT NOCOMPRESS NOT NOWAIT NULL NUMBER OF OFFLINE ON ONLINE OPTION OR ORDER PCTFREE PRIOR "
            + "PUBLIC RAW RENAME RESOURCE REVOKE ROW ROWID ROWNUM ROWS SELECT SESSION SET SHARE SIZE SMALLINT START "
            + "SUCCESSFUL SYNONYM SYSDATE TABLE THEN TO TRIGGER UID UNION UNIQUE UPDATE USER VALIDATE VALUES VARCHAR "
            + "VARCHAR2 VIEW WHENEVER WHERE WITH").split(" "));

    // SQL の構造を作る語と値。引用符で囲むと、誤りが別名として読めてしまう。
    private static final Set<String> STRUCTURAL = Set.of("BOTH", "TRAILING", "CONNECT_BY_ROOT", "CONSTRAINT", "CROSS",
            "EXCEPT", "FETCH", "FOREIGN", "FULL", "INNER", "JOIN", "LATERAL", "NATURAL", "NOCYCLE", "ONLY", "OUTER",
            "PIVOT", "UNPIVOT", "RETURNING", "SEMI", "UNBOUNDED", "USING", "WHEN", "WINDOW", "IGNORE", "LIMIT", "OFFSET",
            "QUALIFY", "TRUE", "FALSE");

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

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT SETTINGS FROM T",
            "SELECT settings FROM T",
            "SELECT ID, SETTINGS, NM FROM T WHERE SETTINGS = ? ORDER BY SETTINGS",
            "SELECT FINAL, OUTPUT, STATEMENT, ERRORS FROM T",
            "SELECT ID AS SETTINGS FROM T",
            "SELECT A.ID FROM SETTINGS A",
            "INSERT INTO T (SETTINGS) VALUES (?)",
            "UPDATE T SET SETTINGS = ? WHERE ID = ?",
            "SELECT SETTINGS, B.ID FROM T A INNER JOIN U B ON A.ID = B.ID",
    })
    void readsKeywordsThatAreColumnNamesWithoutADot(String sql) {
        for (SqlDialect dialect : SqlDialect.values()) {
            SqlSyntaxChecker.Result result = CHECKER.check(sql, dialect);
            assertTrue(result.checked());
            assertNull(result.error(), dialect + ": " + sql);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT SETTINGS FORM T",
            "SELECT SETTINGS, FROM T",
            "SELECT SETTINGS FROM T WHRE ID = 1",
            "SELECT SETTINGS FROM T WHERE",
            "SELECT ID FROM T A JOIN WHERE ID = 1",
    })
    void stillReportsRealErrorsWithThoseNames(String sql) {
        assertNotNull(CHECKER.check(sql, SqlDialect.ORACLE).error(), sql);
    }

    // JSqlParser の更新で読めない語が増えたら、ここで気づく。増えた語は、列名に使える語なら
    // KEYWORD_NAMES に足し、SQL の構造を作る語なら STRUCTURAL に足す。
    @Test
    void everyKeywordIsReadableAsAColumnUnlessItBuildsTheSqlStructure() {
        List<String> unreadable = new ArrayList<>();
        for (String image : CCJSqlParserConstants.tokenImage) {
            if (!image.matches("<K_[A-Z0-9_]+>")) {
                continue;
            }
            String word = image.substring(3, image.length() - 1);
            if (ORACLE_RESERVED.contains(word) || STRUCTURAL.contains(word)) {
                continue;
            }
            for (String form : List.of("SELECT %s FROM T", "SELECT X %s FROM T", "SELECT X FROM T WHERE %s = 1",
                    "UPDATE T SET %s = 1")) {
                if (CHECKER.check(String.format(form, word), SqlDialect.ORACLE).error() != null) {
                    unreadable.add(String.format(form, word));
                }
            }
        }
        assertEquals(List.of(), unreadable);
    }

    @Test
    void quotesKeywordsButNotFunctionsOrText() {
        assertEquals("SELECT \"SETTINGS\", \"settings\", SETTINGS(1), 'SETTINGS' /* SETTINGS */, \"SETTINGS\" FROM T",
                SqlSyntaxChecker.quoteNames(
                        "SELECT SETTINGS, settings, SETTINGS(1), 'SETTINGS' /* SETTINGS */, \"SETTINGS\" FROM T"));
    }

    @Test
    void quotesOnlyDottedNamesOutsideStringsAndComments() {
        assertEquals("SELECT \"A\".\"FINAL\", 'x.y', \"B\".\"C\" /* d.e */ FROM T A -- f.g\nWHERE 1.5 > ?",
                SqlSyntaxChecker.quoteNames(
                        "SELECT A.FINAL, 'x.y', \"B\".\"C\" /* d.e */ FROM T A -- f.g\nWHERE 1.5 > ?"));
        assertEquals("SELECT \"A\".* FROM \"S\".\"T\" A", SqlSyntaxChecker.quoteNames("SELECT A.* FROM S.T A"));
    }
}
