// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.infrastructure.adapter.RestAccountContextPort
import com.openbank.billing.infrastructure.adapter.RestAccountPartyLookupPort
import com.openbank.billing.infrastructure.adapter.RestBillableAccountDiscoveryPort
import com.openbank.billing.infrastructure.adapter.RestProductRevisionPort
import com.openbank.billing.infrastructure.client.AccountDto
import com.openbank.billing.infrastructure.client.AccountPageDto
import com.openbank.billing.infrastructure.client.AccountPageInfoDto
import com.openbank.billing.infrastructure.client.AccountRestClient
import com.openbank.billing.infrastructure.client.BalanceDto
import com.openbank.billing.infrastructure.client.BalanceRestClient
import com.openbank.billing.infrastructure.client.PriceComponentDto
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.billing.infrastructure.client.RevisionContentDto
import com.openbank.billing.infrastructure.client.RevisionDto
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Unit coverage for the read-path adapters (ADR-0143 phase 2c), with mocked REST clients. */
class RestBillingAdaptersTest {

    @Test
    fun `resolve builds the FeeContext from account + balance reads`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "CZK", "ACTIVE"))
        every { balances.getBalance("acc1", "CZK") } returns
            Uni.createFrom().item(BalanceDto("acc1", "CZK", BigDecimal("1000")))

        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "CZK")

        assertThat(r).isNotNull
        assertThat(r!!.productId).isEqualTo("prod-1")
        assertThat(r.context.balance).isEqualByComparingTo("1000")
        assertThat(r.context.currency).isEqualTo("CZK")
        assertThat(r.context.segment).isNull()
        assertThat(r.context.monthlyTurnover).isNull()
    }

    @Test
    fun `resolve fails closed (null) when the account read errors`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount(any()) } returns Uni.createFrom().failure(RuntimeException("account-service down"))

        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "CZK")

        assertThat(r).isNull()
    }

    @Test
    fun `resolve leaves balance null when the balance read errors`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "EUR", "ACTIVE"))
        every { balances.getBalance("acc1", "EUR") } returns Uni.createFrom().failure(RuntimeException("balance down"))

        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "EUR")

        assertThat(r).isNotNull
        assertThat(r!!.context.balance).isNull()
    }

    @Test
    fun `activeAccounts maps ids and forwards the cursor only while more pages exist`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.listActiveAccounts(100, null) } returns Uni.createFrom().item(
            AccountPageDto(
                data = listOf(AccountDto("acc-1", "prod-1", "CZK"), AccountDto("acc-2", "prod-1", "CZK")),
                pagination = AccountPageInfoDto(hasNextPage = true, nextCursor = "c1"),
            ),
        )
        every { accounts.listActiveAccounts(100, "c1") } returns Uni.createFrom().item(
            AccountPageDto(
                data = listOf(AccountDto("acc-3", "prod-2", "CZK")),
                pagination = AccountPageInfoDto(hasNextPage = false, nextCursor = null),
            ),
        )
        val port = RestBillableAccountDiscoveryPort(accounts)

        val first = port.activeAccounts(100, null)
        assertThat(first.accountIds).containsExactly("acc-1", "acc-2")
        assertThat(first.nextCursor).isEqualTo("c1")

        val last = port.activeAccounts(100, "c1")
        assertThat(last.accountIds).containsExactly("acc-3")
        assertThat(last.nextCursor).isNull()
    }

    @Test
    fun `activeAccounts propagates a failed page read instead of failing open`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.listActiveAccounts(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("account-service down"))
        val port = RestBillableAccountDiscoveryPort(accounts)

        val thrown = runCatching { port.activeAccounts(100, null) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    }

    private val meters = SimpleMeterRegistry()

    private fun legacyDocument(vararg feeIds: String) = """{"fees":[""" + feeIds.joinToString(",") {
        """{"id":"$it","name":"Fee $it","type":"MONTHLY","amount":5.0,"currency":"CZK","waivable":true,""" +
            """"waiveCondition":"Balance > 50 000 CZK"}"""
    } + "]}"

    private fun revision(
        state: String = "PUBLISHED",
        attributes: Map<String, Any?>,
        vararg prices: PriceComponentDto,
    ) = RevisionDto(REVISION, state, RevisionContentDto(attributes, prices.toList()))

    private fun price(code: String, value: String, currency: String, kind: String = "AMOUNT") =
        PriceComponentDto(code, kind, BigDecimal(value), currency, "monthly", "MONTHLY")

    @Test
    fun `terms read AMOUNT prices from the revision and take identity and waiver from the embedded v1 fee`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(
                    attributes = mapOf("productType" to "CURRENT", "legacyDocument" to legacyDocument("f1", "f2")),
                    prices = arrayOf(
                        price("FEE_F1_3F524CDC07A11D7C6220", "5", "CZK"),
                        price("FEE_F2_NOT_THE_HASH", "2", "EUR"),
                        price("BASE_RATE", "0.025", "CZK", kind = "RATE"),
                    ),
                ),
            )

            val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")

            val fee = terms.fees.single()
            // The v1 fee id survives, so an already-billed fee keeps its idempotency key across the cutover.
            assertThat(fee.feeId).isEqualTo("f1")
            assertThat(fee.amount).isEqualByComparingTo("5")
            assertThat(fee.waivable).isTrue()
            assertThat(fee.waiveCondition).isEqualTo("Balance > 50 000 CZK")
            assertThat(terms.accountingClass).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
            assertThat(terms.accountingClassSource).isEqualTo(AccountingClassSource.DERIVED_FROM_LEGACY_PRODUCT_TYPE)
            assertThat(meters.get("billing.accounting_class.derived").counter().count()).isEqualTo(1.0)
        }

    @Test
    fun `an accountingClass attribute on the revision wins over any derivation`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
            revision(
                attributes = mapOf(
                    "productType" to "CURRENT",
                    "accountingClass" to "CUSTOMER_DEPOSIT_TERM",
                    "legacyDocument" to legacyDocument("f1"),
                ),
                prices = arrayOf(price("FEE_F1_3F524CDC07A11D7C6220", "9", "CZK")),
            ),
        )

        val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")

        assertThat(terms.accountingClass).isEqualTo("CUSTOMER_DEPOSIT_TERM")
        assertThat(terms.accountingClassSource).isEqualTo(AccountingClassSource.REVISION)
        assertThat(terms.fees.single().feeId).isEqualTo("f1")
        assertThat(meters.find("billing.accounting_class.derived").counter()).isNull()
    }

    @Test
    fun `only legacy v1 fees are fees - a minimum balance or a limit is never billed but is counted`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(
                    attributes = mapOf("accountingClass" to "CUSTOMER_DEPOSIT_DEMAND"),
                    prices = arrayOf(
                        price("MINIMUM_BALANCE", "1000", "CZK"),
                        price("DAILY_WITHDRAWAL_LIMIT", "50000", "CZK"),
                        price("INTEREST_RATE", "0.02", "CZK", kind = "RATE"),
                    ),
                ),
            )
            val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")

            assertThat(terms.fees).isEmpty()
            assertThat(terms.feeBearing).isFalse()
            assertThat(terms.droppedFeeComponents).isEqualTo(0)
            // the two AMOUNTs have no fee identity (the RATE is not an amount and is not counted)
            assertThat(terms.noFeeIdentityComponents).isEqualTo(2)
            assertThat(meters.find("billing.fee.component.dropped").counter()).isNull()
        }

    @Test
    fun `a legacy fee the document declares but the revision has no price component for is dropped`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(
                    attributes = mapOf(
                        "accountingClass" to "CUSTOMER_DEPOSIT_DEMAND",
                        "legacyDocument" to legacyDocument("f1", "f2"),
                    ),
                    prices = arrayOf(price("FEE_F1_3F524CDC07A11D7C6220", "5", "CZK")),
                ),
            )
            val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")

            assertThat(terms.fees.map { it.feeId }).containsExactly("f1")
            assertThat(terms.droppedFeeComponents).isEqualTo(1)
            assertThat(
                meters.get("billing.fee.component.dropped").tag("reason", "MISSING_PRICE_COMPONENT").counter().count(),
            ).isEqualTo(1.0)
        }

    @Test
    fun `an unreadable embedded v1 document is reported as such, not as a catalog fault`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
            revision(
                attributes = mapOf("accountingClass" to "CUSTOMER_DEPOSIT_DEMAND", "legacyDocument" to "{not json"),
            ),
        )
        val thrown = runCatching { RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK") }.exceptionOrNull()
        assertThat(
            thrown,
        ).isInstanceOf(com.openbank.billing.application.port.out.RevisionTermsUnreadableException::class.java)
    }

    @Test
    fun `a revision with neither a class nor a derivable product type reports none`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
            revision(attributes = mapOf("productType" to "FUTURE_TYPE"), prices = arrayOf(price("X", "1", "CZK"))),
        )

        val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")

        assertThat(terms.accountingClass).isNull()
        assertThat(terms.accountingClassSource).isEqualTo(AccountingClassSource.NONE)
    }

    @Test
    fun `terms are cached by revision id when the revision is immutable, never when it is a draft`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(attributes = mapOf("accountingClass" to "CUSTOMER_DEPOSIT_DEMAND"), prices = arrayOf()),
            )
            val port = RestProductRevisionPort(catalog, meters)
            port.terms(REVISION, "CZK")
            port.terms(REVISION, "EUR")
            verify(exactly = 1) { catalog.getRevision(REVISION) }

            val draft = mockk<ProductCatalogRestClient>()
            every { draft.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(state = "DRAFT", attributes = mapOf("accountingClass" to "CUSTOMER_DEPOSIT_DEMAND")),
            )
            val draftPort = RestProductRevisionPort(draft, meters)
            draftPort.terms(REVISION, "CZK")
            draftPort.terms(REVISION, "CZK")
            verify(exactly = 2) { draft.getRevision(REVISION) }
        }

    @Test
    fun `terms refuse a response for a different revision than the one asked for`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
            RevisionDto("0191c0de-0000-7000-8000-0000000000ff", "PUBLISHED", RevisionContentDto()),
        )

        val thrown = runCatching { RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `terms propagate a failed catalog read instead of failing open`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(any()) } returns
            Uni.createFrom().failure(RuntimeException("product-catalog down"))

        val thrown = runCatching { RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `the catalog's own 404 means nothing is published, any other failure is a fault`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getPublishedRevisionForLegacyProduct("none", any()) } returns
            Uni.createFrom().failure(NotFoundException())
        every { catalog.getPublishedRevisionForLegacyProduct("boom", any()) } returns
            Uni.createFrom().failure(RuntimeException("5xx"))
        val port = RestProductRevisionPort(catalog, meters)

        assertThat(port.publishedRevisionIdFor("none")).isNull()
        assertThat(runCatching { port.publishedRevisionIdFor("boom") }.exceptionOrNull())
            .isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `a non-deposit v1 product type reports no class, so its assessment is a counted skip`(): Unit = runBlocking {
        for (type in listOf("LOAN", "MORTGAGE", "CREDIT_CARD", "OVERDRAFT", "INVESTMENT")) {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(attributes = mapOf("productType" to type), prices = arrayOf(price("X", "1", "CZK"))),
            )
            val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")
            assertThat(terms.accountingClass).describedAs(type).isNull()
            assertThat(terms.accountingClassSource).isEqualTo(AccountingClassSource.NONE)
        }
    }

    @Test
    fun `a fee component billing cannot price is dropped loudly by reason and the revision stays fee-bearing`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            val pct = com.openbank.billing.infrastructure.adapter.LegacyFees.priceCode("f1")
            val noCcy = com.openbank.billing.infrastructure.adapter.LegacyFees.priceCode("f2")
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(
                    attributes = mapOf(
                        "accountingClass" to "CUSTOMER_DEPOSIT_DEMAND",
                        "legacyDocument" to legacyDocument("f1", "f2"),
                    ),
                    prices = arrayOf(
                        price(pct, "0.01", "CZK", kind = "RATE"),
                        PriceComponentDto(noCcy, "AMOUNT", BigDecimal("3"), null, "monthly", "MONTHLY"),
                        price("FEE_UNKNOWN_DEADBEEF", "7", "CZK"),
                    ),
                ),
            )
            val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")

            assertThat(terms.fees).isEmpty()
            assertThat(terms.feeBearing).isTrue()
            assertThat(terms.droppedFeeComponents).isEqualTo(3)
            assertThat(meters.get("billing.fee.component.dropped").tag("reason", "NON_AMOUNT_KIND").counter().count())
                .isEqualTo(1.0)
            assertThat(meters.get("billing.fee.component.dropped").tag("reason", "NULL_CURRENCY").counter().count())
                .isEqualTo(1.0)
            assertThat(
                meters.get("billing.fee.component.dropped").tag("reason", "NOT_IN_LEGACY_DOCUMENT").counter().count(),
            )
                .isEqualTo(1.0)
        }

    @Test
    fun `a revision whose only price is an interest rate is not fee-bearing`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
            revision(
                attributes = mapOf("accountingClass" to "CUSTOMER_DEPOSIT_SAVINGS"),
                prices = arrayOf(price("INTEREST_RATE", "0.02", "CZK", kind = "RATE")),
            ),
        )
        val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "CZK")
        assertThat(terms.feeBearing).isFalse()
        assertThat(terms.fees).isEmpty()
    }

    @Test
    fun `fees in only another currency make the revision fee-bearing with nothing to charge here`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getRevision(REVISION) } returns Uni.createFrom().item(
                revision(
                    attributes = mapOf(
                        "accountingClass" to "CUSTOMER_DEPOSIT_DEMAND",
                        "legacyDocument" to legacyDocument("f1"),
                    ),
                    prices = arrayOf(price("FEE_F1_3F524CDC07A11D7C6220", "9", "CZK")),
                ),
            )
            val terms = RestProductRevisionPort(catalog, meters).terms(REVISION, "EUR")
            assertThat(terms.fees).isEmpty()
            assertThat(terms.feeBearing).isTrue()
        }

    @Test
    fun `the pin lookup is dated at the account's opening`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        val opened = java.time.Instant.parse("2026-03-01T00:00:00Z")
        every { catalog.getPublishedRevisionForLegacyProduct("prod-1", "2026-03-01T00:00:00Z") } returns
            Uni.createFrom().item(RevisionDto(REVISION, "SUPERSEDED", RevisionContentDto()))
        val port = RestProductRevisionPort(catalog, meters)

        assertThat(port.publishedRevisionIdFor("prod-1", opened)).isEqualTo(REVISION)
        verify(exactly = 0) { catalog.getPublishedRevisionForLegacyProduct("prod-1", null) }
        assertThat(meters.find("billing.revision_pin.fallback_to_current").counter()).isNull()
    }

    @Test
    fun `an account older than the catalog's first revision falls back to the current one, counted`(): Unit =
        runBlocking {
            val catalog = mockk<ProductCatalogRestClient>()
            every { catalog.getPublishedRevisionForLegacyProduct("prod-1", "2020-01-01T00:00:00Z") } returns
                Uni.createFrom().failure(NotFoundException())
            every { catalog.getPublishedRevisionForLegacyProduct("prod-1", null) } returns
                Uni.createFrom().item(RevisionDto(REVISION, "PUBLISHED", RevisionContentDto()))
            val port = RestProductRevisionPort(catalog, meters)

            assertThat(port.publishedRevisionIdFor("prod-1", java.time.Instant.parse("2020-01-01T00:00:00Z")))
                .isEqualTo(REVISION)
            assertThat(meters.get("billing.revision_pin.fallback_to_current").counter().count()).isEqualTo(1.0)
        }

    @Test
    fun `an unknown product stays unresolved even after the fallback`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getPublishedRevisionForLegacyProduct("none", any()) } returns
            Uni.createFrom().failure(NotFoundException())
        val port = RestProductRevisionPort(catalog, meters)
        assertThat(port.publishedRevisionIdFor("none", java.time.Instant.parse("2020-01-01T00:00:00Z"))).isNull()
        assertThat(meters.find("billing.revision_pin.fallback_to_current").counter()).isNull()
    }

    @Test
    fun `resolve carries the account's opening instant`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount("acc1") } returns Uni.createFrom().item(
            AccountDto("acc1", "prod-1", "CZK", "ACTIVE", null, "2026-03-01T08:30:00Z"),
        )
        every { balances.getBalance("acc1", "CZK") } returns
            Uni.createFrom().item(BalanceDto("acc1", "CZK", BigDecimal("1")))
        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "CZK")
        assertThat(r!!.openedAt).isEqualTo(java.time.Instant.parse("2026-03-01T08:30:00Z"))
    }

    @Test
    fun `partyIdFor reads partyId off the account read`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "CZK", "ACTIVE", "party-1"))

        val partyId = RestAccountPartyLookupPort(accounts).partyIdFor("acc1")

        assertThat(partyId).isEqualTo("party-1")
    }

    @Test
    fun `partyIdFor fails closed (null) when the account read errors`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.getAccount(any()) } returns Uni.createFrom().failure(RuntimeException("account-service down"))

        val partyId = RestAccountPartyLookupPort(accounts).partyIdFor("acc1")

        assertThat(partyId).isNull()
    }

    @Test
    fun `partyIdFor returns null when the account response carries no partyId`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "CZK", "ACTIVE", null))

        val partyId = RestAccountPartyLookupPort(accounts).partyIdFor("acc1")

        assertThat(partyId).isNull()
    }

    private companion object {
        const val REVISION = "0191c0de-0000-7000-8000-0000000000a1"
    }
}
