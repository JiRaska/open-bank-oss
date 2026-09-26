// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Composes the customer's net worth from the services that OWN each figure (ADR-0301 D2).
 *
 * This holds no data of its own and caches nothing. Every leaf names the service that answered
 * for it, so a reader can always tell an authoritative bank figure from a customer's assertion —
 * which is the whole reason the composition lives here and not in a projection.
 *
 * ## The rule that shapes the whole class: a branch that did not answer is UNAVAILABLE, never zero
 *
 * A fan-out has one dangerous failure: an upstream that is down contributes 0, the total still
 * renders, and the customer reads a smaller number as fact. Every existing edge read in this
 * service fails soft to `[]` — `listLoans` says so in its own KDoc — which is right for a list the
 * app draws as a section and wrong for a summand. A missing loan branch does not make someone
 * richer by the size of their mortgage.
 *
 * So each branch carries its own [BranchStatus], the totals carry the set of branches that fed
 * them, and a caller that ignores all of it still cannot silently read a partial total as complete:
 * `complete` is false whenever any branch failed.
 *
 * ## No currency conversion, ever (ADR-0302 D7)
 *
 * Totals are per currency. Converting here would make this a second FX authority whose rate ages
 * invisibly; `openbank-fx-service` is the one that knows, and the app converts for display with
 * the rate and its timestamp shown. A consolidated cross-currency figure is indicative only
 * (ADR-0024) and this service does not produce one.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one private fetcher per branch, plus the assemblers
class NetWorthComposer {

    /** What happened when we asked the service that owns this branch. */
    enum class BranchStatus {
        /** The owner answered. `leaves` is what it said, and may legitimately be empty. */
        AVAILABLE,

        /**
         * The owner did not answer, or answered unusably. Distinct from an empty AVAILABLE branch:
         * "this customer has no loans" and "we could not ask about loans" are different facts and
         * must not render the same (the ADR-0210 D9 lesson, one layer up).
         */
        UNAVAILABLE,
    }

    @Inject
    lateinit var upstream: UpstreamClient

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @ConfigProperty(name = "openbank.edge.account-service-url")
    lateinit var accountServiceUrl: String

    @ConfigProperty(name = "openbank.edge.balance-service-url")
    lateinit var balanceServiceUrl: String

    @ConfigProperty(name = "openbank.edge.lending-service-url")
    lateinit var lendingServiceUrl: String

    @ConfigProperty(name = "openbank.edge.wealth-service-url")
    lateinit var wealthServiceUrl: String

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    /**
     * The number of accounts whose balances we will fetch individually.
     *
     * balance-service exposes no bulk read — only `/api/v1/balances/{accountId}` — so this branch
     * costs one upstream call per account. The cap is not a performance nicety: without it a party
     * with many accounts turns one customer request into an unbounded fan-out against a money-path
     * service. Past the cap the branch reports what it fetched and says it is TRUNCATED rather than
     * pretending the rest are zero.
     */
    private val maxAccounts = MAX_ACCOUNTS

    fun compose(partyId: UUID): ObjectNode {
        val root = objectMapper.createObjectNode()
        root.put("partyId", partyId.toString())
        root.put("asOf", Instant.now(clock).toString())

        val branches = objectMapper.createArrayNode()
        branches.add(cashBranch(partyId))
        branches.add(loansBranch(partyId))
        branches.add(declaredBranch(partyId))
        branches.add(entityStakesBranch(partyId))
        root.set<ArrayNode>("branches", branches)

        root.set<ObjectNode>("totals", totals(branches))
        return root
    }

    // ── branches ────────────────────────────────────────────────────────────

    /** On-platform cash: the caller's accounts, then one balance read per account. */
    private fun cashBranch(partyId: UUID): ObjectNode {
        val accounts = fetchArray("$accountServiceUrl/api/v1/accounts", partyId)
            ?: return branch(CASH, BranchStatus.UNAVAILABLE, "account-service did not answer")

        val leaves = objectMapper.createArrayNode()
        var truncated = false
        var anyBalanceFailed = false
        accounts.forEachIndexed { index, account ->
            if (index >= maxAccounts) {
                truncated = true
                return@forEachIndexed
            }
            val accountId = account.path("id").asText(null)
                ?: account.path("accountId").asText(null)
                ?: return@forEachIndexed
            val balance = fetchNode("$balanceServiceUrl/api/v1/balances/$accountId", partyId)
            if (balance == null) {
                // One account's balance missing does not zero it — the branch says so instead.
                anyBalanceFailed = true
                return@forEachIndexed
            }
            balance.path("balances").forEach { pocket ->
                leaves.add(
                    leaf(
                        owningService = "balance-service",
                        kind = "CASH",
                        label = account.path("iban").asText(accountId),
                        amount = pocket.path("bookedBalance").decimalValue(),
                        currency = pocket.path("currency").asText(null) ?: return@forEach,
                        liability = false,
                    ),
                )
            }
        }

        val b = branch(
            CASH,
            if (anyBalanceFailed) BranchStatus.UNAVAILABLE else BranchStatus.AVAILABLE,
            if (anyBalanceFailed) "at least one balance read failed" else null,
        )
        b.put("truncated", truncated)
        b.set<ArrayNode>("leaves", leaves)
        return b
    }

