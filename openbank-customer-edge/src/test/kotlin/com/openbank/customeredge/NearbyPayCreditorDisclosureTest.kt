// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Where a nearby/pay-to-phone session token DOES and does NOT keep the payee's account private
 * (issue #3890).
 *
 * The privacy property this rail actually has is a **request-side** one, and it is worth pinning:
 * the payer never supplies the payee's account, and the discovery/lookup answer carries only a
 * mask (`DirectoryPayeeTest`). That is what defeats the harvesting threat the design was built
 * against — walking a range of phone numbers to collect IBANs, for free.
 *
 * It is NOT a response-side one, and the prose used to claim otherwise. `createDomesticPayment`
 * returns the upstream `DomesticPaymentResponse` verbatim, and that DTO declares
 * `creditorAccountNumber` / `creditorBankCode` / `creditorName` as required fields — so the payer's
 * own confirmation names the account their money went to, as a bank statement does, and as
 * `enrichWithCounterpartyIban` already does on the very next `GET /transactions` page. ADR-0095,
 * which formalises and supersedes this rail, goes further and puts the full SPAYD descriptor
 * (IBAN included) on the payer's device by design.
 *
 * These tests exist so the two halves can never silently swap again: a future projection added to
 * the confirmation would go red HERE rather than in a mobile client, and losing the request-side
 * resolution would go red too.
 */
class NearbyPayCreditorDisclosureTest {

    private val payer: UUID = UUID.randomUUID()
    private val payee: UUID = UUID.randomUUID()
    private val payerAccount: UUID = UUID.randomUUID()
    private val payeeAccount: UUID = UUID.randomUUID()

    private val sessions = PaymentSessionStore()

    private fun resource(upstream: UpstreamClient): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        sessions,
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns payer.toString()
            every { subject } returns payer.toString()
        }
        objectMapper = ObjectMapper()
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        accountServiceUrl = "http://account"
        domesticPaymentServiceUrl = "http://dompay"
        partyServiceUrl = "http://party"
        scaServiceUrl = "http://sca"
    }

    /**
     * The fixture answers as the real upstreams do. Note what the CONFIRMATION carries: the
     * upstream `DomesticPaymentResponse` declares creditorAccountNumber / creditorBankCode /
     * creditorName as non-nullable, so a fixture that omitted them would be testing a payload
     * domestic-payment-service cannot produce.
     */
    private fun upstream(): UpstreamClient = mockk<UpstreamClient>().also { up ->
        every { up.get(match { it.contains("/accounts/$payerAccount") }, any()) } returns
            Response.ok("""{"id":"$payerAccount","partyId":"$payer","accountNumber":"$PAYER_IBAN"}""").build()
        every { up.get(match { it.contains("/accounts/$payeeAccount") }, any()) } returns
            Response.ok("""{"id":"$payeeAccount","partyId":"$payee","accountNumber":"$PAYEE_IBAN"}""").build()
        every { up.get(match { it.contains("/parties/") }, any()) } returns
            Response.ok("""{"legalName":"Payer Name"}""").build()
        every { up.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            Response.ok("""{"status":"CONSUMED"}""").build()
        every { up.post(match { it.contains("/domestic-payments") }, any(), any(), any()) } returns
            Response.status(201).entity(
                """
                {"id":"$PAYMENT_ID","status":"RECEIVED","debtorAccountId":"$payerAccount",
                 "debtorAccountNumber":"19-2000145399","debtorBankCode":"0800","debtorName":"Payer Name",
                 "creditorAccountNumber":"$PAYEE_BBAN","creditorBankCode":"$PAYEE_BANK",
                 "creditorName":"Jarmila Nováková","amount":"250.00","currency":"CZK"}
                """.trimIndent().replace("\n", ""),
            ).build()
    }

    /** A live session, exactly as `directoryPayee` / the nearby rail mints one. */
    private fun session(): String = sessions.create(
        creditorAccountId = payeeAccount.toString(),
        creditorPartyId = payee.toString(),
        displayName = "Jarmila Nováková",
        requestedAmount = null,
        creditorMasked = PaymentSessionStore.maskIban(PAYEE_IBAN),
    )

    /** The payer's body: a token and no creditor account — the whole point of the rail. */
    private fun body(token: String) = """
        {"debtorAccountId":"$payerAccount","amount":"250.00","currency":"CZK",
         "paymentSessionToken":"$token","creditorName":"Jarmila Nováková"}
    """.trimIndent().replace("\n", "")

    // ── the property that IS in force: the payer never SUPPLIES the account ──────────────────

    /**
     * The edge resolves the real creditor from its own session store. The payer's body carries a
     * token and nothing else, yet the instruction that reaches domestic-payment-service is fully
     * addressed — which is what makes the discovery answer safe to mask.
     */
    @Test
    fun `the payer never supplies the creditor account - the edge resolves it from the session`() {
        val up = upstream()
        val sent = slot<String>()
        every { up.post(match { it.contains("/domestic-payments") }, any(), capture(sent), any()) } returns
            Response.status(201).entity("""{"id":"$PAYMENT_ID","status":"RECEIVED"}""").build()

        val token = session()
        val payerBody = body(token)
        assertThat(payerBody).doesNotContain(PAYEE_BBAN)

        val resp = resource(up).createDomesticPayment(payerBody, "idem-nbp-1", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        assertThat(sent.captured).contains(""""creditorAccountNumber":"$PAYEE_BBAN"""")
        assertThat(sent.captured).contains(""""creditorBankCode":"$PAYEE_BANK"""")
    }

    // ── the property that is NOT in force, and must stop being claimed ───────────────────────

    /**
     * The confirmation the payer's device receives names the creditor account. This is deliberate
     * — it is the payer's own payment, `GET /transactions` re-adds the counterparty IBAN one
     * request later, and ADR-0095 puts the full IBAN on the payer's device by design — but it is
     * the exact opposite of what the comment at the session-resolution site used to promise.
     *
     * Pinned rather than fixed on purpose: `DomesticPaymentResponse` declares these fields
     * non-nullable and `openbank-admin-ui` renders them, so dropping them is a breaking API change
     * on a money path, not a comment-sized correction.
     */
    @Test
    fun `the confirmation returned to the payer carries the creditor account verbatim`() {
        val resp = resource(upstream()).createDomesticPayment(body(session()), "idem-nbp-2", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        val confirmation = resp.entity.toString()
        assertThat(confirmation).contains(""""creditorAccountNumber":"$PAYEE_BBAN"""")
        assertThat(confirmation).contains(""""creditorBankCode":"$PAYEE_BANK"""")
        assertThat(confirmation).contains("Jarmila Nováková")
    }

    /**
     * The same body again on the status poll the Send screen runs after an "accepted" create
     * (ADR-0108). Worth its own assertion: it is a second, independent path to the same field, so
     * a projection applied only to the create would leave the disclosure fully intact here.
     */
    @Test
    fun `the status poll returns the creditor account too - a second path to the same field`() {
        val up = upstream()
        every { up.get(match { it.contains("/domestic-payments/$PAYMENT_ID") }, any()) } returns
            Response.ok(
                """
                {"id":"$PAYMENT_ID","status":"SETTLED","debtorAccountId":"$payerAccount",
                 "creditorAccountNumber":"$PAYEE_BBAN","creditorBankCode":"$PAYEE_BANK",
                 "creditorName":"Jarmila Nováková"}
                """.trimIndent().replace("\n", ""),
            ).build()

        val resp = resource(up).getDomesticPaymentStatus(PAYMENT_ID.toString())

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity.toString()).contains(""""creditorAccountNumber":"$PAYEE_BBAN"""")
    }

    companion object {
        const val PAYER_IBAN = "CZ6508000000192000145399"

        // CZ + 2 check + bank 2010 + prefix 000000 + base 2600123456.
        const val PAYEE_IBAN = "CZ5520100000002600123456"
        const val PAYEE_BBAN = "2600123456"
        const val PAYEE_BANK = "2010"

        const val SCA_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val PAYMENT_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
