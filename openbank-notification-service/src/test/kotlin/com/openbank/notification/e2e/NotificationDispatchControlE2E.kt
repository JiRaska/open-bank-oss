// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The ADR-0047 break-glass dispatch control over the real HTTP surface: halt is a single-actor
 * fail-safe that takes effect immediately and flags a mandatory deferred review; resume raises
 * risk and is gated by four-eyes (the approver must differ from the proposer — MakerCheckerViolation
 * answers 422, never a silent pass). The versioned append-only history is read back over HTTP.
 */
@QuarkusTest
@TestProfile(NotificationE2EProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NotificationDispatchControlE2E : NotificationE2EBase() {

    @Test
    @Order(1)
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `dispatch starts enabled and a halt flips it with a deferred review required`() {
        val before = getJson(DISPATCH, 200)
        assertThat(before.get("current").get("state").asText()).isEqualTo("ENABLED")

        val halted = postReason("$DISPATCH/halt", "provider outage", 200)
        assertThat(halted.get("state").asText()).isEqualTo("HALTED")
        assertThat(halted.get("reason").asText()).isEqualTo("provider outage")
        assertThat(halted.get("actor").asText()).isEqualTo("notification-e2e-operator")
        assertThat(halted.get("deferredReviewRequired").asBoolean()).isTrue()

        val readBack = getJson(DISPATCH, 200)
        assertThat(readBack.get("current").get("state").asText()).isEqualTo("HALTED")
        // The append-only history carries every snapshot, newest accessible by version.
        val versions = readBack.get("history").map { it.get("version").asInt() }
        assertThat(versions).contains(halted.get("version").asInt())
    }

    @Test
    @Order(2)
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a halt without a reason is refused 400 and changes nothing`() {
        postReason("$DISPATCH/halt", null, 400)
        postReason("$DISPATCH/halt", " ", 400)
        assertThat(getJson(DISPATCH, 200).get("current").get("state").asText()).isEqualTo("HALTED")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a resume proposal is accepted and a different operator approves it`() {
        val proposed = postReason("$DISPATCH/resume/propose", "provider recovered", 202)
        proposalId = proposed.get("proposalId").asText()
        assertThat(proposalId).isNotBlank()
        assertThat(proposed.get("state").asText()).isEqualTo("PROPOSED")
        // Still halted until a checker executes the resume.
        assertThat(getJson(DISPATCH, 200).get("current").get("state").asText()).isEqualTo("HALTED")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `the proposer cannot approve their own resume`() {
        requireProposal()
        val refusal = postReason("$DISPATCH/resume/$proposalId/approve", "self-approve", 422)
        assertThat(refusal.get("message").asText()).contains("four-eyes")
        assertThat(getJson(DISPATCH, 200).get("current").get("state").asText()).isEqualTo("HALTED")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "notification-e2e-checker", roles = ["ROLE_OPERATOR"])
    fun `a distinct checker approves and the dispatch is enabled again`() {
        requireProposal()
        val resumed = postReason("$DISPATCH/resume/$proposalId/approve", "verified", 200)
        assertThat(resumed.get("state").asText()).isEqualTo("ENABLED")
        assertThat(resumed.get("actor").asText()).isEqualTo("notification-e2e-checker")
        assertThat(resumed.get("deferredReviewRequired").asBoolean()).isFalse()

        // An executed proposal refuses a second decision.
        postReason("$DISPATCH/resume/$proposalId/approve", "again", 422)

        val readBack = getJson(DISPATCH, 200)
        assertThat(readBack.get("current").get("state").asText()).isEqualTo("ENABLED")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "notification-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a viewer cannot halt propose approve or reject`() {
        postReason("$DISPATCH/halt", "x", 403)
        postReason("$DISPATCH/resume/propose", "x", 403)
        postReason("$DISPATCH/resume/${UUID.randomUUID()}/approve", "x", 403)
        postReason("$DISPATCH/resume/${UUID.randomUUID()}/reject", "x", 403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "notification-e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `an auditor reads the control but cannot change it`() {
        assertThat(getJson(DISPATCH, 200).get("current").get("state").asText()).isEqualTo("ENABLED")
        postReason("$DISPATCH/halt", "x", 403)
    }

    private fun requireProposal() {
        assertThat(proposalId).describedAs("the propose stage must have run first").isNotBlank()
    }

    private companion object {
        const val DISPATCH = "/api/v1/ops/dispatch"
        var proposalId: String = ""
    }
}
