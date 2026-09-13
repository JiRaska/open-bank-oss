// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.it

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID

/**
 * Test-scope CDI alternatives for the disbursement's customer-credit leg (#3931), outranking the
 * production `@Default` no-op (which now correctly fails the disbursement — a build with no real
 * backend genuinely cannot pay a customer, and must say so rather than pretend to).
 *
 * [LendingOutboxWriteIT] boots the real app end-to-end to prove a `loan.disbursed` outbox row gets
 * written; the customer-credit integration is a different concern with its own coverage
 * (`LendingServiceTest`'s `disburse fails when...` tests) and must not be what makes THIS test
 * flaky or fail. Unconditionally succeeding — no `@IfBuildProperty` gate — because these are test
 * sources only, never packaged into the running service.
 */
@ApplicationScoped
@Alternative
@Priority(200)
class TestBorrowerAccountLookupPort : BorrowerAccountLookupPort {
    override fun findCurrentAccount(partyId: UUID, currency: String): Uni<UUID?> =
        Uni.createFrom().item(UUID.randomUUID())
}

@ApplicationScoped
@Alternative
@Priority(200)
class TestBorrowerCreditPort : BorrowerCreditPort {
    override fun credit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> =
        Uni.createFrom().item(Unit)
    override fun debit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> =
        Uni.createFrom().item(Unit)
}

/**
 * Test-scope stand-in for [com.openbank.lending.application.port.out.LedgerPostingPort] (#6057).
 *
 * The production `@Default` [com.openbank.lending.infrastructure.adapter.NoOpLedgerPostingPort] now
 * REFUSES every posting instead of returning the real adapter's success value, so a `@QuarkusTest`
 * that drives a real disbursement needs an explicit stub rather than silently riding on a defect.
 * That is the point of the change: no build gets a "posted" answer from something that posts
 * nothing — a test has to say out loud that it is standing in for the ledger.
 *
 * Postings are recorded so a test can assert on what the flow tried to post.
 */
@ApplicationScoped
@Alternative
@Priority(200)
class TestRecordingLedgerPostingPort : com.openbank.lending.application.port.out.LedgerPostingPort {
    val recorded: MutableList<com.openbank.lending.application.port.out.LedgerPosting> =
        java.util.Collections.synchronizedList(mutableListOf())

    override fun post(posting: com.openbank.lending.application.port.out.LedgerPosting): Uni<Unit> {
        recorded += posting
        return Uni.createFrom().item(Unit)
    }
}
