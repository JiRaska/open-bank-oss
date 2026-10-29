// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.toolkit

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.product.ir.AccountEvent
import com.openbank.libs.product.ir.BalanceSample
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductArithmetic
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductIrValidationException
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.ir.ProductStateTransition
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one implementation behind every Product as Code authoring surface (ADR-0352 §11, §12): the
 * `obp` CLI and the agent-service MCP product tools call exactly these functions, so the two cannot
 * drift — the `product-ir-authoring-parity` gate compares their bytes.
 *
 * Every function is pure (no I/O, no clock, no network) and returns a tree that [CanonicalJson]
 * writes deterministically. Nothing here signs, approves or publishes: the identity-bound signature
 * belongs to the catalog publish path (ADR-0352 §4) and publishing to a human (ADR-0259), so neither
 * capability is reachable through this facade.
 */
object ProductToolkit {

    /** Schema, run-limits and `ruleId` uniqueness: the parser's whole validation, reported as data. */
    fun lint(irJson: String): Map<String, Any?> = try {
        val rev = ProductIrParser.parse(irJson)
        lintOk(rev)
    } catch (e: ProductIrValidationException) {
        mapOf("ok" to false, "problems" to e.problems.map { mapOf("path" to it.path, "message" to it.message) })
    }

    /** The lint report of an already-parsed (so already valid) revision. */
    fun lintOk(rev: ProductRevisionIr): Map<String, Any?> = mapOf(
        "ok" to true,
        "productCode" to rev.productCode,
        "irVersion" to rev.irVersion,
        "revisionHash" to rev.revisionHash,
        "ruleIds" to ruleIdPaths(rev).size,
    )

    /** Canonical IR bytes and their hash. Throws [ProductIrValidationException] for an invalid document. */
    fun compile(irJson: String): Compiled = compile(ProductIrParser.parse(irJson))

    fun compile(rev: ProductRevisionIr): Compiled = Compiled(ProductIrCanonical.canonicalJson(rev), rev.revisionHash)

    /** The canonical IR and its hash. [canonicalJson] is the exact byte sequence [revisionHash] covers. */
    data class Compiled(val canonicalJson: String, val revisionHash: String) {
        fun tree(): Map<String, Any?> =
            mapOf("ok" to true, "revisionHash" to revisionHash, "ir" to IrJson.parse(canonicalJson))
    }

    /**
     * Replays a portfolio fixture under [rev] through the one pure interpreter and returns every
     * instruction in canonical form. Fixture: `{"zone", "accounts": [{"id", "state", "steps"}]}` —
     * the golden scenario's state and step grammar without `expected`. Steps outside `[from, to)` are
     * skipped (state still carries forward only through evaluated steps).
     */
    fun simulate(
        rev: ProductRevisionIr,
        portfolioJson: String,
        arithmetic: ProductArithmetic,
        from: Instant? = null,
        to: Instant? = null,
    ): Map<String, Any?> {
        val root = PortfolioFixture.obj(IrJson.parse(portfolioJson), "portfolio")
        val zone = ZoneId.of(root["zone"] as? String ?: "UTC")
        val interpreter = ProductInterpreter(arithmetic)
        val accounts = PortfolioFixture.list(root["accounts"]).map { PortfolioFixture.obj(it, "account") }
        val replayed = accounts.map { account ->
            var state = PortfolioFixture.state(PortfolioFixture.obj(account["state"], "state"))
            val steps = PortfolioFixture.list(account["steps"]).map { PortfolioFixture.obj(it, "step") }
                .filter { s ->
                    Instant.parse(s.getValue("at") as String).let { PortfolioFixture.inWindow(it, from, to) }
                }
                .map { s ->
                    val at = Instant.parse(s.getValue("at") as String)
                    val event = PortfolioFixture.event(s)
                    val clock = ProductClock(at, zone)
                    val out = interpreter.evaluate(rev, state, event, clock)
                    state = PortfolioFixture.advance(state, out, event, clock.today())
                    mapOf("at" to at.toString(), "instructions" to out.map { InstructionCanonical.tree(it) })
                }
            mapOf("account" to account["id"], "steps" to steps)
        }
        return mapOf(
            "ok" to true,
            "productCode" to rev.productCode,
            "revisionHash" to rev.revisionHash,
            "from" to from?.toString(),
            "to" to to?.toString(),
            "accounts" to replayed,
        )
    }

    /** `ruleId` -> its JSON-pointer path in the canonical IR, plus a DSL location when [sourceOf] knows one. */
    fun explainRule(
        rev: ProductRevisionIr,
        ruleId: String,
        sourceOf: (String) -> String? = { null },
    ): Map<String, Any?> {
        val path = ruleIdPaths(rev)[ruleId]
            ?: return mapOf("ok" to false, "ruleId" to ruleId, "message" to "revision declares no such ruleId")
        return mapOf(
            "ok" to true,
            "productCode" to rev.productCode,
            "revisionHash" to rev.revisionHash,
            "ruleId" to ruleId,
            "irPath" to path,
            "source" to sourceOf(ruleId),
        )
    }

