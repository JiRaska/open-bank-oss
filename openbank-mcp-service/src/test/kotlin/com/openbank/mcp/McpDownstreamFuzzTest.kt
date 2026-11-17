// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.mcp.FuzzPayloads.Payload
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.infrastructure.read.AccountServiceClient
import com.openbank.mcp.infrastructure.read.BalanceServiceClient
import com.openbank.mcp.infrastructure.read.ConsentValidateClient
import com.openbank.mcp.infrastructure.read.ConsentValidationResponse
import com.openbank.mcp.infrastructure.read.DomesticPaymentServiceClient
import com.openbank.mcp.infrastructure.read.RealAccountReadPort
import com.openbank.mcp.infrastructure.read.RealPaymentConfirmationReadPort
import com.openbank.mcp.infrastructure.read.SepaPaymentServiceClient
import com.openbank.mcp.infrastructure.read.TransactionServiceClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #16617, layer 5 (downstream half): "never a downstream call for invalid input", asserted
 * against the REAL read adapters over counting client doubles — the ports the in-process sweep
 * stubs out. A call that reaches consent-service, account-service or a payment service is counted;
 * the consent double answers `valid=false`, so nothing past the gate is exercised and no customer
 * data shape is needed.
 */
class McpDownstreamFuzzTest {

    private val mapper = jacksonObjectMapper()
    private val consentCalls = AtomicInteger()
    private val ctx = ConsentContext(
        agentId = "agent:fuzz",
        consentId = "6a1f5f38-59a5-4e77-9f0d-2b1a7a2f1c11",
        grantedAccounts = emptyList(),
    )

    private val consent = mockk<ConsentValidateClient>().also {
        every { it.validate(any(), any()) } answers {
            consentCalls.incrementAndGet()
            ConsentValidationResponse(valid = false, reason = "fuzz", code = null)
        }
    }
    private val accounts = mockk<AccountServiceClient>(relaxed = true)
    private val balances = mockk<BalanceServiceClient>(relaxed = true)
    private val transactions = mockk<TransactionServiceClient>(relaxed = true)
    private val sepa = mockk<SepaPaymentServiceClient>(relaxed = true)
    private val domestic = mockk<DomesticPaymentServiceClient>(relaxed = true)

    private val accountPort = RealAccountReadPort(consent, accounts, balances, transactions, mapper)
    private val paymentPort = RealPaymentConfirmationReadPort(consent, accounts, sepa, domestic)

    private fun decoded(p: Payload): String = mapper.readTree(p.json).asText()

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.openbank.mcp.McpDownstreamFuzzTest#nonUuidStrings")
    fun `a paymentId that is not a UUID never reaches any downstream service`(p: Payload) {
        runCatching { paymentPort.getPaymentConfirmation(ctx, decoded(p)) }
            .exceptionOrNull().let { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }

        assertThat(consentCalls.get()).`as`("consent-service was called for paymentId=%s", p.label).isZero()
        verify(exactly = 0) { sepa.getPayment(any()) }
        verify(exactly = 0) { domestic.getPayment(any()) }
    }

    @Test
    fun `control - a well-formed paymentId DOES reach consent-service, so the counter above can see calls`() {
        runCatching { paymentPort.getPaymentConfirmation(ctx, "6a1f5f38-59a5-4e77-9f0d-2b1a7a2f1c11") }
        assertThat(consentCalls.get()).isEqualTo(1)
    }

    @Test
    fun `an invalid paymentId is not reflected into the error message`() {
        val huge = "P".repeat(FuzzPayloads.MEGABYTE)
        val message = runCatching { paymentPort.getPaymentConfirmation(ctx, huge) }.exceptionOrNull()?.message.orEmpty()
        assertThat(message.length).isLessThan(1_024)
    }

    @Test
    fun `control - an IBAN reaches consent-service on the balance path`() {
        runCatching { accountPort.getBalance(ctx, "CZ6508000000192000145399") }
        assertThat(consentCalls.get()).isEqualTo(1)
    }

    @Test
    fun `an accountId that is not an IBAN never reaches consent-service`() {
        FuzzPayloads.ibanShapedGarbage.forEach { p ->
            runCatching { accountPort.getBalance(ctx, decoded(p)) }
            runCatching { accountPort.listTransactions(ctx, decoded(p), 10) }
        }
        assertThat(consentCalls.get()).`as`("consent-service calls for garbage account ids").isZero()
    }

    companion object {
        @JvmStatic
        fun nonUuidStrings(): List<Payload> = FuzzPayloads.strings.filter { it.isString }
    }
}
