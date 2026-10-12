// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CountryCode
import com.openbank.customeredge.infrastructure.rest.CurrencyCode
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PayeeIban
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.util.UUID

/**
 * CodeQL `java/ssrf` (alert #611): every caller value that reaches an upstream URL path or query is
 * parsed into a closed value type at the resource boundary. Invalid input is a 400 that never reaches
 * the upstream; valid input reaches it as the exact canonical URL.
 */
class UpstreamPathValuesTest {

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    /** Records every upstream URL; the account read answers "owned by the caller". */
    private class Recorder(party: UUID, account: UUID, pocketBalance: Int = 0) {
        val urls = mutableListOf<String>()
        val upstream = mockk<UpstreamClient>()

        init {
            val accountJson = """{"id":"$account","partyId":"$party"}"""
            every { upstream.get(any(), any()) } answers {
                val url = firstArg<String>()
                urls += url
                when {
                    url == "http://account/api/v1/accounts/$account" -> Response.ok(accountJson).build()
                    url.endsWith("/pockets") ->
                        Response.ok("""{"pockets":[{"currencyCode":"CZK","isPrimary":true}]}""").build()
                    url.startsWith("http://balance/") ->
                        Response.ok(
                            """{"balances":[{"currency":"EUR","bookedAmount":$pocketBalance,"availableAmount":$pocketBalance}]}""",
                        ).build()
                    url.startsWith("http://fx/") -> Response.ok("""{"bidRate":"25.0"}""").build()
                    else -> Response.ok("[]").build()
                }
            }
            every { upstream.getRaw(any(), any(), any()) } answers {
                urls += firstArg<String>()
                Response.ok("doc").build()
            }
            every { upstream.delete(any(), any()) } answers {
                urls += firstArg<String>()
                Response.noContent().build()
            }
        }
    }

    private fun resource(upstream: UpstreamClient) = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        inMemoryPaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns party.toString()
            every { subject } returns party.toString()
        }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        balanceServiceUrl = "http://balance"
        productCatalogUrl = "http://catalog"
        partyServiceUrl = "http://party"
        statementServiceUrl = "http://statement"
        fxServiceUrl = "http://fx"
    }

    private val ownershipRead get() = "http://account/api/v1/accounts/$account"

    // ── value types ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `value types accept only their alphabet and length`() {
        assertThat(CurrencyCode.parse("EUR")?.value).isEqualTo("EUR")
        assertThat(CurrencyCode.parse("eur")).isNull()
        assertThat(CurrencyCode.parse("EU")).isNull()
        assertThat(CurrencyCode.parse("E/R")).isNull()
        assertThat(CountryCode.parse("cz")?.value).isEqualTo("cz")
        assertThat(CountryCode.parse("C1")).isNull()
        assertThat(PayeeIban.parse("CZ6508000000192000145399")?.value).isEqualTo("CZ6508000000192000145399")
        assertThat(PayeeIban.parse("A".repeat(35))).isNull()
        assertThat(PayeeIban.parse("")).isNull()
        assertThat(PayeeIban.parse(null)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["EÚR", "ＥＵＲ", "E\u0000R", "E R", "E%2"])
    fun `non-ASCII lookalikes and encodings are not currency codes`(raw: String) {
        assertThat(CurrencyCode.parse(raw)).isNull()
    }

    // ── closePocket / resolvePocket / convertQuote / renderStatement (currency) ─────────────────

    @ParameterizedTest
    @ValueSource(strings = ["../x", "EU", "EUR?a=1", "E%52", "EURO"])
    fun `close pocket refuses a non-currency segment before any pocket call`(raw: String) {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).closePocket(account, raw).status).isEqualTo(400)
        assertThat(rec.urls).containsExactly(ownershipRead)
        verify(exactly = 0) { rec.upstream.delete(any(), any()) }
    }

    @Test
    fun `close pocket deletes the canonical upper-case pocket`() {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).closePocket(account, "eur").status).isEqualTo(204)
        assertThat(rec.urls.last()).isEqualTo("http://account/api/v1/accounts/$account/pockets/EUR")
    }

    @ParameterizedTest
    @ValueSource(strings = ["CZK&x=1", "C", "1ZK"])
    fun `resolve pocket refuses a non-currency query value`(raw: String) {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).resolvePocket(account, raw).status).isEqualTo(400)
        assertThat(rec.urls).containsExactly(ownershipRead)
    }

    @Test
    fun `resolve pocket queries the exact canonical currency`() {
        val rec = Recorder(party, account)
        resource(rec.upstream).resolvePocket(account, "usd")
        assertThat(rec.urls.last())
            .isEqualTo("http://account/api/v1/accounts/$account/pockets/resolve?currency=USD")
    }

    @Test
    fun `convert quote refuses a non-currency segment before the fx call`() {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).convertQuote(account, "EUR/..").status).isEqualTo(400)
        assertThat(rec.urls).containsExactly(ownershipRead)
    }

    @Test
    fun `convert quote prices the canonical currency pair`() {
        val rec = Recorder(party, account, pocketBalance = 10)
        assertThat(resource(rec.upstream).convertQuote(account, "eur").status).isEqualTo(200)
        assertThat(rec.urls).contains("http://fx/api/v1/fx/rates/EUR/CZK")
    }

    @ParameterizedTest
    @ValueSource(strings = ["eur", "EU", "EUR%2F..", "../.."])
    fun `statement render refuses a non-currency segment`(raw: String) {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).renderStatement(account, raw, 7, "PDF").status).isEqualTo(400)
        assertThat(rec.urls).containsExactly(ownershipRead)
        verify(exactly = 0) { rec.upstream.getRaw(any(), any(), any()) }
    }

    @Test
    fun `statement render fetches the exact canonical document`() {
        val rec = Recorder(party, account)
        resource(rec.upstream).renderStatement(account, "CZK", 7, "camt053")
        assertThat(rec.urls.last())
            .isEqualTo("http://statement/api/v1/statements/$account/CZK/7?format=CAMT_053")
    }

    // ── deletePayee (iban) ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["..", "CZ65 0800", "CZ65/../x", "CZ65%2F", "CZ65?x=1"])
    fun `delete payee refuses a non-alphanumeric IBAN with no upstream call`(raw: String) {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).deletePayee(raw).status).isEqualTo(400)
        assertThat(rec.urls).isEmpty()
    }

    @Test
    fun `delete payee targets the exact payee`() {
        val rec = Recorder(party, account)
        resource(rec.upstream).deletePayee("CZ6508000000192000145399")
        assertThat(rec.urls)
            .containsExactly("http://party/api/v1/parties/$party/payees/CZ6508000000192000145399")
    }

    // ── listProductOffers (type) ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["SAVINGS&status=DRAFT", "MORTGAGE", "SAVINGS#"])
    fun `product list refuses an unknown type with no upstream call`(raw: String) {
        val rec = Recorder(party, account)
        assertThat(resource(rec.upstream).listProductOffers(raw).status).isEqualTo(400)
        assertThat(rec.urls).isEmpty()
    }

    @Test
    fun `product list queries the canonical type`() {
        val rec = Recorder(party, account)
        resource(rec.upstream).listProductOffers("savings")
        assertThat(rec.urls.first()).isEqualTo("http://catalog/api/v1/products?type=SAVINGS&status=ACTIVE")
    }
}
