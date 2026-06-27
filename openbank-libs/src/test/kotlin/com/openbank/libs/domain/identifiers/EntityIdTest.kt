// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.identifiers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class EntityIdTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `serializes as bare UUID string, not as object`() {
        val id = AccountId.of("11111111-2222-3333-4444-555555555555")
        val json = mapper.writeValueAsString(id)
        assertThat(json).isEqualTo("\"11111111-2222-3333-4444-555555555555\"")
    }

    @Test
    fun `parses from bare UUID string`() {
        val json = "\"11111111-2222-3333-4444-555555555555\""
        val id = mapper.readValue(json, TransactionId::class.java)
        assertThat(id.value).isEqualTo(UUID.fromString("11111111-2222-3333-4444-555555555555"))
    }

    @Test
    fun `random produces a fresh UUID per call`() {
        assertThat(PartyId.random()).isNotEqualTo(PartyId.random())
    }

    @Test
    fun `of rejects invalid UUID with descriptive message`() {
        assertThatThrownBy { CardId.of("not-a-uuid") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CardId")
    }

    @Test
    fun `equality and hashCode come from the wrapped UUID`() {
        val a = AccountId.of("11111111-2222-3333-4444-555555555555")
        val b = AccountId.of("11111111-2222-3333-4444-555555555555")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `different ID types with the same UUID are NOT equal`() {
        val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        assertThat(AccountId(uuid) as EntityId).isNotEqualTo(TransactionId(uuid) as EntityId)
    }
}
