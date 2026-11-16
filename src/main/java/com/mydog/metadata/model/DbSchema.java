package com.mydog.metadata.model;

/** A database (schema) on a server. */
public record DbSchema(String name, String charset, String collation) {
}
