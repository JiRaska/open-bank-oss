// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.mcp.FuzzPayloads.Payload
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.McpPiiMasker
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.PolicyFilteredToolCatalog
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.port.out.StatementReadPort
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #16617, layer 5: property-style fuzzing of every `tools/call` argument, in process, with
 * the REAL [McpEndpoint] + [McpToolRegistry] over counting ports and an allow-all PDP — so every
 * argument is genuinely reachable and a call that gets past validation is VISIBLE in [Ports.calls].
 * The wire-level sibling is [McpToolCallWireFuzzIT]; the real downstream adapters are covered by
 * `McpDownstreamFuzzTest`.
 *
 * Invariants, for every (tool, slot, payload):
 *  1. the endpoint returns (a throw is what the platform renders as HTTP 500);
 *  2. the response serializes and is exactly one of result / error, with the id echoed;
 *  3. it carries no stack trace and no internal class name;
 *  4. it stays within [RESPONSE_CAP_BYTES] — except the reflection deviations tracked by the
 *     disabled tests at the bottom of this class (the payload there is echoed back verbatim);
 *  5. an argument of the wrong JSON type, or an invalid `propose_payment` field, never reaches a port.
 *
 * Known-positive proof is in this class too (the `sabotage` tests): the same checker is run against
 * deliberately broken ports and must report a throw, a leak, an oversize response and a port reach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolCallFuzzTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private class Ports(private val m: ObjectMapper) :
        AccountReadPort,
        StatementReadPort,
        PaymentConfirmationReadPort,
        ProposalPort {
        val calls = CopyOnWriteArrayList<String>()
        val limits = CopyOnWriteArrayList<Int>()

        /** Sabotage hook for the known-positive tests; a no-op in every real sweep. */
        var onCall: (String) -> Unit = {}
        private fun hit(name: String): JsonNode {
            calls.add(name)
            onCall(name)
            return m.createObjectNode().put("ok", true)
        }
        override fun listAccounts(consentContext: ConsentContext) = hit("list_accounts")
        override fun getBalance(consentContext: ConsentContext, accountId: String) = hit("get_balance")
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode {
            limits.add(limit)
            return hit("list_transactions")
        }
        override fun listConsents(consentContext: ConsentContext) = hit("list_consents")
        override fun getStatementSummary(
            consentContext: ConsentContext,
            accountId: String,
            currency: String?,
            legalSequence: Long?,
        ) = hit("get_statement")
        override fun getPaymentConfirmation(consentContext: ConsentContext, paymentId: String) =
            hit("get_payment_confirmation")
        override fun proposePayment(consentContext: ConsentContext, request: JsonNode) = hit("propose_payment")
    }

    private class Harness(val mapper: ObjectMapper) {
        val ports = Ports(mapper)
        val audit = CopyOnWriteArrayList<AuditEvent>()
        val meters = SimpleMeterRegistry()
        private val allow = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = true)
        }
        private val limiter = McpRateLimiter.withDefaults().apply { enabled = false }
        private val registry = McpToolRegistry(
            ports,
            ports,
            ports,
            ports,
            StubMarketingReachPort(mapper),
            McpPiiMasker(mapper),
            mapper,
        )
        val endpoint = McpEndpoint(
            registry = registry,
            pdp = allow,
            auditor = McpCallAuditor(
                object : AuditEventPublisher {
                    override suspend fun publish(event: AuditEvent) {
                        audit.add(event)
                    }
                },
            ),
            callerResolver = CallerContextResolver(
                TestJsonWebToken(
                    mapOf(
                        "sub" to "agent:fuzz",
                        "aud" to "openbank-mcp-service",
                        "consent_id" to "6a1f5f38-59a5-4e77-9f0d-2b1a7a2f1c11",
                    ),
                ),
                customerIssuer = java.util.Optional.empty(),
            ),
            mapper = mapper,
            serverName = "openbank-mcp",
            serverVersion = "0.1.0",
            protocolVersion = "2025-06-18",
        ).apply {
            metrics = McpMetricsAdapter(meters)
            rateLimiter = limiter
            toolsCatalog = PolicyFilteredToolCatalog(registry, allow, 0)
        }
    }

    /** One fuzz request: raw JSON-RPC text plus what the invariants need to know about it. */
    data class Case(
        val label: String,
        /** Built on demand: ~1 MiB requests held for all ~2000 cases at once exhaust the test heap. */
        val build: () -> String,
        val mustNotReachPort: Boolean,
        val tool: String,
        val payload: Payload,
        val echoesInput: Boolean = false,
    ) {
        override fun toString() = label
    }

    // -- invariants ------------------------------------------------------------------------------

    /** Returns the violations found; an empty list means the case held every invariant. */
    private fun violations(h: Harness, case: Case): List<String> {
        val request = try {
            h.mapper.readTree(case.build())
        } catch (ex: JsonProcessingException) {
            // Over the parser's nesting limit the platform answers 400 before the endpoint runs;
            // the wire-level sweep asserts that. Anything ELSE unparseable is a corpus bug.
            return if (case.payload.isUnparseableDeep) emptyList() else listOf("corpus produced invalid JSON: $ex")
        }
        val before = h.ports.calls.size
        val entity = try {
            h.endpoint.handle(request).entity
        } catch (ex: Throwable) {
            return listOf("endpoint threw ${ex.javaClass.simpleName} (HTTP 500)")
        }
        val out = mutableListOf<String>()
        val text = try {
            h.mapper.writeValueAsString(entity)
        } catch (ex: Throwable) {
            return listOf("response could not be serialized: ${ex.javaClass.simpleName} (HTTP 500)")
        }
        val body = h.mapper.readTree(text)
        if (body.has("result") == body.has("error")) out += "must carry exactly one of result/error"
        if (body.path("id").asInt(-1) != 1) out += "id not echoed"
        LEAK_MARKERS.filter { text.contains(it) }.forEach { out += "leaks '$it'" }
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > RESPONSE_CAP_BYTES && !case.echoesInput) out += "response is $size bytes (cap $RESPONSE_CAP_BYTES)"
        if (case.mustNotReachPort && h.ports.calls.size != before) {
            out += "invalid input reached downstream port ${h.ports.calls.drop(before)}"
        }
        return out
    }

    private fun assertHolds(case: Case) {
        val h = Harness(mapper)
        assertThat(violations(h, case)).`as`("case %s", case.label).isEmpty()
    }

    // -- corpus ----------------------------------------------------------------------------------

    private val goodArgs = mapOf(
        "accountId" to "\"CZ6508000000192000145399\"",
        "paymentId" to "\"6a1f5f38-59a5-4e77-9f0d-2b1a7a2f1c11\"",
        "limit" to "10",
        "currency" to "\"CZK\"",
        "legalSequence" to "3",
        "fromAccountId" to "\"CZ6508000000192000145399\"",
        "toIban" to "\"CZ6508000000192000145399\"",
        "amount" to "\"10.00\"",
    )

    private val slotsByTool: Map<String, List<String>> = mapOf(
        "list_accounts" to emptyList(),
        "list_consents" to emptyList(),
        "count_marketing_consents" to emptyList(),
        "get_balance" to listOf("accountId"),
        "list_transactions" to listOf("accountId", "limit"),
        "get_statement" to listOf("accountId", "currency", "legalSequence"),
        "get_payment_confirmation" to listOf("paymentId"),
        "propose_payment" to listOf("fromAccountId", "toIban", "amount", "currency"),
    )

    private val requiredSlots: Map<String, Set<String>> = mapOf(
        "get_balance" to setOf("accountId"),
        "list_transactions" to setOf("accountId"),
        "get_statement" to setOf("accountId"),
        "get_payment_confirmation" to setOf("paymentId"),
        "propose_payment" to setOf("fromAccountId", "toIban", "amount", "currency"),
    )

    private fun call(tool: String, argumentsJson: String) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argumentsJson}}"""

    private fun slotCases(): List<Case> = slotsByTool.flatMap { (tool, slots) ->
        slots.flatMap { slot ->
            FuzzPayloads.all.map { p ->
                val args = (slotsByTool.getValue(tool).associateWith { goodArgs.getValue(it) } + (slot to p.json))
                    .entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
                // A slot filled with the wrong JSON type must never reach a port. For propose_payment
                // EVERY corpus value is invalid for every slot (the corpus holds no valid IBAN, amount
                // or currency), and ProposePaymentArgs sits in front of the port.
                // fromAccountId is only checked for presence here (the CONSENT check is the port's job),
                // so a non-blank string is valid for it; every other corpus value is invalid.
                val mustNot = (tool == "propose_payment" && (slot != "fromAccountId" || !p.isString)) ||
                    (slot in requiredSlots[tool].orEmpty() && !p.isString)
                Case("$tool.$slot=${p.label}", { call(tool, args) }, mustNot, tool, p)
            }
        }
    }

    private fun wholeArgumentCases(): List<Case> = slotsByTool.keys.flatMap { tool ->
        FuzzPayloads.all.map { p ->
            // No corpus value is a valid argument object for a tool with required arguments.
            val mustNot = tool in requiredSlots
            Case("$tool.arguments=${p.label}", { call(tool, p.json) }, mustNot, tool, p)
        }
    }

    private fun unknownKeyCases(): List<Case> = slotsByTool.keys.flatMap { tool ->
        FuzzPayloads.strings.filter { it.isString && !it.isHuge }.map { p ->
            val good = slotsByTool.getValue(tool).map { "\"$it\":${goodArgs.getValue(it)}" }
            // The payload is already a quoted JSON string, so it is used as the key text verbatim.
            Case("$tool.extra-key=${p.label}", {
                call(tool, (good + "${p.json}:1").joinToString(",", "{", "}"))
            }, false, tool, p)
        }
    }

    private fun randomisedCases(): List<Case> = slotsByTool.filterValues { it.isNotEmpty() }.flatMap { (tool, slots) ->
        FuzzPayloads.randomised(seed = tool.hashCode().toLong(), count = 40).flatMap { p ->
            slots.map { slot ->
                val args = (slots.associateWith { goodArgs.getValue(it) } + (slot to p.json))
                    .entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
                Case(
                    "$tool.$slot=${p.label}",
                    { call(tool, args) },
                    tool == "propose_payment" && slot != "fromAccountId",
                    tool,
                    p,
                )
            }
        }
    }

    private fun paramsCases(): List<Case> = FuzzPayloads.all.map { p ->
        Case(
            "params=${p.label}",
            { """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":${p.json}}""" },
            mustNotReachPort = true,
            tool = "-",
            payload = p,
        )
    }

    /** `name` fuzz: the response echoes an unmapped name, so size is judged separately (F1). */
    private fun nameCases(): List<Case> = FuzzPayloads.all.map { p ->
        Case(
            "name=${p.label}",
            { """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":${p.json},"arguments":{}}}""" },
            mustNotReachPort = true,
            tool = "-",
            payload = p,
            echoesInput = p.isString,
        )
    }

    @Suppress("unused")
    fun slotCorpus() = slotCases()

    @Suppress("unused")
    fun wholeArgumentCorpus() = wholeArgumentCases()

    @Suppress("unused")
    fun unknownKeyCorpus() = unknownKeyCases()

    @Suppress("unused")
    fun randomisedCorpus() = randomisedCases()

    @Suppress("unused")
    fun paramsCorpus() = paramsCases()

    @Suppress("unused")
    fun nameCorpus() = nameCases()

    @ParameterizedTest(name = "{0}")
    @MethodSource("slotCorpus")
    fun `every argument slot of every tool survives every hostile value`(case: Case) = assertHolds(case)

    @ParameterizedTest(name = "{0}")
    @MethodSource("wholeArgumentCorpus")
    fun `a hostile value as the whole arguments member is survived`(case: Case) = assertHolds(case)

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownKeyCorpus")
    fun `a hostile argument KEY is survived`(case: Case) = assertHolds(case)

    @ParameterizedTest(name = "{0}")
    @MethodSource("randomisedCorpus")
    fun `seeded random strings in every slot are survived`(case: Case) = assertHolds(case)

    @ParameterizedTest(name = "{0}")
    @MethodSource("paramsCorpus")
    fun `a hostile value as params is survived`(case: Case) = assertHolds(case)

    @ParameterizedTest(name = "{0}")
    @MethodSource("nameCorpus")
    fun `a hostile tool name is survived`(case: Case) = assertHolds(case)

    @Test
    fun `corpus is not vacuous - it covers every tool, every slot and every hostile class`() {
        assertThat(slotCases().map { it.tool }.toSet()).containsAll(slotsByTool.filterValues { it.isNotEmpty() }.keys)
        listOf("nul", "lone-high-surrogate", "one-megabyte-ascii", "iban-wrong-checksum", "number-9223372036854775808")
            .forEach { label -> assertThat(FuzzPayloads.all.map { it.label }).contains(label) }
        assertThat(FuzzPayloads.all.count { it.isUnparseableDeep }).isEqualTo(2)
        assertThat(FuzzPayloads.all.any { it.isHuge && it.isString }).isTrue()
        assertThat(slotCases().size + wholeArgumentCases().size).isGreaterThan(1_000)
    }

    // -- known-positive proof: the checker must flag each failure class when it is injected ----------

    private val probe = Payload("probe", "1")

    private fun sabotaged(onCall: (String) -> Unit, mustNotReach: Boolean = false): List<String> {
        val h = Harness(mapper)
        h.ports.onCall = onCall
        return violations(h, Case("sabotage", { call("list_accounts", "{}") }, mustNotReach, "list_accounts", probe))
    }

    @Test
    fun `sabotage - an escaping Error is reported as an HTTP 500`() {
        assertThat(sabotaged({ throw AssertionError("boom") })).anyMatch { it.contains("HTTP 500") }
    }

    @Test
    fun `sabotage - a stack trace in a client-visible message is reported as a leak`() {
        val leaked = sabotaged({ throw IllegalArgumentException("x\n\tat com.openbank.Secret.run(Secret.kt:1)") })
        assertThat(leaked).anyMatch { it.startsWith("leaks") }
    }

    @Test
    fun `sabotage - an oversize response is reported`() {
        val big = sabotaged({ throw IllegalArgumentException("A".repeat(RESPONSE_CAP_BYTES * 2)) })
        assertThat(big).anyMatch { it.contains("cap") }
    }

    @Test
    fun `sabotage - invalid input reaching a port is reported`() {
        assertThat(sabotaged({}, mustNotReach = true)).anyMatch { it.contains("reached downstream port") }
    }

    @Test
    fun `an unsabotaged call is clean, so the four reports above are caused by the sabotage`() {
        assertThat(sabotaged({})).isEmpty()
    }

    // -- availability SLI input: a client's bad arguments must not read as a server fault ----------------

    private fun failureCount(kind: String, onCall: (String) -> Unit): Double {
        val h = Harness(mapper)
        h.ports.onCall = onCall
        h.endpoint.handle(mapper.readTree(call("list_accounts", "{}")))
        return h.meters.find("openbank.mcp.tool_call.failures").tag("kind", kind).counter()?.count() ?: 0.0
    }

    @Test
    fun `invalid params is metered as a client error, not a server error`() {
        assertThat(failureCount("invalid_params") { throw IllegalArgumentException("bad") }).isEqualTo(1.0)
        assertThat(failureCount("error") { throw IllegalArgumentException("bad") }).isEqualTo(0.0)
    }

    @Test
    fun `a downstream fault is metered as a server error`() {
        assertThat(failureCount("error") { throw IllegalStateException("downstream") }).isEqualTo(1.0)
        assertThat(failureCount("invalid_params") { throw IllegalStateException("downstream") }).isEqualTo(0.0)
    }

    @Test
    fun `an unwired tool is metered as unavailable, and a success meters no failure`() {
        assertThat(failureCount("unavailable") { throw UnsupportedOperationException("not wired") }).isEqualTo(1.0)
        assertThat(failureCount("error") {}).isEqualTo(0.0)
    }

    // -- known deviations (disabled; see PR body) ---------------------------------------------------

    @Test
    fun `a megabyte tool name does not amplify into the response`() {
        val h = Harness(mapper)
        val p = FuzzPayloads.all.first { it.label == "one-megabyte-ascii" }
        val case = nameCases().first { it.payload == p }.copy(echoesInput = false)
        assertThat(violations(h, case)).isEmpty()
    }

    @Test
    fun `list_transactions never forwards a non-positive or unbounded limit`() {
        val h = Harness(mapper)
        listOf(
            "0",
            "-1",
            "201",
            "-2147483649",
            "4294967296",
            "2147483647",
            "9223372036854775807",
            "1e308",
        ).forEach { limit ->
            h.endpoint.handle(
                mapper.readTree(
                    call("list_transactions", """{"accountId":"CZ6508000000192000145399","limit":$limit}"""),
                ),
            )
        }
        assertThat(h.ports.limits).`as`("limits forwarded downstream").allMatch { it in 1..MAX_SANE_LIMIT }
    }

    @Test
    fun `audit events stay bounded whatever the caller names its arguments`() {
        val h = Harness(mapper)
        // Built as a tree: a 1 MiB name is refused by the parser on the wire (see the wire sweep),
        // so this isolates what the endpoint does if one ever arrives.
        val key = "K".repeat(FuzzPayloads.MEGABYTE)
        val args = mapper.createObjectNode().put(key, 1)
        h.endpoint.handle(
            mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .set<com.fasterxml.jackson.databind.node.ObjectNode>(
                    "params",
                    mapper.createObjectNode().put("name", "list_accounts").set("arguments", args),
                ),
        )
        assertThat(h.audit).isNotEmpty
        h.audit.forEach {
            assertThat(it.payload.toString().length).isLessThan(AUDIT_CAP_BYTES)
        }
    }

    @Test
    fun `a NUL inside a non-object root is refused, metered and never dispatched`() {
        val h = Harness(mapper)
        val arrayRoot = mapper.readTree(
            """[{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts","arguments":{"a\u0000":1}}}]""",
        )
        val resp = h.endpoint.handle(arrayRoot)
        assertThat(resp.status).isEqualTo(400)
        val body = mapper.readTree(mapper.writeValueAsString(resp.entity))
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602)
        assertThat(h.audit).isEmpty()
        assertThat(h.ports.limits).isEmpty()
        assertThat(
            h.meters.find("openbank.mcp.input_refused").tag("reason", "nul").counter()?.count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `control - a NUL-free request does not tick the refusal counter`() {
        val h = Harness(mapper)
        h.endpoint.handle(mapper.readTree(call("list_accounts", "{}")))
        assertThat(h.meters.find("openbank.mcp.input_refused").counter()).isNull()
    }

    private companion object {
        const val RESPONSE_CAP_BYTES = 64 * 1024
        const val AUDIT_CAP_BYTES = 64 * 1024
        const val MAX_SANE_LIMIT = 200
        val LEAK_MARKERS = listOf(
            "\tat ", "Caused by", "java.lang", "java.util", "kotlin.", "jakarta.", "io.quarkus", "com.openbank",
            "Exception", "NullPointer", "ClassCast", "NumberFormat",
        )
    }
}