    /** Outstanding loans, as liabilities. */
    private fun loansBranch(partyId: UUID): ObjectNode {
        val loans = fetchArray("$lendingServiceUrl/api/v1/lending/loans?partyId=$partyId", partyId)
            ?: return branch(LOANS, BranchStatus.UNAVAILABLE, "lending-service did not answer")

        val leaves = objectMapper.createArrayNode()
        loans.forEach { loan ->
            val amount = loan.path("outstandingPrincipal").takeIf { !it.isMissingNode }
                ?: loan.path("principal")
            leaves.add(
                leaf(
                    owningService = "lending-service",
                    kind = "LOAN",
                    label = loan.path("productCode").asText(loan.path("id").asText("loan")),
                    amount = amount.decimalValue(),
                    currency = loan.path("currency").asText("CZK"),
                    liability = true,
                ),
            )
        }
        return branch(LOANS, BranchStatus.AVAILABLE, null).also { it.set<ArrayNode>("leaves", leaves) }
    }

    /**
     * Customer-declared off-platform holdings (ADR-0301).
     *
     * Every leaf here carries `valuationSource` and `valuationAgeDays` verbatim from
     * wealth-service. That labelling is not decoration: `CUSTOMER_DECLARED` means the customer
     * typed the number and nobody checked it, and a years-old figure looks identical to a fresh
     * one without the age. The app must render both; this composer refuses to strip them.
     */
    private fun declaredBranch(partyId: UUID): ObjectNode {
        val holdings = fetchArray("$wealthServiceUrl/api/v1/holdings", partyId)
            ?: return branch(DECLARED, BranchStatus.UNAVAILABLE, "wealth-service did not answer")

        val leaves = objectMapper.createArrayNode()
        holdings.forEach { h ->
            val l = leaf(
                owningService = "wealth-service",
                kind = h.path("holdingType").asText("DECLARED"),
                label = h.path("label").asText(""),
                amount = h.path("attributableAmount").decimalValue(),
                currency = h.path("currency").asText(null) ?: return@forEach,
                liability = h.path("isLiability").asBoolean(false),
            )
            l.put("valuationSource", h.path("valuationSource").asText(null))
            l.put("valuationAgeDays", h.path("valuationAgeDays").asLong(-1))
            leaves.add(l)
        }
        return branch(DECLARED, BranchStatus.AVAILABLE, null).also { it.set<ArrayNode>("leaves", leaves) }
    }

    /**
     * Companies the caller may act for (ADR-0284 D8), listed WITHOUT a value.
     *
     * A stake's worth is not knowable from the owner graph — party-service returns who may
     * represent whom, not what the entity is worth — so these leaves carry no amount and are
     * excluded from every total. Putting a zero here would be the same lie as a missing branch
     * contributing zero; naming them with `valued: false` lets the app show the customer their
     * companies without implying they are worthless. Valuing them is ADR-0302 D4 look-through.
     */
    private fun entityStakesBranch(partyId: UUID): ObjectNode {
        val profiles = fetchArray("$partyServiceUrl/api/v1/parties/$partyId/acting-for", partyId)
            ?: return branch(ENTITIES, BranchStatus.UNAVAILABLE, "party-service did not answer")

        val leaves = objectMapper.createArrayNode()
        profiles.forEach { p ->
            val l = objectMapper.createObjectNode()
            l.put("owningService", "party-service")
            l.put("kind", "ENTITY_STAKE")
            l.put("label", p.path("legalName").asText(p.path("partyId").asText("")))
            l.put("entityPartyId", p.path("partyId").asText(null))
            l.put("valued", false)
            leaves.add(l)
        }
        return branch(ENTITIES, BranchStatus.AVAILABLE, null).also { it.set<ArrayNode>("leaves", leaves) }
    }

    // ── assembly ────────────────────────────────────────────────────────────

