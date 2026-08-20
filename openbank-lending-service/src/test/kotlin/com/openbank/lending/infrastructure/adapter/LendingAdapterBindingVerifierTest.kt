// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.libs.domain.money.Money
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Stands in for a real, wired adapter: the name must not match the `NoOp`/`Logging` prefixes. */
private class RestStubLedgerPort : LedgerPostingPort {
    override fun post(posting: LedgerPosting): Uni<Unit> = Uni.createFrom().item(Unit)
}

private class RestStubBorrowerCreditPort : BorrowerCreditPort {
    override fun credit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> =
        Uni.createFrom().item(Unit)
    override fun debit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> =
        Uni.createFrom().item(Unit)
}

private class JpaStubLoanEventEmitter : LoanEventEmitter {
    override fun emit(message: LendingOutboxMessage): Uni<Unit> = Uni.createFrom().item(Unit)
}

/**
 * #6057: the `@IfBuildProperty` gates on the outbound adapters are resolved at augmentation and
 * frozen into the image, while the deployment activated them with container env vars. The
 * combination shipped a no-op that returned success on the general-ledger path.
 *
 * These tests are about the verifier's ability to DETECT that state. The companion
 * `LedgerAdapterBindingIT` proves the shipped configuration itself binds the real adapter.
 */
class LendingAdapterBindingVerifierTest {

    private fun verifier(
        ledger: LedgerPostingPort,
        credit: BorrowerCreditPort,
        events: LoanEventEmitter,
        ledgerBackend: String,
        creditBackend: String,
        outboxBackend: String = "jpa",
        meters: SimpleMeterRegistry = SimpleMeterRegistry(),
    ) = LendingAdapterBindingVerifier(
        ledger,
        credit,
        events,
        meters,
        ledgerBackend,
        creditBackend,
        outboxBackend,
    )

    @Test
    fun `refuses to boot when the runtime asks for the real ledger backend and the no-op is bound`() {
        // Exactly the deployed state measured on the running pod: LENDING_LEDGER_BACKEND=rest in the
        // container environment, NoOpLedgerPostingPort baked into the image by augmentation.
        assertThatThrownBy {
            verifier(
                ledger = NoOpLedgerPostingPort(),
                credit = RestStubBorrowerCreditPort(),
                events = JpaStubLoanEventEmitter(),
                ledgerBackend = "rest",
                creditBackend = "rest",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lending.ledger.backend")
            .hasMessageContaining("LENDING_LEDGER_BACKEND")
            .hasMessageContaining("NoOpLedgerPostingPort")
            .hasMessageContaining("BUILD-time")
    }

    @Test
    fun `refuses to boot when the borrower-credit backend is inert`() {
        assertThatThrownBy {
            verifier(
                ledger = RestStubLedgerPort(),
                credit = NoOpBorrowerCreditPort(),
                events = JpaStubLoanEventEmitter(),
                ledgerBackend = "rest",
                creditBackend = "rest",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lending.borrower-credit.backend")
            .hasMessageContaining("NoOpBorrowerCreditPort")
    }

    @Test
    fun `refuses to boot when the outbox emitter is the logging stand-in`() {
        assertThatThrownBy {
            verifier(
                ledger = RestStubLedgerPort(),
                credit = RestStubBorrowerCreditPort(),
                events = LoggingLoanEventEmitter(),
                ledgerBackend = "rest",
                creditBackend = "rest",
                outboxBackend = "jpa",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lending.outbox.backend")
            .hasMessageContaining("LoggingLoanEventEmitter")
    }

    @Test
    fun `reports every inert port at once rather than only the first`() {
        assertThatThrownBy {
            verifier(
                ledger = NoOpLedgerPostingPort(),
                credit = NoOpBorrowerCreditPort(),
                events = JpaStubLoanEventEmitter(),
                ledgerBackend = "rest",
                creditBackend = "rest",
            )
        }
            .hasMessageContaining("lending.ledger.backend")
            .hasMessageContaining("lending.borrower-credit.backend")
    }

    @Test
    fun `boots when the real adapters are bound and the runtime asks for them`() {
        assertThatCode {
            verifier(
                ledger = RestStubLedgerPort(),
                credit = RestStubBorrowerCreditPort(),
                events = JpaStubLoanEventEmitter(),
                ledgerBackend = "rest",
                creditBackend = "rest",
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `boots offline when the runtime does not ask for a real backend`() {
        // The offline story ADR-0028 D3 protects: no-ops bound AND declared. They refuse calls
        // rather than reporting success, so nothing can mistake this build for a wired one.
        assertThatCode {
            verifier(
                ledger = NoOpLedgerPostingPort(),
                credit = NoOpBorrowerCreditPort(),
                events = JpaStubLoanEventEmitter(),
                ledgerBackend = "none",
                creditBackend = "none",
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `publishes a gauge naming which implementation is bound, per property`() {
        val meters = SimpleMeterRegistry()
        verifier(
            ledger = RestStubLedgerPort(),
            credit = NoOpBorrowerCreditPort(),
            events = JpaStubLoanEventEmitter(),
            ledgerBackend = "rest",
            creditBackend = "none",
            meters = meters,
        )

        val gauges = meters.find("openbank_lending_adapter_real_backend_bound").gauges()
        assertThat(gauges).hasSize(3)

        val ledgerGauge = gauges.single { it.id.getTag("property") == "lending.ledger.backend" }
        assertThat(ledgerGauge.value()).isEqualTo(1.0)
        assertThat(ledgerGauge.id.getTag("implementation")).isEqualTo("RestStubLedgerPort")

        val creditGauge = gauges.single { it.id.getTag("property") == "lending.borrower-credit.backend" }
        assertThat(creditGauge.value()).isEqualTo(0.0)
        assertThat(creditGauge.id.getTag("implementation")).isEqualTo("NoOpBorrowerCreditPort")
    }
}

/** The no-op adapters must not be able to spell "success" at all (#6057). */
class NoOpAdaptersRefuseRatherThanSucceedTest {

    @Test
    fun `the no-op ledger port fails with a dedicated type instead of returning Unit`() {
        val posting = LedgerPosting(
            reference = "loan:1:disbursement",
            partyId = UUID.randomUUID(),
            amount = Money.of("12000.00", "CZK"),
            kind = PostingKind.DISBURSEMENT,
        )

        assertThatThrownBy { NoOpLedgerPostingPort().post(posting).await().indefinitely() }
            .isInstanceOf(LedgerBackendNotConfiguredException::class.java)
            .hasMessageContaining("DISBURSEMENT")
            .hasMessageContaining("loan:1:disbursement")
    }

    @Test
    fun `the no-op borrower credit refuses both directions`() {
        val port = NoOpBorrowerCreditPort()
        val account = UUID.randomUUID()
        val amount = Money.of("500.00", "CZK")

        assertThatThrownBy { port.credit("ref-c", account, amount).await().indefinitely() }
            .isInstanceOf(BorrowerCreditBackendNotConfiguredException::class.java)
        assertThatThrownBy { port.debit("ref-d", account, amount).await().indefinitely() }
            .isInstanceOf(BorrowerCreditBackendNotConfiguredException::class.java)
    }
}
