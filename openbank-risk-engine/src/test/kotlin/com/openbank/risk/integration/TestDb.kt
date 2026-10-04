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

    /**
     * The ČNB minimum-reserve facts as fx-service parses them from the live workbook: ratio 4 % from
     * 2025-01-02 and remuneration 0 from 2023-10-05 (the rows that matter for 2025+ snapshots).
     * Test data standing in for the consumed topic. Idempotent.
     */
    fun seedReserveFacts() {
        execute(
            "INSERT INTO cnb_policy_rate_fact (instrument, effective_from, rate, source_url, fetched_at, " +
                "content_sha256, note, received_at) VALUES " +
                "('MIN_RESERVE_RATIO', DATE '2025-01-02', 0.04, '$PMR_URL', now(), '$PMR_SHA', " +
                "'Vyhláška č. 323/2024 Sb.', now()), " +
                "('MIN_RESERVE_REMUNERATION', DATE '2023-10-05', 0, '$PMR_URL', now(), '$PMR_SHA', null, now()) " +
                "ON CONFLICT (instrument, effective_from) DO NOTHING",
        )
    }

    const val PMR_URL =
        "https://www.cnb.cz/export/sites/cnb/cs/financni-trhy/.galleries/penezni_trh/download/PMR_historie_zmen.xlsx"
    const val PMR_SHA = "43ffa8b5bedfd02d28970eacd7ea41cee83434373fb07856c6483c6383526e38"

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
