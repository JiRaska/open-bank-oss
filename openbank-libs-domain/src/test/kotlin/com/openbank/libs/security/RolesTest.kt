// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The realm-role vocabulary is a security boundary: a duplicated, misspelled or unlisted name is
 * how three invented vocabularies reached 39 `@RolesAllowed` sites that answered 403 to every
 * caller (#2404). These assertions are the in-module half of that guard — the CI script
 * `check-roles-allowed-realm.py` owns the realm-JSON half.
 */
class RolesTest {

    @Test
    fun `every canonical role carries the ROLE_ prefix the JWT claim spells verbatim`() {
        assertThat(Roles.ALL).isNotEmpty()
        assertThat(Roles.ALL).allSatisfy { assertThat(it).startsWith("ROLE_") }
    }

    @Test
    fun `role names are upper snake case after the prefix`() {
        val shape = Regex("^ROLE_[A-Z]+(_[A-Z]+)*$")
        assertThat(Roles.ALL).allSatisfy { assertThat(it).matches { n -> shape.matches(n) } }
    }

    @Test
    fun `ALL has no duplicates`() {
        assertThat(Roles.ALL).doesNotHaveDuplicates()
    }

    @Test
    fun `ALL enumerates exactly the declared constants`() {
        assertThat(Roles.ALL).containsExactly(
            Roles.ADMIN,
            Roles.OPERATOR,
            Roles.VIEWER,
            Roles.COMPLIANCE,
            Roles.AUDITOR,
            Roles.SUPERVISOR,
            Roles.KYC,
            Roles.KYC_OPENER,
            Roles.KYC_REVIEWER,
            Roles.PAYMENTS,
            Roles.API,
        )
    }

    @Test
    fun `the four-eyes KYC pair are two distinct roles, not one`() {
        assertThat(Roles.KYC_OPENER).isNotEqualTo(Roles.KYC_REVIEWER)
        assertThat(Roles.ALL).contains(Roles.KYC_OPENER, Roles.KYC_REVIEWER)
    }

    @Test
    fun `the dead ROLE_SERVICE name is not part of the vocabulary`() {
        // ADR-0034 phase 5 / #266: no realm client is ever granted ROLE_SERVICE, so a policy or
        // annotation naming it is structurally unreachable. ROLE_API is the only M2M grant.
        assertThat(Roles.ALL).doesNotContain("ROLE_SERVICE")
        assertThat(Roles.API).isEqualTo("ROLE_API")
    }
}
