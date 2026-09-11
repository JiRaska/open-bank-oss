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

    @Test
    fun `both word orders are recognised, because the register writes both`() {
        // The single largest cause of the 31% UNKNOWN rate measured over 142 live texts: the
        // earlier patterns accepted noun-then-adverb only, and the register uses either.
        listOf(
            "Za společnost jedná samostatně jednatel.",
            "Jednatel jedná jménem společnosti ve všech úkonech samostatně.",
        ).forEach { assertThat(mode(it).mode).describedAs(it).isEqualTo(RepresentationMode.SOLE) }

        listOf(
            "Jménem společnosti jednají společně dva členové představenstva.",
            "Za společnost jednají společně vždy alespoň dva jednatelé.",
            "Za společnost jednají vždy dva (2) členové představenstva společně.",
        ).forEach {
            val r = mode(it)
            assertThat(r.mode).describedAs(it).isEqualTo(RepresentationMode.JOINT_N)
            assertThat(r.requiredSigners).describedAs(it).isEqualTo(2)
        }
    }

    @Test
    fun `a written-form second signature outranks the solo opening`() {
        // THE ordering test. The text opens solo and demands two signatures for anything in written
        // form — which a framework agreement is — and it never says "společně", so no keyword test
        // separates the two halves. It parses correctly only because every joint branch runs before
        // the solo one; move `sole` ahead of `jointCount` in `parse` and this goes SOLE(1).
        val rule = mode(
            "Za družstvo jedná navenek předseda nebo místopředseda. Je-li však pro právní úkon, " +
                "který činí představenstvo, předepsána písemná forma, je třeba podpisu alespoň dvou " +
                "členů představenstva.",
        )

        assertThat(rule.mode).isEqualTo(RepresentationMode.JOINT_N)
        assertThat(rule.requiredSigners).isEqualTo(2)
        assertThat(rule.signaturesRequired(4))
            .describedAs("one signature here would contract the družstvo on a signature it does not accept")
            .isEqualTo(2)
    }

    @Test
    fun `a rule naming the signing offices is never flattened into a count`() {
        // Kofola's actual způsob jednání. Two ordinary board members satisfy a count of two and do
        // NOT satisfy this rule, so answering a number would invite exactly the mistake the count
        // exists to prevent: the right number of the wrong people.
        val rule = mode(
            "Společnost Kofola zastupují vždy společně předseda představenstva spolu s jedním " +
                "členem představenstva.",
        )

        assertThat(rule.isRoleConstrained).isTrue()
        assertThat(rule.requiredRoles).containsExactly("predseda", "clen")
        assertThat(rule.signaturesRequired(5))
            .describedAs("the count is known and insufficient; a caller must read sourceText, not a number")
            .isNull()
        assertThat(rule.sourceText).contains("předseda představenstva")
    }

    @Test
    fun `a count qualified by an office is role-constrained too`() {
        val rule = mode(
            "Společnost zastupují vždy dva členové představenstva společně, z nichž jeden musí být " +
                "předsedou nebo místopředsedou představenstva.",
        )

        assertThat(rule.requiredSigners).isEqualTo(2)
        assertThat(rule.isRoleConstrained)
            .describedAs("any two members meet the count; the register also says one of them must chair")
            .isTrue()
        assertThat(rule.signaturesRequired(6)).isNull()
    }

    /**
     * THE test of this file. Every text below demands more than one signature, or names who must
     * sign, and each was chosen because a positive solo pattern matches it: an opening solo clause
     * followed by a second-signer clause, a negated solo, a consent of a second director, a
     * threshold. Written first against the previous parser, 7 of the 14 answered SOLE. They pass
     * now only because SOLE is withheld whenever any second-person, condition, negation or amount
     * marker is present — disable that whitelist and this goes red. A SOLE verdict on ANY of these
     * would let the bank contract the company on a signature the register does not accept.
     */
    @Test
    fun `no text that involves a second person or a condition can ever answer SOLE`() {
        listOf(
            "Jednatel jedná samostatně ve věcech do 100 000 Kč; jinak jednají oba jednatelé.",
            "Za společnost jednají oba jednatelé.",
            "K platnosti právního jednání je třeba podpisu obou jednatelů.",
            "Jednatelé nejednají samostatně; jednají vždy dva jednatelé.",
            "Samostatně nemůže jednat žádný jednatel.",
            "Žádný člen představenstva není oprávněn jednat samostatně.",
            "Jednatelé jednají společným jednáním dvou jednatelů.",
            "Za společnost podepisují dva jednatelé společným podpisem.",
            "Jednatel jedná samostatně, s výjimkou převodu nemovitostí, kde je třeba podpisu obou jednatelů.",
            "Za společnost jedná předseda představenstva samostatně nebo dva členové představenstva společně.",
            "Jednatel jedná samostatně pouze se souhlasem druhého jednatele.",
            "Jednatel jedná samostatně vyjma věcí nad 1 000 000 Kč, kde jedná spolu s druhým jednatelem.",
            "Předseda představenstva a místopředseda představenstva.",
            "Společnost zastupuje předseda představenstva spolu s prokuristou.",
        ).forEach {
            val r = mode(it)
            assertThat(r.mode).describedAs(it).isNotEqualTo(RepresentationMode.SOLE)
            assertThat(r.signaturesRequired(4) ?: 0).describedAs(it).isNotEqualTo(1)
        }
    }

    @Test
    fun `the register's standard signing-manner sentence is still a SOLE rule`() {
        // "…připojí svůj podpis" is the register's boilerplate for HOW one signs, not a second
        // signature. A `pripoj` marker blocked it and would have made a large slice of genuinely
        // solo texts permanently UNKNOWN.
        assertThat(
            mode(
                "Za společnost jedná jednatel samostatně a podepisuje se tak, že k obchodní firmě " +
                    "společnosti připojí svůj podpis.",
            ).mode,
        ).isEqualTo(RepresentationMode.SOLE)
    }

    @Test
    fun `a prokurista acting alone is SOLE, and acting WITH someone is not`() {
        // Blocking every mention of a prokurista would make a whole class of solo rules UNKNOWN —
        // "Prokurista jedná samostatně" is one, and the pairing below is common.
        assertThat(mode("Prokurista jedná samostatně.").mode).isEqualTo(RepresentationMode.SOLE)
        assertThat(mode("Jednatel jedná samostatně. Prokurista jedná samostatně.").mode)
            .isEqualTo(RepresentationMode.SOLE)

        // …and the joint shape the marker exists for must NOT read as solo.
        assertThat(mode("Ředitel jedná samostatně vždy s prokuristou.").mode)
            .isNotEqualTo(RepresentationMode.SOLE)
    }

    @Test
    fun `the whitelist tolerates exactly the three solo shapes that carry a harmless marker`() {
        // Each of these is unambiguously solo and carries a word the whitelist would otherwise
        // reject: a "-li" introducing the head-count, an internal body's consent, the register's
        // own "Počet členů statutárního orgánu" line. The tolerance is per shape, not per word —
        // "se souhlasem druhého jednatele" stays blocked (see the test above).
        listOf(
            "Jednatel jedná jménem společnosti samostatně. Má-li společnost více jednatelů, je každý z jednatelů " +
                "oprávněn jednat jménem společnosti zcela samostatně.",
            "Jménem společnosti jedná a podepisuje jednatel samostatně. Jednatel je oprávněn zcizovat a zatěžovat " +
                "nemovitosti jen se souhlasem valné hromady.",
            "Jednatel zastupuje společnost samostatně a v plném rozsahu. Počet členů statutárního orgánu: 1",
        ).forEach { assertThat(mode(it).mode).describedAs(it).isEqualTo(RepresentationMode.SOLE) }
    }
}
