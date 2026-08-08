// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.inject.Vetoed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [RealStatementReadPort] (issue #4109, ADR-0248) — mirrors [RealAccountReadPortTest]'s harness and
 * invariants: live consent validation before touching downstream data, `grantedAccounts`
 * intersection enforced, and (specific to this port) the category enrichment + latest-closed-period
 * resolution when no `legalSequence` is given.
 */
class RealStatementReadPortTest {

    private val mapper = jacksonObjectMapper()
    private val consentId = UUID.randomUUID()
    private val ctx =
        ConsentContext(agentId = "agent:mcp-tpp-42", consentId = consentId.toString(), grantedAccounts = emptyList())
    private val internalId = UUID.randomUUID()

    private fun accountJson(id: UUID): JsonNode = mapper.createObjectNode().put("id", id.toString())

    private fun statementJson(entries: List<Pair<String?, String?>>): JsonNode {
        val root = mapper.createObjectNode()
        root.put("accountId", internalId.toString())
        root.put("currency", "CZK")
        val arr: ArrayNode = root.putArray("entries")
        entries.forEach { (description, counterparty) ->
            val entry = arr.addObject()
            description?.let { entry.put("description", it) }
            counterparty?.let { entry.put("counterparty", it) }
        }
        return root
    }

    @Test
    fun `an exact legalSequence with currency calls the summary endpoint directly`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val accounts = FakeAccountServiceClient(mapOf("CZ01" to accountJson(internalId)))
        val statements = FakeStatementServiceClient(
            summaries = mapOf(Triple(internalId, "CZK", 3L) to statementJson(listOf("groceries" to null))),
        )
        val port = RealStatementReadPort(consent, accounts, statements)

        val result = port.getStatementSummary(ctx, "CZ01", currency = "CZK", legalSequence = 3L)

        assertThat(result.path("entries").single().path("category").asText()).isEqualTo("GROCERIES")
        assertThat(consent.lastRequest?.requiredScope).isEqualTo(ConsentScopes.STATEMENTS_READ)
        assertThat(consent.lastRequest?.accountIban).isEqualTo("CZ01")
    }

    @Test
    fun `legalSequence without currency is a client error, not a silent guess`() {
        val port = RealStatementReadPort(
            FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01")),
            FakeAccountServiceClient(mapOf("CZ01" to accountJson(internalId))),
            FakeStatementServiceClient(emptyMap()),
        )

        assertThatThrownBy { port.getStatementSummary(ctx, "CZ01", currency = null, legalSequence = 3L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `omitting both resolves the most recently CLOSED period`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val accounts = FakeAccountServiceClient(mapOf("CZ01" to accountJson(internalId)))
        val periods = mapper.createArrayNode().apply {
            addObject().put("pocketCurrency", "CZK").put("legalSequenceNumber", 1L).put("status", "CLOSED")
            addObject().put("pocketCurrency", "CZK").put("legalSequenceNumber", 2L).put("status", "SUPERSEDED")
            addObject().put("pocketCurrency", "CZK").put("legalSequenceNumber", 4L).put("status", "CLOSED")
        }
        val statements = FakeStatementServiceClient(
            listings = mapOf(internalId to periods),
            summaries = mapOf(Triple(internalId, "CZK", 4L) to statementJson(emptyList())),
        )
        val port = RealStatementReadPort(consent, accounts, statements)

        port.getStatementSummary(ctx, "CZ01", currency = null, legalSequence = null)

        assertThat(statements.lastSummaryRequest).isEqualTo(Triple(internalId, "CZK", 4L))
    }

    @Test
    fun `denies an account outside the granted consent scope before calling statement-service`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val statements = FakeStatementServiceClient(emptyMap())
        val port = RealStatementReadPort(consent, FakeAccountServiceClient(emptyMap()), statements)

        assertThatThrownBy { port.getStatementSummary(ctx, "CZ99-not-granted", "CZK", 1L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not within the granted consent scope")
        assertThat(statements.summaryCalled).isFalse()
    }

    @Test
    fun `each entry gets a best-effort category, never crashing on an entry with no text`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val statements = FakeStatementServiceClient(
            summaries = mapOf(
                Triple(internalId, "CZK", 1L) to statementJson(listOf(null to null, "monthly fee" to null)),
            ),
        )
        val accounts = FakeAccountServiceClient(mapOf("CZ01" to accountJson(internalId)))
        val port = RealStatementReadPort(consent, accounts, statements)

        val entries = port.getStatementSummary(ctx, "CZ01", "CZK", 1L).path("entries")

        assertThat(entries[0].path("category").asText()).isEqualTo("OTHER")
        assertThat(entries[1].path("category").asText()).isEqualTo("FEES")
    }
}

@Vetoed
private class FakeStatementServiceClient(
    private val summaries: Map<Triple<UUID, String, Long>, JsonNode> = emptyMap(),
    private val listings: Map<UUID, JsonNode> = emptyMap(),
) : StatementServiceClient {
    var summaryCalled: Boolean = false
    var lastSummaryRequest: Triple<UUID, String, Long>? = null

    override fun listStatements(accountId: UUID): JsonNode =
        listings[accountId] ?: throw NoSuchElementException("no periods for $accountId")

    override fun statementSummary(accountId: UUID, currency: String, legalSequence: Long): JsonNode {
        summaryCalled = true
        val key = Triple(accountId, currency, legalSequence)
        lastSummaryRequest = key
        return summaries[key] ?: throw NoSuchElementException("no summary for $key")
    }
}