    /**
     * Per-currency totals, plus the honesty fields.
     *
     * `complete` is false whenever ANY branch is UNAVAILABLE or truncated. A client that renders
     * the totals and ignores everything else still cannot present a partial figure as final,
     * because the field it would have to ignore to do so says otherwise.
     */
    private fun totals(branches: ArrayNode): ObjectNode {
        val byCurrency = linkedMapOf<String, BigDecimal>()
        var complete = true
        val contributing = objectMapper.createArrayNode()
        val missing = objectMapper.createArrayNode()

        branches.forEach { b ->
            val name = b.path("branch").asText()
            if (b.path("status").asText() != BranchStatus.AVAILABLE.name || b.path("truncated").asBoolean(false)) {
                complete = false
                missing.add(name)
                return@forEach
            }
            contributing.add(name)
            b.path("leaves").forEach { l ->
                if (!l.path("valued").asBoolean(true)) return@forEach
                val currency = l.path("currency").asText(null) ?: return@forEach
                val amount = l.path("amount").decimalValue()
                val signed = if (l.path("liability").asBoolean(false)) amount.negate() else amount
                byCurrency[currency] = (byCurrency[currency] ?: BigDecimal.ZERO).add(signed)
            }
        }

        val out = objectMapper.createObjectNode()
        val arr = objectMapper.createArrayNode()
        byCurrency.forEach { (currency, amount) ->
            val n = objectMapper.createObjectNode()
            n.put("currency", currency)
            n.put("amount", amount)
            arr.add(n)
        }
        out.set<ArrayNode>("byCurrency", arr)
        out.put("complete", complete)
        out.set<ArrayNode>("contributingBranches", contributing)
        out.set<ArrayNode>("missingBranches", missing)
        // Stated on the wire, not only in the docs: there is no converted grand total here, and a
        // client that wants one must convert through fx-service and show the rate (ADR-0024).
        out.put("converted", false)
        return out
    }

    private fun branch(name: String, status: BranchStatus, reason: String?): ObjectNode {
        val b = objectMapper.createObjectNode()
        b.put("branch", name)
        b.put("status", status.name)
        if (reason != null) b.put("reason", reason)
        b.put("truncated", false)
        b.set<ArrayNode>("leaves", objectMapper.createArrayNode())
        return b
    }

    @Suppress("LongParameterList") // one field per wire property; a DTO here would be the same list
    /**
     * The wire field is `owningService`, NOT `sourceService`.
     *
     * `sourceService` is a contract word in this fleet: it means the module that EMITTED an event,
     * and audit-service's TopicAttribution falls back to it, so a value disagreeing with the module
     * directory splits one producer into two in every group-by (#5256/#5902). This is a REST
     * response, not an event, and the value is deliberately a DIFFERENT service from the one
     * answering the request — reusing the name would have been a collision with an established
     * meaning, which `check-source-service-convention.py` caught.
     */
    private fun leaf(
        owningService: String,
        kind: String,
        label: String,
        amount: BigDecimal,
        currency: String,
        liability: Boolean,
    ): ObjectNode {
        val l = objectMapper.createObjectNode()
        l.put("owningService", owningService)
        l.put("kind", kind)
        l.put("label", label)
        l.put("amount", amount)
        l.put("currency", currency)
        l.put("liability", liability)
        l.put("valued", true)
        return l
    }

    // ── upstream access ─────────────────────────────────────────────────────

    /**
     * Null means "could not ask", which the caller turns into UNAVAILABLE. An empty array is a
     * different return and means the owner answered with nothing.
     */
    private fun fetchArray(url: String, partyId: UUID): ArrayNode? = fetchNode(url, partyId) as? ArrayNode

    private fun fetchNode(url: String, partyId: UUID): JsonNode? {
        val response = runCatching { upstream.get(url, partyId.toString()) }.getOrElse {
            Log.warnf(it, "net-worth: %s threw", url)
            return null
        }
        if (response.status != OK) {
            Log.debugf("net-worth: %s answered %d", url, response.status)
            return null
        }
        val body = response.entity as? String ?: return null
        return runCatching { objectMapper.readTree(body) }.getOrElse {
            Log.warnf(it, "net-worth: %s returned unparseable JSON", url)
            null
        }
    }

    private companion object {
        private val Log = Logger.getLogger(NetWorthComposer::class.java)
        const val OK = 200
        const val MAX_ACCOUNTS = 50
        const val CASH = "CASH"
        const val LOANS = "LOANS"
        const val DECLARED = "DECLARED_HOLDINGS"
        const val ENTITIES = "ENTITY_STAKES"
    }
}
