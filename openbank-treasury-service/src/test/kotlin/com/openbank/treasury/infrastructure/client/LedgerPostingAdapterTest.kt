// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.out.AccountingDayClosedException
import com.openbank.treasury.domain.LedgerMapGolden
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LedgerRole
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.Side
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Rollout safety of the class-based posting (ADR-0341 D3): with `post-by-class` OFF (the default)
 * the wire payload is the explicit `glAccountId` an OLD ledger understands, with it ON the legs name
 * a class, and a pinned reversal leg always names the account of the original posting.
 */
class LedgerPostingAdapterTest {

    private val mapper = ObjectMapper()
    private val dealId = UUID.fromString("0191c0de-0000-7000-8000-000000000001")

    private fun spec(vararg lines: PostingLine) = JournalSpec(dealId, PostingEvent.SETTLED, lines.toList())

    private fun line(cls: String, role: LedgerRole, side: Side, pinned: UUID? = null) =
        PostingLine(cls, role, side, BigDecimal("100.00"), "CZK", pinned)

    private fun capture(
        postByClass: Boolean,
        spec: JournalSpec,
        answerLines: List<UUID>,
    ): Pair<PostJournalRequest, com.openbank.treasury.application.port.out.PostedJournal> {
        val client = mockk<LedgerRestClient>()
        var sent: PostJournalRequest? = null
        every { client.postJournal(any()) } answers {
            sent = firstArg()
            Uni.createFrom().item(
                JournalResponse(
                    UUID.randomUUID(),
                    dealId,
                    "POSTED",
                    answerLines.mapIndexed { i, id -> JournalLineResponse(id, i + 1) }.reversed(),
                ),
            )
        }
        val posted =
            runBlocking { LedgerPostingAdapter(client, postByClass).post(spec, LocalDate.parse("2026-03-16"), "t") }
        return checkNotNull(sent) to posted
    }

    private val placement = spec(
        line("INTERBANK_PLACEMENT", LedgerRole.PRINCIPAL, Side.DEBIT),
        line("NOSTRO_ACCOUNT", LedgerRole.PRINCIPAL, Side.CREDIT),
    )

    @Test
    fun `flag off sends exactly the explicit-account payload an old ledger understands`() {
        val (request, _) = capture(false, placement, listOf(UUID.randomUUID(), UUID.randomUUID()))
        val json = mapper.readTree(mapper.writeValueAsString(request))
        val legs = json["lines"]
        assertThat(legs[0]["glAccountId"].asText()).isEqualTo("a0000000-0000-0000-0000-000000001500")
        assertThat(legs[1]["glAccountId"].asText()).isEqualTo("a0000000-0000-0000-0000-000000001001")
        legs.forEach {
            // no class fields at all: an old ledger cannot be confused by, nor reject, an unknown field
            assertThat(it.has("accountingClass")).isFalse()
            assertThat(it.has("accountingRole")).isFalse()
        }
    }

    @Test
    fun `flag on sends the class and no account`() {
        val (request, _) = capture(true, placement, listOf(UUID.randomUUID(), UUID.randomUUID()))
        val legs = mapper.readTree(mapper.writeValueAsString(request))["lines"]
        assertThat(legs[0]["accountingClass"].asText()).isEqualTo("INTERBANK_PLACEMENT")
        assertThat(legs[0]["accountingRole"].asText()).isEqualTo("PRINCIPAL")
        legs.forEach { assertThat(it.has("glAccountId")).isFalse() }
    }

    @Test
    fun `a pinned reversal leg names its original account in either mode and never the class`() {
        val original = UUID.fromString("a0000000-0000-0000-0000-000000007777")
        val reversal = spec(
            line("INTERBANK_PLACEMENT", LedgerRole.PRINCIPAL, Side.CREDIT, pinned = original),
            line("NOSTRO_ACCOUNT", LedgerRole.PRINCIPAL, Side.DEBIT, pinned = original),
        )
        listOf(true, false).forEach { byClass ->
            val (request, _) = capture(byClass, reversal, listOf(original, original))
            request.lines.forEach {
                assertThat(it.glAccountId).describedAs("postByClass=$byClass").isEqualTo(original)
                assertThat(it.accountingClass).isNull()
            }
        }
    }

    @Test
    fun `the accounts the ledger answered are recorded per leg, in the order the legs were sent`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val (_, posted) = capture(true, placement, listOf(a, b))
        assertThat(posted.accounts.map { it.glAccountId }).containsExactly(a, b)
        assertThat(posted.accounts.map { it.accountingClass }).containsExactly("INTERBANK_PLACEMENT", "NOSTRO_ACCOUNT")
    }

    @Test
    fun `an answer that does not match the legs records nothing rather than a guess`() {
        val (_, posted) = capture(true, placement, listOf(UUID.randomUUID()))
        assertThat(posted.accounts).isEmpty()
    }

    @Test
    fun `the rollout shim holds exactly the treasury rows of the ledger map`() {
        assertThat(LegacyGlChart.entries).isEqualTo(LedgerMapGolden.treasury)
    }

    private fun refusing(status: Int, body: String): LedgerPostingAdapter {
        val client = mockk<LedgerRestClient>()
        every { client.postJournal(any()) } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(
                jakarta.ws.rs.core.Response.status(status).entity(body).type("application/json").build(),
            ),
        )
        return LedgerPostingAdapter(client, true)
    }

    @Test
    fun `a 409 ACCOUNTING_DAY_CLOSED is a closed-day refusal, any other 409 stays an error`() {
        assertThatThrownBy {
            runBlocking {
                refusing(409, """{"code":"ACCOUNTING_DAY_CLOSED","error":"day closed"}""")
                    .post(placement, LocalDate.parse("2026-03-16"), "t")
            }
        }.isInstanceOf(AccountingDayClosedException::class.java).hasMessageContaining("2026-03-16")
        assertThatThrownBy {
            runBlocking {
                refusing(409, """{"code":"IDEMPOTENCY_CONFLICT"}""").post(placement, LocalDate.parse("2026-03-16"), "t")
            }
        }.isInstanceOf(jakarta.ws.rs.WebApplicationException::class.java)
            .isNotInstanceOf(AccountingDayClosedException::class.java)
    }
}
