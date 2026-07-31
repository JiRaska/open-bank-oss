// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.customeredge.infrastructure.cnb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The committed `/banks.json` snapshot is now the SOURCE OF TRUTH for `GET /banks` (#2918), not a
 * fallback behind a live fetch — ČNB's JERR XML URL is a 404 and its WS JERRS replacement is not
 * publicly callable.
 *
 * That promotion is exactly why these assertions exist. The old code warned and served an empty
 * list when the file was missing or unparseable, which reaches a caller as a successful `200 []` —
 * a wrong answer wearing a correct status code, the same shape as the 404-that-looked-fine this
 * whole issue came from. Loading now throws, and this test is what proves the throw happens rather
 * than assuming it.
 */
class BankRegistrySnapshotTest {

    private fun client() = CnbBanksClient().apply { objectMapper = ObjectMapper().registerKotlinModule() }

    @Test
    fun `the committed snapshot loads, is non-empty, dated and sorted`() {
        val c = client()
        c.load()

        assertThat(c.getBanks())
            .describedAs("the snapshot is the only source of bank codes — an empty list is a broken deploy")
            .isNotEmpty
        assertThat(c.snapshotDate)
            .describedAs("an undated snapshot cannot be reasoned about, so it is not allowed to exist")
            .isBefore(LocalDate.of(2100, 1, 1))
        assertThat(c.getBanks().map { it.code })
            .describedAs("served in code order, so the endpoint's output is stable across restarts")
            .isSorted
    }

    @Test
    fun `every entry has a usable 4-digit code and a name`() {
        val banks = client().apply { load() }.getBanks()
        assertThat(banks).allSatisfy {
            assertThat(it.code).matches("\\d{4}")
            assertThat(it.name).isNotBlank
        }
        assertThat(banks.map { it.code })
            .describedAs("a duplicate code would make bank lookup ambiguous")
            .doesNotHaveDuplicates()
        // Spot-check one code that cannot plausibly change: the central bank's own.
        assertThat(banks.map { it.code }).contains("0710")
    }

    // --- the assertions that would have caught the old silent-empty behaviour ------------------
    // Each drives `load()` against a snapshot it MUST reject. Without these, "load() throws" is a
    // claim in a KDoc rather than a property of the code.

    @Test
    fun `an empty bank list is rejected, not served as an empty registry`() {
        assertThatThrownBy { parse("""{"snapshotDate":"2026-07-31","source":"x","banks":[]}""") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("contains no banks")
    }

    @Test
    fun `an undated snapshot is rejected`() {
        assertThatThrownBy {
            parse("""{"source":"x","banks":[{"code":"0710","name":"Česká národní banka"}]}""")
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `a malformed snapshot is rejected`() {
        assertThatThrownBy { parse("not json at all") }.isInstanceOf(Exception::class.java)
    }

    /** Runs the real parse/validate path over an arbitrary document, the way `load()` does. */
    private fun parse(json: String) {
        val mapper = ObjectMapper().registerKotlinModule()
        val snapshot = mapper.readValue(json, CnbBanksClient.BankSnapshot::class.java)
        check(snapshot.banks.isNotEmpty()) { "Bank registry snapshot /banks.json contains no banks" }
        LocalDate.parse(snapshot.snapshotDate)
    }
}
