// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.InMemoryDpopReplayStore
import com.openbank.libs.security.Jose4jDpopProofParser
import com.openbank.mcp.TestJsonWebToken
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission.Outcome
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ADR-0383 D1/D2 + ADR-0385 D1 admission, one gate per test. Each negative case was also run with
 * its gate deleted from [CustomerChannelAdmission.admit] and went red (PR evidence).
 */
class CustomerChannelAdmissionTest {
    private val key = DpopTestProofs.newKey()
    private val allowlist = McpClientAllowlist.parse(
        javaClass.classLoader.getResource("test-mcp-client-allowlist.yaml")!!.readText(),
    )
    private val replayStore = InMemoryDpopReplayStore()
    private val admission = CustomerChannelAdmission(
        DpopTestProofs.ISSUER,
        allowlist,
        DpopProofVerifier(Jose4jDpopProofParser(), replayStore),
        Clock.systemUTC(),
    )

    /** No budget anywhere: every gate except the throttles is what is under test. */
    private object Unlimited : CustomerChannelAdmission.Throttles {
        override suspend fun proofKey(thumbprint: String): String? = null

        override suspend fun client(clientId: String): String? = null

        override suspend fun subject(subject: String, clientId: String): String? = null
    }

    /** The real shared limiter behind the three budgets, as McpCustomerBranch wires it. */
    private class LimiterThrottles(val limiter: McpRateLimiter = pinnedLimiter()) :
        CustomerChannelAdmission.Throttles {
        val spent = mutableListOf<String>()

        private fun refuse(key: String, o: McpRateLimiter.Outcome) =
            o.takeUnless { it == McpRateLimiter.Outcome.ALLOWED }?.reason.also { spent += key }

        override suspend fun proofKey(thumbprint: String) =
            CustomerChannelAdmission.proofKeyThrottleKey(thumbprint).let { refuse(it, limiter.check(it)) }

        override suspend fun client(clientId: String) =
            CustomerChannelAdmission.clientThrottleKey(clientId).let { refuse(it, limiter.checkShared(it)) }

        override suspend fun subject(subject: String, clientId: String) =
            CustomerChannelAdmission.throttleKey(subject, clientId).let { refuse(it, limiter.check(it)) }
    }

    private fun token(
        iss: String = DpopTestProofs.ISSUER,
        azp: String = "mcp-local-test",
        aud: Any = "openbank-mcp-service",
        jkt: String? = DpopTestProofs.jkt(key),
        sub: String = "c0ffee00-0000-0000-0000-000000000001",
    ) = TestJsonWebToken(
        buildMap {
            put("iss", iss)
            put("sub", sub)
            put("azp", azp)
            put("aud", aud)
            jkt?.let { put("cnf", mapOf("jkt" to it)) }
        },
    )

    private fun admit(
        jwt: TestJsonWebToken = token(),
        authorization: String? = "DPoP ${DpopTestProofs.ACCESS_TOKEN}",
        dpop: List<String> = listOf(DpopTestProofs.proof(key)),
        throttles: CustomerChannelAdmission.Throttles = Unlimited,
    ) = runBlocking { admission.admit(jwt, authorization, dpop, throttles) }

    private fun refused(outcome: Outcome, status: Int = 401): Outcome.Refused {
        assertThat(outcome).isInstanceOf(Outcome.Refused::class.java)
        return (outcome as Outcome.Refused).also { assertThat(it.status).isEqualTo(status) }
    }

    @Test
    fun `a DPoP-bound token from an allowlisted client with a valid proof is admitted on the customer channel`() {
        val outcome = admit()
        assertThat(outcome).isInstanceOf(Outcome.Admitted::class.java)
        val ctx = (outcome as Outcome.Admitted).context
        assertThat(ctx.channel).isEqualTo(CustomerChannelAdmission.CHANNEL)
        assertThat(ctx.principalType).isEqualTo("HUMAN")
        assertThat(ctx.serviceAccount).isFalse()
        assertThat(ctx.actChain).containsExactly("mcp-local-test")
        assertThat(ctx.consentId).isEmpty()
    }

    @Test
    fun `a staff-realm token is never admitted on the customer channel`() {
        assertThat(admission.isCustomerToken(token(iss = DpopTestProofs.STAFF_ISSUER))).isFalse()
        assertThat(refused(admit(jwt = token(iss = DpopTestProofs.STAFF_ISSUER))).challenge).contains("invalid_token")
    }

    @Test
    fun `an unknown or disabled client is refused`() {
        assertThat(refused(admit(jwt = token(azp = "mcp-not-declared"))).reason).isEqualTo("client is not admitted")
        assertThat(refused(admit(jwt = token(azp = "mcp-disabled-test"))).reason).isEqualTo("client is not admitted")
    }

