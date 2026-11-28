// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.delegation.integration.PaymentProposalApiIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Git-pact HTTP provider replay for the payment-proposal routes customer-edge calls (ADR-0383 D6):
 * read, approve, record execution, reject. Runs on every PR (`check-pact-provider-replay.py`).
 *
 * ## Why a second `@PactFolder` class, split by `@PactFilter`
 *
 * [DelegationEventPactFolderProviderVerificationTest] is a plain-JVM message replay; these
 * interactions need the real HTTP stack, PostgreSQL and the service-account identity guard. The
 * two classes partition the folder by provider-state name — this one takes every state naming a
 * "payment proposal", the message classes take every other — the same split account-service uses
 * (`AccountNegativeAuthProviderVerificationTest`), so no interaction is replayed twice and none is
 * skipped: a new HTTP interaction without a proposal state lands on the message target and fails
 * loudly rather than silently.
 *
 * ## Identity
 *
 * The routes admit only `service-account-openbank-edge` acting for the customer named in
 * `X-Customer-Party-Id`; the class-level identity is exactly that, so the replay goes through the
 * same guard production does.
 *
 * ## Negative half
 *
 * The pact carries the happy path only. The refusals — a caller that is not the edge service account
 * (403), a proposal id that belongs to another customer (404, enumeration resistance) and a wrong
 * payload hash — are asserted against the same routes in [PaymentProposalApiIT]; a plain `@Test`
 * here cannot resolve the `PactVerificationContext` the `@BeforeEach` takes.
 *
 * ## Seeding a fixed id into an append-only table
 *
 * The contract pins the proposal id (the payload hash covers it), and the table forbids DELETE by
 * trigger. Each state handler therefore removes the previous replay's row in a session with
 * `session_replication_role = replica` — test database only — and inserts a fresh one.
 */
@QuarkusTest
@QuarkusTestResource(PaymentProposalApiIT.InMemoryKafka::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = DelegationProposalProviderFixture.EDGE, roles = ["ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = DelegationProposalProviderFixture.EDGE),
    ],
)
@Provider("openbank-delegation-service")
@PactFolder("../pacts")
@PactFilter(DelegationProposalProviderFixture.PROPOSAL_STATES)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DelegationProposalPactFolderProviderVerificationTest : DelegationProposalProviderFixture() {

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("a PENDING payment proposal exists for the customer")
    fun pendingProposal() = seed("PENDING")

    @State("an APPROVED payment proposal exists for the customer")
    fun approvedProposal() = seed("APPROVED")
}
