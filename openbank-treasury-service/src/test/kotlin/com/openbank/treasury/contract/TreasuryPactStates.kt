// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.contract

import com.openbank.treasury.application.port.`in`.PortfolioStatementUseCase
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.infrastructure.iso20022.Semt002Statements
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.eclipse.microprofile.config.ConfigProvider
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Provider states for tax-reporting's portfolio pact (ADR-0337 amendment, #12504). */
object TreasuryPactStates {
    /** Must match TaxReportingTreasuryPortfolioPactConsumerTest (openbank-tax-reporting-service). */
    const val SNAPSHOT_STATE = "the pension company holds investment positions at 2026-12-31"

    /** The 409 ("no statement, never an empty list") state; recorded by the consumer since #12504. */
    const val NO_SNAPSHOT_STATE = "no portfolio snapshot exists for 2026-12-31"

    /** The state name every consumer of this provider uses for its missing-identity interaction. */
    const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

    private const val DATE = "2026-12-31"
    private const val FIXTURE = "/semt002/pension-co-2026-12-31.xml"

    /**
     * Ingests the synthetic custodian semt.002 for 2026-12-31 through the REAL use case (parse,
     * CFI classification, store). Idempotent, so replaying the state twice stores one version.
     */
    fun snapshotExists(vertx: Vertx, portfolio: PortfolioStatementUseCase) {
        val xml = requireNotNull(TreasuryPactStates::class.java.getResource(FIXTURE)).readBytes()
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml))
        runOnVertxContext(vertx) {
            portfolio.upload(
                Semt002Statements.parse(xml),
                sha256,
                "pact-portfolio-$DATE",
                Actor("pact-state", ActorType.HUMAN),
            )
        }
    }

    /** Removes every stored version for the date — test data only; the service itself never deletes. */
    fun noSnapshotExists() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.autoCommit = false
            c.createStatement().use { st ->
                st.executeUpdate("delete from portfolio_holdings where statement_date = date '$DATE'")
                st.executeUpdate(
                    "update portfolio_statements set supersedes = null where statement_date = date '$DATE'",
                )
                st.executeUpdate("delete from portfolio_statements where statement_date = date '$DATE'")
            }
            c.commit()
        }
    }

    /** Reactive Panache needs a Vert.x context, which Pact-JVM's state callback thread lacks. */
    private fun runOnVertxContext(vertx: Vertx, block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private const val STATE_TIMEOUT_SECONDS = 10L
}
