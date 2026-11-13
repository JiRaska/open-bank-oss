// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence

import org.eclipse.microprofile.config.ConfigProvider
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

/** Shared-image callback: choose the own-book chart at runtime, after the common schema exists. */
class PensionChartCallback : Callback {
    // Flyway probes support with a null context while discovering callbacks.
    override fun supports(event: Event, context: Context?): Boolean = event == Event.AFTER_MIGRATE

    override fun canHandleInTransaction(event: Event, context: Context?): Boolean = true

    override fun getCallbackName(): String = "pension-own-book-chart"

    override fun handle(event: Event, context: Context) {
        if (event != Event.AFTER_MIGRATE) return
        val config = ConfigProvider.getConfig()
        val kind = config.getOptionalValue("openbank.ledger.book.kind", String::class.java).orElse("BANK")
        val id = config.getOptionalValue("openbank.ledger.book.id", String::class.java).orElse(null)
        bindBeforeChart(context.connection, kind, id)
        if (kind == "BANK") return

        val scripts = if (kind == "PENSION_FUND") {
            listOf(resource("db/pension-fund/afterMigrate__pension_fund_chart.sql"))
        } else {
            val file = config.getOptionalValue("openbank.ledger.book.chart-script", String::class.java)
                .filter { it.isNotBlank() }
                .orElseThrow { IllegalStateException("A company chart requires openbank.ledger.book.chart-script") }
            listOf(
                Files.readString(Path.of(file)),
                resource("db/pension-company/afterMigrate__pension_company_fee_map.sql"),
            )
        }
        for (sql in scripts) {
            context.connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun bindBeforeChart(connection: Connection, kind: String, configuredId: String?) {
        val existing = binding(connection)
        if (kind == "BANK") {
            check(configuredId == null && existing == null) {
                "The bank must not carry or reuse a pension book identity"
            }
            return
        }
        require(kind in setOf("PENSION_FUND", "PENSION_COMPANY")) { "Unknown ledger book kind" }
        val id = UUID.fromString(requireNotNull(configuredId) { "A pension chart requires an explicit book id" })
        if (existing != null) {
            check(existing == (kind to id)) { "The immutable pension book identity changed" }
            return
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT EXISTS (SELECT 1 FROM journal_entries)").use { rows ->
                check(rows.next() && !rows.getBoolean(1)) {
                    "An unbound ledger with journal history requires a reviewed book cutover"
                }
            }
        }
        connection.prepareStatement(
            "INSERT INTO ledger_book_identity (singleton, book_kind, book_id) VALUES (true, ?, ?)",
        ).use { statement ->
            statement.setString(1, kind)
            statement.setObject(2, id)
            statement.executeUpdate()
        }
    }

    private fun binding(connection: Connection): Pair<String, UUID>? = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT book_kind, book_id FROM ledger_book_identity WHERE singleton").use { rows ->
            if (rows.next()) rows.getString(1) to rows.getObject(2, UUID::class.java) else null
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Missing own-book chart resource: $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
