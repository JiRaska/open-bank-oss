// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.transaction.model.TransactionPagePagination
import com.openbank.libs.api.pagination.PageInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The spec's `TransactionPage.pagination` must state exactly what the server sends. The server's
 * page envelope is libs `PageInfo` (`CursorPage.pagination`); this test pins the generated type to
 * it from both directions, so a field added to or removed from `PageInfo` reddens here instead of
 * being silently dropped by every consumer of the generated client.
 */
class PageInfoContractBindingTest {

    private val mapper = jacksonObjectMapper()

    private fun generatedFields(): Set<String> = TransactionPagePagination::class.java.declaredFields
        .mapNotNull { it.getAnnotation(JsonProperty::class.java)?.value }
        .toSet()

    @Test
    fun `every field PageInfo sends is declared by the generated pagination`() {
        val sent = PageInfo::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet()

        assertThat(sent)
            .containsExactlyInAnyOrder("limit", "hasNextPage", "nextCursor", "previousCursor", "totalCount")
        assertThat(generatedFields()).containsAll(sent)
    }

    @Test
    fun `the only declared field PageInfo never sends is the gate-constrained hasMore`() {
        val sent = PageInfo::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet()

        assertThat(generatedFields() - sent).containsExactly("hasMore")
    }

    @Test
    fun `a real PageInfo payload binds strictly into the generated pagination`() {
        val json = mapper.writeValueAsString(
            PageInfo(limit = 20, hasNextPage = true, nextCursor = "n", previousCursor = "p", totalCount = 41L),
        )

        val bound = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(json, TransactionPagePagination::class.java)

        assertThat(bound.limit).isEqualTo(20)
        assertThat(bound.hasNextPage).isTrue
        assertThat(bound.nextCursor).isEqualTo("n")
        assertThat(bound.previousCursor).isEqualTo("p")
        assertThat(bound.totalCount).isEqualTo(41L)
        assertThat(bound.hasMore).describedAs("the server never sends hasMore").isNull()
    }
}
