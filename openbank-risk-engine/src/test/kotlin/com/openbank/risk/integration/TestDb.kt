// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import org.eclipse.microprofile.config.ConfigProvider
import java.sql.DriverManager

/** Plain JDBC against the IT database, for cleanup the tests share. */
internal object TestDb {
    fun execute(sql: String) {
        connect().use { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
    }

    /** The first column of the first row of a `SELECT count(*)`. */
    fun count(sql: String): Int = connect().use { conn -> conn.createStatement().use { countOf(it, sql) } }

    /** The first column of every row of [sql], as text. */
    fun query(sql: String, row: (String) -> Unit) {
        connect().use { conn -> conn.createStatement().use { st -> each(st, sql, row) } }
    }

    private fun each(st: java.sql.Statement, sql: String, row: (String) -> Unit) {
        st.executeQuery(sql).use { rs -> while (rs.next()) row(rs.getString(1)) }
    }

    private fun countOf(st: java.sql.Statement, sql: String): Int =
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else 0 }

    private fun connect() = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
