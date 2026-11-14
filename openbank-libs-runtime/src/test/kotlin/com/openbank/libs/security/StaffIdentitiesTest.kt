// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.mockk.every
import io.mockk.mockk
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaffIdentitiesTest {
    private fun jwt(name: String?, personId: Any?, amr: Any?): JsonWebToken = mockk {
        every { this@mockk.name } returns name
        every { subject } returns "sub-1"
        every { getClaim<Any?>("person_id") } returns personId
        every { getClaim<Any?>("amr") } returns amr
    }

    @Test
    fun `person id and second factor are read from the token`() {
        val id = StaffIdentities.from(jwt("ana", "staff-9001", listOf("pwd", "otp")))
        assertEquals("ana", id.account)
        assertEquals("staff-9001", id.personId)
        assertTrue(id.strongAuth)
    }

    @Test
    fun `password-only session is not strong authentication`() {
        assertFalse(StaffIdentities.from(jwt("ana", "staff-9001", listOf("pwd"))).strongAuth)
        assertFalse(StaffIdentities.from(jwt("ana", "staff-9001", null)).strongAuth)
    }

    @Test
    fun `json-string shaped claim values are unquoted`() {
        val quoted = object {
            override fun toString() = "\"staff-9001\""
        }
        val id = StaffIdentities.from(
            jwt(
                "ana",
                quoted,
                listOf(object {
                    override fun toString() = "\"otp\""
                }),
            ),
        )
        assertEquals("staff-9001", id.personId)
        assertTrue(id.strongAuth)
    }

    @Test
    fun `missing, blank or multi-valued person id yields no person`() {
        assertNull(StaffIdentities.from(jwt("svc", null, null)).person)
        assertNull(StaffIdentities.from(jwt("svc", " ", null)).person)
        assertNull(StaffIdentities.from(jwt("svc", listOf("a1", "b2"), null)).person)
        assertNull(StaffIdentities.from(null).person)
    }

    @Test
    fun `account falls back to subject`() {
        assertEquals("sub-1", StaffIdentities.from(jwt(null, "staff-1", null)).account)
    }
}
