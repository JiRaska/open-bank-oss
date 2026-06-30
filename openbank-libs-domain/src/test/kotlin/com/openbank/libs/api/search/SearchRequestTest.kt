// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchRequestTest {

    @Test
    fun `blank or null query collapses to wildcard list mode`() {
        for (q in listOf(null, "", "   ")) {
            val req = SearchRequest.of(q)
            assertThat(req.wildcard).isTrue()
            assertThat(req.term).isNull()
            assertThat(req.hasTerm).isFalse()
        }
    }

    @Test
    fun `literal asterisk means everything in scope, not a fulltext term`() {
        val req = SearchRequest.of("*")
        assertThat(req.wildcard).isTrue()
        assertThat(req.term).isNull()
    }

    @Test
    fun `term shorter than minimum collapses to list to avoid unselective scan`() {
        val req = SearchRequest.of("a")
        assertThat(req.wildcard).isTrue()
        assertThat(req.term).isNull()
    }

    @Test
    fun `valid term is trimmed, kept, and marks a fulltext search`() {
        val req = SearchRequest.of("  Novak  ")
        assertThat(req.wildcard).isFalse()
        assertThat(req.hasTerm).isTrue()
        assertThat(req.term).isEqualTo("Novak")
    }

    @Test
    fun `limit defaults when absent and is clamped to the hard ceiling`() {
        assertThat(SearchRequest.of("x", limit = null).limit).isEqualTo(SearchRequest.DEFAULT_LIMIT)
        assertThat(SearchRequest.of("x", limit = 1_000_000).limit).isEqualTo(SearchRequest.MAX_LIMIT)
        assertThat(SearchRequest.of("x", limit = 0).limit).isEqualTo(1)
        assertThat(SearchRequest.of("x", limit = -5).limit).isEqualTo(1)
        assertThat(SearchRequest.of("x", limit = 50).limit).isEqualTo(50)
    }

    @Test
    fun `LIKE metacharacters in the term are escaped so they match literally`() {
        val req = SearchRequest.of("50% _off_")
        // backslash escaped first, then % and _
        assertThat(req.term).isEqualTo("50\\% \\_off\\_")
    }

    @Test
    fun `escapeLike escapes the escape character before the wildcards`() {
        assertThat(SearchRequest.escapeLike("a\\b")).isEqualTo("a\\\\b")
        assertThat(SearchRequest.escapeLike("100%")).isEqualTo("100\\%")
        assertThat(SearchRequest.escapeLike("a_b")).isEqualTo("a\\_b")
    }

    @Test
    fun `cursor and filters pass through unchanged`() {
        val req = SearchRequest.of("Novak", cursor = "abc", filters = mapOf("status" to "ACTIVE"))
        assertThat(req.cursor).isEqualTo("abc")
        assertThat(req.filters).containsEntry("status", "ACTIVE")
    }
}
