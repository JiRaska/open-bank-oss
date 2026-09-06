// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser
import com.openbank.kyb.domain.model.RepresentationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Phrasings copied from real *způsob jednání* entries; each family pins the mode it must yield. */
class CzechRepresentationRuleParserTest {

    private fun mode(text: String?) = CzechRepresentationRuleParser.parse(text)

    @Test
    fun `sole representation`() {
        listOf(
            "Jednatel jedná za společnost samostatně.",
            "Každý jednatel zastupuje společnost samostatně.",
            "Za společnost jedná každý člen představenstva samostatně.",
            "Samostatně",
            "Společnost zastupuje kterýkoli jednatel samostatně ve všech záležitostech.",
        ).forEach { assertThat(mode(it).mode).describedAs(it).isEqualTo(RepresentationMode.SOLE) }
        assertThat(mode("Jednatel jedná samostatně.").signaturesRequired(3)).isEqualTo(1)
    }

    @Test
    fun `joint representation with an explicit count`() {
        val two = mode("Za společnost jednají vždy dva jednatelé společně.")
        assertThat(two.mode).isEqualTo(RepresentationMode.JOINT_N)
        assertThat(two.requiredSigners).isEqualTo(2)
        assertThat(
            mode("Společnost zastupují alespoň dva členové představenstva společně.").requiredSigners,
        ).isEqualTo(2)
        assertThat(mode("Za společnost jednají 3 členové představenstva společně.").requiredSigners).isEqualTo(3)
        assertThat(
            mode("Předseda představenstva společně s jedním členem představenstva.").requiredSigners,
        ).isEqualTo(2)
    }

    @Test
    fun `all representatives jointly`() {
        assertThat(
            mode("Za společnost jednají všichni jednatelé společně.").mode,
        ).isEqualTo(RepresentationMode.JOINT_ALL)
        assertThat(mode("Všichni jednatelé společně.").signaturesRequired(3)).isEqualTo(3)
    }

    @Test
    fun `a threshold-conditional rule takes the stricter count`() {
        val rule =
            mode(
                "Jednatel jedná samostatně; v záležitostech s hodnotou nad 1 000 000 Kč jednají dva jednatelé společně.",
            )
        assertThat(rule.mode).isEqualTo(RepresentationMode.JOINT_N)
        assertThat(rule.requiredSigners).isEqualTo(2)
    }

    @Test
    fun `an unrecognised phrasing is UNKNOWN, never a guessed low count`() {
        val rule = mode("Způsob jednání je upraven ve stanovách společnosti.")
        assertThat(rule.mode).isEqualTo(RepresentationMode.UNKNOWN)
        assertThat(rule.signaturesRequired(2)).isNull()
        assertThat(mode("").mode).isEqualTo(RepresentationMode.UNKNOWN)
        assertThat(mode(null).mode).isEqualTo(RepresentationMode.UNKNOWN)
    }
}
