// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CaseScreeningTest {

    private fun extract(name: String, form: LegalFormClass, reps: List<String>) = RegistryExtract(
        identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
        legalName = name,
        legalFormCode = "112",
        legalFormClass = form,
        status = EntityStatus.ACTIVE,
        registeredAddress = null,
        incorporatedOn = null,
        taxId = null,
        representatives = reps.map { Representative(it, null, "jednatelé", "jednatel", null) },
        representationRule = RepresentationRule.SOLE,
        source = "ares",
        sourceRef = null,
        verification = ExtractVerification.VERIFIED,
        fetchedAt = Instant.EPOCH,
    )

    private fun owner(name: String, corporate: Boolean = false) = BeneficialOwner(
        fullName = name,
        dateOfBirth = null,
        nationality = null,
        countryOfResidence = null,
        band = OwnershipBand.PCT_50_TO_75,
        natureOfControl = emptyList(),
        notifiedOn = null,
        corporate = corporate,
    )

    @Test
    fun `a director who is also an owner is ONE subject, however the two registers spell the name`() {
        val subjects = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Ing. Jana Nováková")),
            listOf(owner("NOVÁKOVÁ Jana"), owner("Holding Alfa a.s.", corporate = true)),
        )
        assertThat(subjects.map { it.kind }).containsExactly(
            ScreeningSubjectKind.ENTITY,
            ScreeningSubjectKind.REPRESENTATIVE,
            ScreeningSubjectKind.BENEFICIAL_OWNER,
        )
        assertThat(subjects.map { it.organisation }).containsExactly(true, false, true)
        assertThat(subjects.map { it.key }.toSet()).hasSize(3)
    }

    @Test
    fun `a sole trader is screened as a natural person`() {
        val subjects = CaseScreening.subjectsOf(
            extract("Jan Novák", LegalFormClass.SOLE_TRADER, listOf("Jan Novák")),
            emptyList(),
        )
        assertThat(subjects).hasSize(1)
        assertThat(subjects.single().organisation).isFalse()
    }

    @Test
    fun `the key is stable and carries no name`() {
        val a = CaseScreening.subjectsOf(extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")), emptyList())
        val b = CaseScreening.subjectsOf(extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")), emptyList())
        assertThat(a.map { it.key }).isEqualTo(b.map { it.key })
        assertThat(a.map { it.key }).allMatch { it.matches(Regex("[0-9a-f]{24}")) }
    }

    @Test
    fun `nothing screened is not complete, and neither is anything still pending`() {
        val subjects = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
            emptyList(),
        )
        assertThat(CaseScreening(emptyList()).complete).isFalse()
        assertThat(CaseScreening(subjects).complete).isFalse()
        val oneAnswered = subjects.mapIndexed { i, s -> if (i == 0) s.copy(sanctions = ScreeningVerdict.CLEAR) else s }
        assertThat(CaseScreening(oneAnswered).complete).isFalse()
        assertThat(CaseScreening(subjects.map { it.copy(sanctions = ScreeningVerdict.CLEAR) }).complete).isTrue()
    }

    @Test
    fun `a rebuilt subject list keeps the answers already obtained and drops people no longer on the case`() {
        val before = CaseScreening(
            CaseScreening.subjectsOf(
                extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
                listOf(owner("Karel Vlastník")),
            ).map { it.copy(sanctions = ScreeningVerdict.CLEAR) },
        )
        val fresh = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
            listOf(owner("Nový Majitel")),
        )
        val merged = before.mergedInto(fresh)
        assertThat(merged.subjects.map { it.name }).containsExactly("Příklad s.r.o.", "Jana Nováková", "Nový Majitel")
        assertThat(merged.subjects.map { it.sanctions })
            .containsExactly(ScreeningVerdict.CLEAR, ScreeningVerdict.CLEAR, ScreeningVerdict.PENDING)
    }

    @Test
    fun `the review flag is one neutral sentence for a hit and for a PEP, and absent when everyone is clear`() {
        val subjects = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
            emptyList(),
        ).map { it.copy(sanctions = ScreeningVerdict.CLEAR) }
        assertThat(CaseScreening(subjects).reviewFlags()).isEmpty()
        val hit = subjects.map { if (it.kind == ScreeningSubjectKind.REPRESENTATIVE) it.copy(sanctions = ScreeningVerdict.HIT) else it }
        val pep = subjects.map { if (it.kind == ScreeningSubjectKind.REPRESENTATIVE) it.copy(pep = true) else it }
        assertThat(CaseScreening(hit).reviewFlags()).containsExactly(CaseScreening.REVIEW_FLAG)
        assertThat(CaseScreening(pep).reviewFlags()).containsExactly(CaseScreening.REVIEW_FLAG)
        assertThat(CaseScreening.REVIEW_FLAG).doesNotContain("Nováková", "sanction", "PEP")
    }
}
