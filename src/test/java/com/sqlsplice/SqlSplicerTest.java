package com.sqlsplice;

import org.junit.Test;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SqlSplicerTest {
    @Test
    public void escapesStringFormatsDateAndExpandsCollection() {
        String sql = SqlSplicer.splice(
                "select * from orders where owner = ? and created_at >= ? and id in (?)",
                List.of("O'Reilly", Timestamp.valueOf("2026-10-09 12:34:56"), List.of(7, 9)));

        assertEquals("select * from orders where owner = 'O''Reilly' and created_at >= '2026-10-09 12:34:56' and id in (7, 9)", sql);
    }

    @Test
    public void expandsPrimitiveArrayAndKeepsNullLiteral() {
        assertEquals("select * from t where id in (1, 2) and deleted_at is NULL",
                SqlSplicer.splice("select * from t where id in (?) and deleted_at is ?", Arrays.asList(new int[]{1, 2}, null)));
    }

    @Test
    public void rejectsParameterPlaceholderMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlSplicer.splice("select * from t where id = ?", List.of(1, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> SqlSplicer.splice("select * from t where id = ?", List.of()));
    }

    @Test
    public void skipsQuestionMarksInsideLiteralsAndComments() {
        String sql = "select '?' as literal, \"?\" as identifier -- ? comment\n"
                + "from t /* ? comment */ where name = ? and id = ?";

        assertEquals("select '?' as literal, \"?\" as identifier -- ? comment\n"
                        + "from t /* ? comment */ where name = 'Alice' and id = 5",
                SqlSplicer.splice(sql, List.of("Alice", 5)));
    }

    @Test
    public void rejectsEmptyCollectionsBecauseInEmptyParenthesesIsInvalidSql() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlSplicer.splice("select * from t where id in (?)", List.of(List.of())));
    }

    @Test
    public void createsSelfContainedDebugExpressionForLegalVariableNames() {
        String expression = DebugExpressionBuilder.expression("jdbcSql", "bindArgs");

        assertEquals(true, expression.contains(".splice(jdbcSql,bindArgs)"));
        assertEquals(true, expression.contains("String splice(Object rawSql,Object args)"));
        assertEquals(true, expression.contains("java.lang.reflect.Array.getLength"));
        assertThrows(IllegalArgumentException.class, () -> DebugExpressionBuilder.expression("sql;", "args"));
    }
}