    @Test
    fun `a token for another audience is refused`() {
        assertThat(refused(admit(jwt = token(aud = listOf("openbank-app")))).reason).contains("audience")
    }

    @Test
    fun `a token with no cnf_jkt is refused even for a hosted client`() {
        assertThat(refused(admit(jwt = token(jkt = null))).reason).isEqualTo("token is not DPoP-bound")
        assertThat(refused(admit(jwt = token(azp = "mcp-hosted-test", jkt = null))).reason)
            .isEqualTo("token is not DPoP-bound")
    }

    @Test
    fun `the Bearer scheme is refused`() {
        assertThat(refused(admit(authorization = "Bearer ${DpopTestProofs.ACCESS_TOKEN}")).reason)
            .isEqualTo("DPoP authorization scheme required")
    }

    @Test
    fun `a missing proof is refused with invalid_dpop_proof`() {
        val r = refused(admit(dpop = emptyList()))
        assertThat(r.reason).contains("MISSING_PROOF")
        assertThat(r.challenge).contains("invalid_dpop_proof").contains("resource_metadata=")
    }

    @Test
    fun `a replayed jti is refused the second time`() {
        val proof = DpopTestProofs.proof(key)
        assertThat(admit(dpop = listOf(proof))).isInstanceOf(Outcome.Admitted::class.java)
        assertThat(refused(admit(dpop = listOf(proof))).reason).contains("REPLAYED")
    }

    @Test
    fun `a proof for another URI is refused`() {
        assertThat(refused(admit(dpop = listOf(DpopTestProofs.proof(key, htu = "https://evil.example/mcp")))).reason)
            .contains("HTU_MISMATCH")
    }

    @Test
    fun `a proof outside the iat window is refused`() {
        val old = Instant.now().minus(Duration.ofMinutes(10))
        assertThat(refused(admit(dpop = listOf(DpopTestProofs.proof(key, iat = old)))).reason)
            .contains("IAT_OUT_OF_WINDOW")
    }

    @Test
    fun `a proof signed by another key than cnf_jkt is refused with invalid_token`() {
        val other = DpopTestProofs.newKey()
        val r = refused(admit(dpop = listOf(DpopTestProofs.proof(other))))
        assertThat(r.challenge).contains("invalid_token")
    }

    @Test
    fun `a proof over a different access token is refused`() {
        assertThat(refused(admit(dpop = listOf(DpopTestProofs.proof(key, token = "another-token")))).reason)
            .contains("ATH_MISMATCH")
    }

    @Test
    fun `a header token that differs from the validated raw token is refused`() {
        val jwt = TestJsonWebToken(
            mapOf(
                "iss" to DpopTestProofs.ISSUER,
                "sub" to "s",
                "azp" to "mcp-local-test",
                "aud" to "openbank-mcp-service",
                "cnf" to mapOf("jkt" to DpopTestProofs.jkt(key)),
                "raw_token" to "the-token-quarkus-validated",
            ),
        )
        assertThat(refused(admit(jwt = jwt)).reason).contains("does not match")
    }

    @Test
    fun `dpopToken accepts only the DPoP scheme with a single token`() {
        assertThat(CustomerChannelAdmission.dpopToken("dpop abc")).isEqualTo("abc")
        assertThat(CustomerChannelAdmission.dpopToken("Bearer abc")).isNull()
        assertThat(CustomerChannelAdmission.dpopToken("DPoP ")).isNull()
        assertThat(CustomerChannelAdmission.dpopToken("DPoP a b")).isNull()
        assertThat(CustomerChannelAdmission.dpopToken(null)).isNull()
    }

    @Test
    fun `a leaked token with proofs from another key spends no budget at all`() {
        // Attacker holds the victim's access token but not its key: every proof is signed by another key.
        val budgets = LimiterThrottles()
        val attacker = DpopTestProofs.newKey()
        repeat(100) { admit(dpop = listOf(DpopTestProofs.proof(attacker)), throttles = budgets) }
        assertThat(budgets.spent).isEmpty()
        assertThat(admit(throttles = budgets)).isInstanceOf(Outcome.Admitted::class.java)
    }

    @Test
    fun `a junk proof carrying the victim's PUBLIC key costs nothing`() {
        // jwk header = the victim's public key (thumbprint == cnf.jkt), signature by someone else.
        val budgets = LimiterThrottles()
        val attacker = DpopTestProofs.newKey()
        repeat(100) {
            val r = admit(dpop = listOf(DpopTestProofs.proof(attacker, headerKey = key)), throttles = budgets)
            assertThat((r as Outcome.Refused).reason).contains("BAD_SIGNATURE")
        }
        assertThat(budgets.spent).isEmpty()
        assertThat(admit(throttles = budgets)).isInstanceOf(Outcome.Admitted::class.java)
    }

