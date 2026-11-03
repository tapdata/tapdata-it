package io.tapdata.it.l3.steps.jdbc;

/** 列名 + JDBC SQL 类型元数据. */
public record ColumnInfo(String name, int sqlType) {}
