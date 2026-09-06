// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

/**
 * The four-eyes approval for `transaction.sweep` must bind to the SPECIFIC sweep (#4754).
 *
 * `POST /api/v1/transactions/merge-sweep` is `ROLE_OPERATOR`-gated and moves a balance; combined
 * with a party merge it is the second half of the account-takeover path #1984 documents. The
 * interceptor stamps [com.openbank.libs.approval.PendingApproval.resourceId] from
 * `query.resource?.id`, so an `@Authorize(resource = "")` makes every approval — and every match —
 * carry `resourceId = null`, and an approval granted for one sweep silently satisfies a different
 * sweep by the same maker.
 *
 * Why this is an IT and not a unit test, and why BOTH assertions are here:
 *
 *  1. **A real HTTP request.** The resource expression is resolved by the CDI interceptor via
 *     Kotlin reflection over the intercepted method's parameters; a unit test that constructs a
 *     `PendingApproval` directly, or calls the resource class, never exercises that extraction at
 *     all and passes against `resource = ""`. Only a request through RestAssured does.
 *  2. **The negative is the control.** `resourceId != null` alone would also pass if the binding
 *     were wrong-but-present. `an approval for sweep A must not satisfy sweep B` (step 3) is the
 *     assertion that fails against the pre-#4754 annotation — verified by reverting it, where that
 *     step answers 201 Created instead of a fresh 202 PENDING_APPROVAL.
 *
 * Ordered steps with per-step `@TestSecurity` rather than one method, because the maker and the
 * checker MUST be different principals: `ApprovalStore.decide` enforces segregation of duties
 * itself and throws [com.openbank.libs.approval.SelfApprovalNotAllowedException] if they match, so
 * a single-identity test could not approve anything. Same shape as [TransactionApiIT].
 *
 * Four-eyes is force-enabled by [FourEyesProfile] here only. `AUTHZ_FOUR_EYES_ENFORCE` stays false
 * in `application.yaml` and in gitops — flipping it is a separate decision with its own precondition
 * (a second operator identity must exist, or every gated action stalls at 202), deliberately out of
 * scope for #4754. The profile also swaps the OPA sidecar PDP for [AlwaysFourEyesPdp]: the real one
 * is unreachable in a test and its failure would be swallowed, leaving the gate untested. What the
 * deployed policy actually answers for this action is measured separately with `opa eval` against
 * the bundle ConfigMap (see the PR description).
 *
 * [LedgerWireMockResource] is required, not optional wiring. Step 4 is the one step that clears the
 * gate and reaches [com.openbank.transaction.application.usecase.TransactionService.initiateTransaction],
 * which blocks the HTTP request on a real (in-JVM) Temporal workflow that calls out over HTTP to place
 * a balance-service cover hold (`PaymentWorkflowImpl` -> `PaymentActivitiesImpl.placeHold`). Without this
 * resource that call targets the unstubbed default `balance-service` URL: on some runners that connects
 * (rather than failing fast with ECONNREFUSED) and then sits without a response, so the client-side read
 * eventually throws `SocketTimeoutException` — and because `stub.execute()` pins an IO-dispatcher thread
 * and a Temporal workflow task for up to the activity's `scheduleToCloseTimeout` per retry attempt, a
 * timed-out step 4 can leave that workflow retrying in the background well after the test method itself
 * has failed, stalling whatever later test next contends for the same pinned resources. Stubbing the
 * hold (and the journal) — same as [PaymentWorkflowTerminalWriteIT] — makes step 4 resolve in-process
 * with no real network call at all, which is what the other ordered steps already get for free by never
 * reaching the workflow.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@QuarkusTestResource(LedgerWireMockResource::class)
@TestProfile(MergeSweepApprovalBindingIT.FourEyesProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MergeSweepApprovalBindingIT {

    /**
     * Stands in for the OPA sidecar: allows every call, and flags `four_eyes_required` for
     * `transaction.sweep` ONLY — which is exactly what the deployed bundle answers, since
     * `rules.yaml four_eyes.verbs` lists `sweep` and does not list `decide`. Enabled as a CDI
     * `@Alternative` via the profile below, so it overrides the service's own `AuthzProducer`
     * for this test only.
     *
     * Scoping by action is load-bearing, not tidiness. `ApprovalResource.decide` is itself
     * `@Authorize(action = "transaction.approval.decide")`, so a stub that flagged EVERY action
     * makes approving an approval transitively require approving that approval: step 2's PATCH
     * answers 202 PENDING_APPROVAL instead of 200, and steps 3 and 4 then run against an approval
     * that was never granted. Steps 2 and 4 fail outright; step 3 — the negative that is the whole
     * point of this test — passes VACUOUSLY, because a gate that pauses everything also pauses the
     * mismatched replay it is supposed to catch. A fixture that gates one action too many cannot
     * observe the binding this test exists to prove.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    class AlwaysFourEyesPdp : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision = AuthzDecision(
            allow = true,
            attributes = mapOf("four_eyes_required" to (query.action == FOUR_EYES_ACTION)),
        )
    }

    class FourEyesProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "authz.four-eyes.enforce" to "true",
            "authz.enforce" to "true",
        )

        override fun getEnabledAlternatives() = setOf<Class<*>>(AlwaysFourEyesPdp::class.java)
    }

    /**
     * Step 1 — the maker requests sweep A. A `four_eyes_required` action must be paused with a
     * PendingApproval, never executed on the first call.
     */
    @Test
    @Order(1)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `a four-eyes sweep is paused and mints a pending approval`() {
        val paused = postSweep(sweepPayload(KEY_A, "MERGE-2026-0001"))

        assertThat(paused.statusCode())
            .describedAs("a four_eyes_required sweep must be paused, not executed")
            .isEqualTo(PENDING_APPROVAL)
        assertThat(paused.jsonPath().getString("status")).isEqualTo("PENDING_APPROVAL")

        approvalForSweepA = paused.jsonPath().getString("approvalId")
        assertThat(approvalForSweepA).isNotBlank()
    }

    /**
     * Step 2 — a DIFFERENT operator approves it. The decide response echoes the stored
     * `resourceId`, so this reads the real persisted binding produced by the real HTTP request in
     * step 1. A null here is the #4754 defect itself.
     */
    @Test
    @Order(2)
    @TestSecurity(user = CHECKER, roles = ["ROLE_OPERATOR"])
    fun `the stored approval is bound to the specific sweep`() {
        val approved = RestAssured.given()
            .contentType("application/json")
            .body("""{"approve": true}""")
            .patch("/api/v1/transactions/approvals/$approvalForSweepA")
            .then()
            .statusCode(200)
            .extract()

        assertThat(approved.jsonPath().getString("status")).isEqualTo("APPROVED")
        assertThat(approved.jsonPath().getString("action")).isEqualTo("transaction.sweep")
        assertThat(approved.jsonPath().getString("resourceId"))
            .describedAs(
                "the approval must be bound to the specific sweep — a null resourceId is the #4754 " +
                    "defect, where any sweep by this maker would satisfy it",
            )
            .isEqualTo(KEY_A)
    }

    /**
     * Step 3 — **the negative, and the whole point of this test.** The maker replays the approval
     * granted for sweep A against sweep B: different idempotency key, different accounts, different
     * merge. It must not be honoured. Against `resource = ""` both approvals carry `resourceId=null`,
     * `AuthorizeInterceptor.satisfies` returns true, and this answers 201 Created — the balance moves
     * on an approval nobody granted for it.
     */
    @Test
    @Order(3)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `an approval issued for sweep A does not satisfy sweep B by the same maker`() {
        val replayed = postSweep(sweepPayload(KEY_B, "MERGE-2026-0002"), approvalId = approvalForSweepA)

        assertThat(replayed.statusCode())
            .describedAs(
                "an approval for sweep A must not satisfy sweep B — the maker must be paused again " +
                    "for a second approver, not allowed to move the balance",
            )
            .isEqualTo(PENDING_APPROVAL)
        assertThat(replayed.jsonPath().getString("approvalId"))
            .describedAs("a NEW pending approval is re-issued rather than the mismatched one consumed")
            .isNotEqualTo(approvalForSweepA)
    }

    /**
     * Step 4 — positive control, so step 3 cannot pass merely because the gate rejects everything.
     * The same approval DOES unlock the sweep it was granted for. The sweep then reaches the
     * handler, whose own outcome depends on account state — anything other than a 202 pause proves
     * the four-eyes gate let it through, which is what is under test here.
     */
    @Test
    @Order(4)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `the approval still satisfies the exact sweep it was issued for`() {
        val replayed = postSweep(sweepPayload(KEY_A, "MERGE-2026-0001"), approvalId = approvalForSweepA)

        assertThat(replayed.statusCode())
            .describedAs("the approval must still satisfy the exact sweep it was issued for")
            .isNotEqualTo(PENDING_APPROVAL)
    }

    private fun sweepPayload(idempotencyKey: String, mergeReference: String) = """
        {
          "idempotencyKey": "$idempotencyKey",
          "sourceAccountId": "${UUID.randomUUID()}",
          "targetAccountId": "${UUID.randomUUID()}",
          "sourcePartyId": "${UUID.randomUUID()}",
          "survivingPartyId": "${UUID.randomUUID()}",
          "amount": "1000.00",
          "currencyCode": "CZK",
          "valueDate": "${LocalDate.now()}",
          "mergeReference": "$mergeReference"
        }
    """.trimIndent()

    /** Posts a sweep, optionally replaying an approval id. The status IS the observation. */
    private fun postSweep(payload: String, approvalId: String? = null) = RestAssured.given()
        .contentType("application/json")
        .apply { approvalId?.let { header("X-Approval-Id", it) } }
        .body(payload)
        .post("/api/v1/transactions/merge-sweep")
        .then()
        .extract()

    companion object {
        private const val MAKER = "00000000-0000-0000-0000-000000000099"
        private const val CHECKER = "00000000-0000-0000-0000-000000000098"
        private const val KEY_A = "sweep-a-11111111-1111-1111-1111-111111111111"
        private const val KEY_B = "sweep-b-22222222-2222-2222-2222-222222222222"
        private const val PENDING_APPROVAL = 202

        /** The only action this stub PDP gates — mirrors `rules.yaml four_eyes.verbs: sweep`. */
        private const val FOUR_EYES_ACTION = "transaction.sweep"

        /** Carried between ordered steps — the maker and checker must be different principals. */
        private var approvalForSweepA: String? = null
    }
}
