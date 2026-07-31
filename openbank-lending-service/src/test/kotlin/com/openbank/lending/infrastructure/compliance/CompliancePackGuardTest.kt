// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.compliance

import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.lending.compliance.AprDisclosure
import com.openbank.libs.lending.compliance.CompliancePack
import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import com.openbank.libs.lending.compliance.PackProductType
import com.openbank.libs.lending.compliance.TerminationGround
import com.openbank.libs.lending.compliance.TerminationRules
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Covers ADR-0212 D2: the fail-closed origination guard and its bootstrap flag. */
class CompliancePackGuardTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC)
    private val registry = CompliancePackRegistry()

    private fun activateCzPack() {
        registry.activate(
            Proposal(
                id = "p1",
                action = CompliancePackCompiler.compile(
                    CompliancePack(
                        jurisdiction = "CZ",
                        productType = PackProductType.CONSUMER_CREDIT,
                        version = 1,
                        effectiveFrom = LocalDate.parse("2026-08-01"),
                        coolingOffDays = 14,
                        aprDisclosure = AprDisclosure("RPSN", "cs-CZ"),
                        terminationRules = TerminationRules(
                            noticePeriodDays = 30,
                            permittedGrounds = setOf(TerminationGround.DEFAULT_DPD),
                        ),
                    ),
                ),
                proposedBy = "maker-1",
                proposedAt = Instant.EPOCH,
                state = ProposalState.EXECUTED,
                decidedBy = "checker-2",
            ),
        )
    }

    @Test
    fun `enforcement off lets everything through (bootstrap default)`() {
        val guard = CompliancePackGuard(registry, clock, enforced = false)

        assertThatCode { guard.checkOriginationAllowed(null, null) }.doesNotThrowAnyException()
        assertThatCode { guard.checkOriginationAllowed("XX", "NOPE") }.doesNotThrowAnyException()
    }

    @Test
    fun `enforcement on refuses a missing jurisdiction`() {
        val guard = CompliancePackGuard(registry, clock, enforced = true)

        assertThatThrownBy { guard.checkOriginationAllowed(null, "CONSUMER_CREDIT") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("jurisdiction is required")
    }

    @Test
    fun `enforcement on refuses an unknown product type`() {
        val guard = CompliancePackGuard(registry, clock, enforced = true)

        assertThatThrownBy { guard.checkOriginationAllowed("CZ", "PAYDAY") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown productType")
    }

    @Test
    fun `enforcement on refuses origination with no active pack (fail closed)`() {
        val guard = CompliancePackGuard(registry, clock, enforced = true)

        assertThatThrownBy { guard.checkOriginationAllowed("CZ", "CONSUMER_CREDIT") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no active compliance pack")
    }

    @Test
    fun `enforcement on passes with an active pack`() {
        activateCzPack()
        val guard = CompliancePackGuard(registry, clock, enforced = true)

        assertThatCode { guard.checkOriginationAllowed("CZ", "CONSUMER_CREDIT") }.doesNotThrowAnyException()
    }
}
