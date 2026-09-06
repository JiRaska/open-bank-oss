// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.application.port.`in`

import com.openbank.tppregistry.domain.model.TppRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * `roles` is declared `Set<TppRole?>` because Jackson does not null-check collection ELEMENTS, so
 * `{"roles":[null]}` really does reach the use case. These exercise the guard that makes that a
 * 400 (IllegalArgumentException, mapped by libs-runtime) rather than a 500 deeper down.
 */
class RegisterTppCommandTest {

    private fun cmd(roles: Set<TppRole?>) = RegisterTppCommand(
        tppId = "CZ-CNB-1",
        name = "Acme",
        countryCode = "CZ",
        nca = "CNB",
        roles = roles,
        qwacSubjectDn = null,
        qsealSubjectDn = null,
    )

    @Test
    fun `requireRoles returns the roles unchanged when none are null`() {
        val result = cmd(setOf(TppRole.AISP, TppRole.PISP)).requireRoles()

        assertThat(result).containsExactlyInAnyOrder(TppRole.AISP, TppRole.PISP)
    }

    @Test
    fun `requireRoles rejects a null element and names its index`() {
        assertThatThrownBy { cmd(setOf(null)).requireRoles() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("roles[0] must not be null")
    }

    @Test
    fun `requireRoles reports the index of the null, not always zero`() {
        assertThatThrownBy { cmd(linkedSetOf(TppRole.AISP, TppRole.PISP, null)).requireRoles() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("roles[2] must not be null")
    }

    @Test
    fun `requireRoles accepts an empty set - emptiness is not this guard's job`() {
        assertThat(cmd(emptySet()).requireRoles()).isEmpty()
    }
}
