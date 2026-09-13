// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.infrastructure.client.LedgerCallGuard
import com.openbank.lending.infrastructure.client.LendingGlChart
import com.openbank.lending.infrastructure.client.LendingJournalFactory
import com.openbank.lending.infrastructure.client.LendingLedgerConfig
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * Real [LedgerPostingPort]: posts the loan book's cash events to ledger-service as balanced
 * double-entry journals (ADR-0028 D3), using the same `POST /api/v1/journals` contract the
 * transaction-service uses — the platform's only ledger ingestion surface.
 *
 * Build-time gated by `lending.ledger.backend=rest`; when unset the `@Default` no-op stays bound and
 * the service builds and boots with zero external dependency (the platform realization pattern,
 * ADR-0045), exactly like the analytics adapters.
 */
// `@Unremovable` because a test asserts this bean's PRESENCE (LedgerAdapterBindingIT, #6057).
// The test-scope `@Priority(200)` stubs outrank it, which makes it unused, and ArC would then
// remove it for a reason unrelated to the build-time gate under test — the assertion would fail
// against correct code. No effect in production, where nothing outranks it. `@IfBuildProperty`
// still disables it outright when the backend is not selected, so the negative case is unaffected.
@io.quarkus.arc.Unremovable
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "lending.ledger.backend", stringValue = "rest")
class RestLedgerPostingAdapter(
    private val guard: LedgerCallGuard,
    private val config: LendingLedgerConfig,
    private val clock: Clock,
) : LedgerPostingPort {

    private val log = Logger.getLogger(RestLedgerPostingAdapter::class.java)

    override fun post(posting: LedgerPosting): Uni<Unit> {
        val request = LendingJournalFactory.buildRequest(
            posting = posting,
            // Select the GL account set matching the loan's own currency — ledger-service 422s a line
            // whose currency doesn't match its GL account's currency (issue #1275). Fails loud on an
            // unseeded currency rather than mis-posting.
            accounts = LendingGlChart.accountsFor(posting.amount.currency.code),
            systemActorId = config.systemActorId(),
            date = LocalDate.now(clock),
        )
        return guard.postJournal(request)
            .invoke { response ->
                log.debugf("ledger journal %s posted (%s) for %s", response.id, response.status, posting.reference)
            }
            .map { Unit }
    }
}
