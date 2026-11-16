package com.mydog.sync;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a fresh JDBC connection on demand.
 *
 * <p>The sync engine needs several connections to the same server at once — one to read metadata,
 * one per side to stream rows while diffing, and one to write — because MySQL will not run a
 * second statement on a connection that is still streaming a result set. Handing out factories
 * instead of connections keeps that requirement honest: whoever needs a connection opens it, and
 * closes it on the same line.
 */
@FunctionalInterface
public interface ConnectionFactory {

    Connection open() throws SQLException;
}