    /** Explains one emitted instruction: its `ruleId` (as [explainRule]) and whether it came from [rev]. */
    fun explainInstruction(
        rev: ProductRevisionIr,
        instructionJson: String,
        sourceOf: (String) -> String? = { null },
    ): Map<String, Any?> {
        val instruction = PortfolioFixture.obj(IrJson.parse(instructionJson), "instruction")
        val ruleId = instruction["ruleId"] as? String
            ?: return mapOf("ok" to false, "message" to "instruction carries no ruleId")
        val hash = instruction["revisionHash"] as? String
        if (hash != null && hash != rev.revisionHash) {
            return mapOf(
                "ok" to false,
                "ruleId" to ruleId,
                "message" to "instruction was emitted by revision $hash, not ${rev.revisionHash}",
            )
        }
        return explainRule(rev, ruleId, sourceOf) + ("instructionKind" to instruction["type"])
    }

    /**
     * What `propose` would send: a DRAFT revision on the catalog v2 API carrying [rev] as its
     * behaviour. A plan only — the caller decides whether to execute it, and no plan ever publishes.
     */
    fun proposalPlan(rev: ProductRevisionIr, offeringId: String, schemaRef: Map<String, Any?>): Map<String, Any?> {
        require(UUID_PATTERN.matches(offeringId)) { "offeringId must be a UUID" }
        return mapOf(
            "ok" to true,
            "action" to "create-draft-revision",
            "method" to "POST",
            "path" to "/api/v2/offerings/$offeringId/revisions",
            "publishes" to false,
            "revisionHash" to rev.revisionHash,
            "body" to mapOf(
                "schemaRef" to schemaRef,
                "name" to mapOf("en" to rev.productCode),
                "attributes" to emptyMap<String, Any?>(),
                "behaviour" to IrJson.parse(ProductIrCanonical.canonicalJson(rev)),
            ),
        )
    }

    /** Every `ruleId` in the canonical IR tree, keyed to its JSON pointer (RFC 6901). */
    fun ruleIdPaths(rev: ProductRevisionIr): Map<String, String> {
        val out = sortedMapOf<String, String>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is Map<*, *> -> {
                    (node["ruleId"] as? String)?.let { out.putIfAbsent(it, path.ifEmpty { "/" }) }
                    node.forEach { (k, v) -> walk(v, "$path/${(k as String).replace("~", "~0").replace("/", "~1")}") }
                }
                is List<*> -> node.forEachIndexed { i, v -> walk(v, "$path/$i") }
                else -> Unit
            }
        }
        walk(ProductIrCanonical.tree(rev), "")
        return out
    }

    /** Writes a toolkit result the way every surface prints it. */
    fun write(tree: Map<String, Any?>): String = CanonicalJson.write(tree)

    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}

/** The portfolio-fixture grammar (the golden scenario's state and step shape) and the replay step. */
internal object PortfolioFixture {

    fun inWindow(at: Instant, from: Instant?, to: Instant?): Boolean =
        (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to))

    fun advance(state: ProductState, out: List<Instruction>, event: ProductEvent, today: LocalDate): ProductState {
        var next = ProductStateTransition.apply(state, out, today)
        val posting = event.posting
        val rejected = out.any { it is Instruction.Reject }
        if (posting != null && event.hook == Hook.PRE_POSTING && !rejected) {
            next = ProductStateTransition.applyPosting(next, posting, today)
        }
        return next
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?, what: String): Map<String, Any?> =
        v as? Map<String, Any?> ?: throw IllegalArgumentException("$what must be a JSON object")

    fun list(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    private fun dec(v: Any?): BigDecimal = when (v) {
        is BigDecimal -> v
        is String -> BigDecimal(v)
        null -> BigDecimal.ZERO
        else -> throw IllegalArgumentException("not a decimal: $v")
    }

    fun state(t: Map<String, Any?>): ProductState = ProductState(
        currency = t.getValue("currency") as String,
        balance = dec(t["balance"]),
        accruedInterest = dec(t["accruedInterest"]),
        openedOn = LocalDate.parse(t.getValue("openedOn") as String),
        segment = t["segment"] as? String,
        monthlyTurnover = t["monthlyTurnover"]?.let { dec(it) },
        aggregatePocketBalance = t["aggregatePocketBalance"]?.let { dec(it) },
        events = list(t["events"]).map { obj(it, "event") }.map {
            AccountEvent(
                EventKind.valueOf(it.getValue("kind") as String),
                LocalDate.parse(it.getValue("at") as String),
                dec(it["amount"]),
            )
        },
        balanceSamples = list(t["balanceSamples"]).map { obj(it, "balanceSample") }.map {
            BalanceSample(LocalDate.parse(it.getValue("on") as String), dec(it["balance"]))
        },
        parameters = (t["parameters"] as? Map<*, *> ?: emptyMap<String, Any?>())
            .entries.associate { (k, v) -> k as String to dec(v) },
    )

    fun event(t: Map<String, Any?>): ProductEvent {
        val hook = ProductIrParser.HOOK_KEYS[t["hook"]]
            ?: throw IllegalArgumentException("unknown hook '${t["hook"]}'")
        val posting = (t["posting"] as? Map<*, *>)?.let {
            Posting(
                EventKind.valueOf(it["kind"] as String),
                PostingDirection.valueOf(it["direction"] as String),
                dec(it["amount"]),
            )
        }
        return ProductEvent(
            hook = hook,
            tag = t["tag"] as? String,
            posting = posting,
            parameter = t["parameter"] as? String,
            newValue = t["newValue"]?.let { dec(it) },
        )
    }
}
