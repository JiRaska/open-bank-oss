// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/**
 * A missing required `accountId`/`currency` on a customer-edge read must answer **400 naming the
 * parameter**, not 500 (#3624, follow-up to #3104).
 *
 * customer-edge is the mobile/web BFF, so this is the most caller-visible instance of the defect in
 * the whole tail: the app cannot tell "I built a bad request" from "the bank is broken", and a 5xx
 * is what it retries and what burns the error budget.
 *
 * This has to be driven over REAL HTTP. The defect lives in JAX-RS parameter injection, so a unit
 * test that calls the handler supplies the very argument the framework does not —
 * `CustomerEdgeResourceTest` constructs the resource directly and passes an accountId, so it is
 * structurally incapable of seeing this. Measured against the pre-fix signatures
 * (`accountId: UUID`, `currency: String`, non-nullable): all five assertions below saw **500**.
 * These handlers are plain `fun`, so Kotlin emits an `Intrinsics.checkNotNullParameter` at bytecode
 * offset 0 and the NPE lands before the first statement of the body — which is why the guard is
 * only reachable once the type is nullable.
 *
 * Envelope: `badRequest()`, the resource's OWN 400 shape, not `requireNotNull` → libs-runtime.
 * `listSddMandates` already answers an absent `accountId` exactly this way, and introducing a
 * second 400 shape into a resource that answers every other 400 with an explicit `Response` is the
 * #526 two-mappers-for-one-type lottery in slow motion.
 *
 * No upstream stub is needed: every guard runs before the first `UpstreamClient` call, which is
 * itself part of the fix — a rejected request must not cost an upstream round trip.
 *
 * The last test is the control. Same auth, same resource, a handler that takes no required query
 * parameter; it passes both before and after the fix on purpose, so the redness of the first five
 * is attributable to the guards and not to the harness.
 */
@QuarkusTest
@TestSecurity(user = "customer:$MISSING_PARAM_PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = MISSING_PARAM_PARTY_ID)])
class CustomerEdgeMissingParamStatusIT {

    @Test
    fun `an absent accountId on the transaction history answers 400 naming the parameter`() {
        Given { this } When { get("/customer/v1/transactions") } Then {
            statusCode(400)
            body(containsString("accountId"))
        }
    }

    @Test
    fun `an absent accountId on the SCT Inst list answers 400 naming the parameter`() {
        Given { this } When { get("/customer/v1/sepa-instant") } Then {
            statusCode(400)
            body(containsString("accountId"))
        }
    }

    @Test
    fun `an absent accountId on the dispute list answers 400 naming the parameter`() {
        Given { this } When { get("/customer/v1/disputes") } Then {
            statusCode(400)
            body(containsString("accountId"))
        }
    }

    @Test
    fun `an absent accountId on the SCT Inst recall answers 400 naming the parameter`() {
        Given {
            contentType("application/json")
            body("{}")
        } When {
            post("/customer/v1/sepa-instant/$MISSING_PARAM_PAYMENT_ID/recall")
        } Then {
            statusCode(400)
            body(containsString("accountId"))
        }
    }

    @Test
    fun `an absent currency on the pocket resolve answers 400 naming the parameter`() {
        Given { this } When {
            get("/customer/v1/accounts/$MISSING_PARAM_ACCOUNT_ID/pockets/resolve")
        } Then {
            statusCode(400)
            body(containsString("currency"))
        }
    }

    @Test
    fun `control - a present accountId gets past the guard and fails on the absent upstream`() {
        Given {
            queryParam("accountId", MISSING_PARAM_ACCOUNT_ID)
        } When {
            get("/customer/v1/transactions")
        } Then {
            // Deliberately not an exact code: no upstream is stubbed, so what this becomes past the
            // guard (403 from the ownership lookup, or a 5xx from the failed call) is not the point.
            // The point is that it is NOT the 400 the guard produces — before AND after the fix.
            statusCode(not(400))
        }
    }
}

internal const val MISSING_PARAM_PARTY_ID = "22222222-2222-2222-2222-222222222222"
internal const val MISSING_PARAM_ACCOUNT_ID = "33333333-3333-3333-3333-333333333333"
internal const val MISSING_PARAM_PAYMENT_ID = "44444444-4444-4444-4444-444444444444"
