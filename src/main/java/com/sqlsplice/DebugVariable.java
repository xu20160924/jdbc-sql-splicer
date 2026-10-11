package com.sqlsplice;

/** 当前 Java 调试栈中可求值的局部变量。 */
public record DebugVariable(String name, String type) {
    @Override
    public String toString() {
        return name + "  (" + type + ")";
    }
}