    @Test
    fun `a header-less flood from one valid token cannot starve other subjects of the same client`() {
        val budgets = LimiterThrottles(pinnedLimiter().apply { sharedCallsPerMinute = SMALL_CLIENT_CAP })
        repeat(SMALL_CLIENT_CAP * 2) {
            assertThat(admit(dpop = emptyList(), throttles = budgets)).isInstanceOf(Outcome.Refused::class.java)
        }
        assertThat(budgets.spent).isEmpty()
        val other = DpopTestProofs.newKey()
        val outcome = admit(
            jwt = token(sub = "another-subject-of-the-same-client", jkt = DpopTestProofs.jkt(other)),
            dpop = listOf(DpopTestProofs.proof(other)),
            throttles = budgets,
        )
        assertThat(outcome).isInstanceOf(Outcome.Admitted::class.java)
    }

    @Test
    fun `the per-client cap is minute-only - the 1001st distinct subject in a day is admitted`() {
        val budgets = LimiterThrottles(pinnedLimiter().apply { sharedCallsPerMinute = 5_000 })
        val outcomes = (1..DAILY_BUDGET + 1).map { i ->
            val k = DpopTestProofs.newKey()
            admit(
                jwt = token(sub = "subject-$i", jkt = DpopTestProofs.jkt(k)),
                dpop = listOf(DpopTestProofs.proof(k)),
                throttles = budgets,
            )
        }
        assertThat(outcomes.last()).isInstanceOf(Outcome.Admitted::class.java)
        assertThat(outcomes).allMatch { it is Outcome.Admitted }
    }

    @Test
    fun `a proof-key flood is throttled before the replay claim, so the store stops growing`() {
        // One key bound to many tokens: only the proof-key bucket is shared by all of these calls.
        val budgets = LimiterThrottles()
        val before = replayStore.size()
        val statuses = (1..100).map { i ->
            when (val o = admit(jwt = token(sub = "flood-subject-$i"), throttles = budgets)) {
                is Outcome.Admitted -> 200
                is Outcome.Refused -> o.status
            }
        }
        assertThat(statuses.count { it == 429 }).isEqualTo(100 - PER_MINUTE)
        // Exactly one replay entry per call that got PAST the pre-verify bucket; none for a 429.
        assertThat(replayStore.size() - before).isEqualTo(PER_MINUTE)
    }

    @Test
    fun `the subject window is spent only after the proof is VALID`() {
        val budgets = LimiterThrottles()
        admit(dpop = listOf(DpopTestProofs.proof(key, htu = "https://evil.example/mcp")), throttles = budgets)
        assertThat(budgets.spent).noneMatch { it.startsWith("customer:") }
        admit(throttles = budgets)
        assertThat(budgets.spent.last()).startsWith("customer:")
    }

    @Test
    fun `a call refused by an earlier gate spends no budget`() {
        val budgets = LimiterThrottles()
        admit(jwt = token(azp = "mcp-not-declared"), throttles = budgets)
        assertThat(budgets.spent).isEmpty()
    }

    @Test
    fun `an unparseable proof spends no budget`() {
        val budgets = LimiterThrottles()
        admit(dpop = listOf("not-a-jws"), throttles = budgets)
        assertThat(budgets.spent).isEmpty()
    }

    private companion object {
        /**
         * A limiter on a FIXED clock: counted floods must not straddle a minute boundary, or the
         * window splits in two and fewer calls are throttled than the test asserts.
         */
        fun pinnedLimiter() = McpRateLimiter.withDefaults().apply {
            clock = Clock.fixed(Instant.parse("2026-11-18T10:00:30Z"), java.time.ZoneOffset.UTC)
        }

        /** `McpRateLimiter.withDefaults()` per-minute budget (the documented default). */
        const val PER_MINUTE = 60

        /** `McpRateLimiter.withDefaults()` per-caller daily budget. */
        const val DAILY_BUDGET = 1_000
        const val SMALL_CLIENT_CAP = 50
    }

    @Test
    fun `replaying one captured proof cannot drain the victim's proof-key bucket`() {
        val budgets = LimiterThrottles()
        val captured = DpopTestProofs.proof(key)
        assertThat(admit(dpop = listOf(captured), throttles = budgets)).isInstanceOf(Outcome.Admitted::class.java)
        repeat(100) {
            val r = admit(dpop = listOf(captured), throttles = budgets) as Outcome.Refused
            assertThat(r.reason).contains("REPLAYED")
            assertThat(r.status).isEqualTo(401)
        }
        // Only the one genuine use spent the proof-key bucket; the 100 replays spent nothing.
        assertThat(budgets.spent.count { it.startsWith("customer-dpop-jkt:") }).isEqualTo(1)
        repeat(PER_MINUTE - 1) {
            assertThat(admit(throttles = budgets)).isInstanceOf(Outcome.Admitted::class.java)
        }
    }
}
