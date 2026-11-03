package com.mybatislint.analyzer.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void quotesOnlyDottedNamesOutsideStringsAndComments() {
        assertEquals("SELECT \"A\".\"FINAL\", 'x.y', \"B\".\"C\" /* d.e */ FROM T A -- f.g\nWHERE 1.5 > ?",
                SqlSyntaxChecker.quoteDottedNames(
                        "SELECT A.FINAL, 'x.y', \"B\".\"C\" /* d.e */ FROM T A -- f.g\nWHERE 1.5 > ?"));
        assertEquals("SELECT \"A\".* FROM \"S\".\"T\" A", SqlSyntaxChecker.quoteDottedNames("SELECT A.* FROM S.T A"));
    }
}
