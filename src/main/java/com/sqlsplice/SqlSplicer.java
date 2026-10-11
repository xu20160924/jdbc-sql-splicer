package com.sqlsplice;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Iterator;

/** JDBC 问号占位符的纯 Java 拼接逻辑，可独立测试。 */
public final class SqlSplicer {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private SqlSplicer() {
    }

    public static String splice(String sql, Iterable<?> args) {
        if (sql == null) {
            throw new IllegalArgumentException("SQL must not be null");
        }
        if (args == null) {
            throw new IllegalArgumentException("Arguments must not be null; use an empty array or collection when there are no arguments");
        }

        String result = sql;
        for (Object arg : args) {
            String literal = toSqlLiteral(arg);
            String next = replaceNextPlaceholder(result, literal);
            if (next == null) {
                throw new IllegalArgumentException("More arguments were supplied than SQL placeholders");
            }
            result = next;
        }
        if (findNextPlaceholder(result) >= 0) {
            throw new IllegalArgumentException("Unreplaced SQL placeholder remains");
        }
        return result;
    }

    public static String toSqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return quote(value.toString());
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        if (value instanceof BigDecimal || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof Timestamp timestamp) {
            return quote(DATE_TIME.format(timestamp.toLocalDateTime()));
        }
        if (value instanceof Date date) {
            return quote(DATE.format(date.toLocalDate()));
        }
        if (value instanceof Time time) {
            return quote(TIME.format(time.toLocalTime()));
        }
        if (value instanceof LocalDateTime dateTime) {
            return quote(DATE_TIME.format(dateTime));
        }
        if (value instanceof LocalDate date) {
            return quote(DATE.format(date));
        }
        if (value instanceof LocalTime time) {
            return quote(TIME.format(time));
        }
        if (value instanceof OffsetDateTime dateTime) {
            return quote(DATE_TIME.format(dateTime.toLocalDateTime()));
        }
        if (value instanceof Instant instant) {
            return quote(DATE_TIME.format(instant.atZone(ZoneId.systemDefault()).toLocalDateTime()));
        }
        if (value instanceof java.util.Date date) {
            return quote(DATE_TIME.format(Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime()));
        }
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                throw new IllegalArgumentException("An empty collection cannot be rendered as a SQL argument; handle an empty IN condition explicitly in application code");
            }
            return join(collection.iterator());
        }
        if (value.getClass().isArray()) {
            if (Array.getLength(value) == 0) {
                throw new IllegalArgumentException("An empty array cannot be rendered as a SQL argument; handle an empty IN condition explicitly in application code");
            }
            return joinArray(value);
        }
        return quote(value.toString());
    }

    private static String joinArray(Object array) {
        StringBuilder builder = new StringBuilder();
        int length = Array.getLength(array);
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(toSqlLiteral(Array.get(array, index)));
        }
        return builder.toString();
    }

    private static String join(Iterator<?> iterator) {
        StringBuilder builder = new StringBuilder();
        while (iterator.hasNext()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(toSqlLiteral(iterator.next()));
        }
        return builder.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String replaceNextPlaceholder(String sql, String literal) {
        int index = findNextPlaceholder(sql);
        if (index < 0) {
            return null;
        }
        return sql.substring(0, index) + literal + sql.substring(index + 1);
    }

    /**
     * 仅识别 SQL 代码区的 JDBC 占位符，跳过单引号、双引号、行注释和块注释中的问号。
     */
    private static int findNextPlaceholder(String sql) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (singleQuoted) {
                if (current == '\'' && next == '\'') {
                    index++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (current == '"' && next == '"') {
                    index++;
                } else if (current == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '?') {
                return index;
            }
        }
        return -1;
    }
}
