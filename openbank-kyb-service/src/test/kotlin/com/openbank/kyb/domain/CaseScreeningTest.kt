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
import com.openbank.kyb.domain.model.UboSource
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
    fun `complete needs an answer for everyone AND an ownership lookup that was actually made`() {
        val subjects = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
            emptyList(),
        )
        val clear = subjects.map { it.copy(sanctions = ScreeningVerdict.CLEAR) }
        assertThat(CaseScreening(emptyList(), UboSource.REGISTER).complete).isFalse()
        assertThat(CaseScreening(subjects, UboSource.REGISTER).complete).isFalse()
        val oneAnswered = subjects.mapIndexed { i, s -> if (i == 0) s.copy(sanctions = ScreeningVerdict.CLEAR) else s }
        assertThat(CaseScreening(oneAnswered, UboSource.REGISTER).complete).isFalse()
        assertThat(CaseScreening(clear, UboSource.REGISTER).complete).isTrue()
        assertThat(CaseScreening(clear, UboSource.SELF_DECLARATION).complete).isTrue()
        // Everyone on the list is clear, but nobody could say who the owners are: not complete.
        assertThat(CaseScreening(clear, UboSource.UNAVAILABLE).complete).isFalse()
        assertThat(CaseScreening(clear, null).complete).isFalse()
    }

    @Test
    fun `a rebuilt subject list keeps the answers already obtained, adds newcomers as pending, drops leavers`() {
        val before = CaseScreening(
            CaseScreening.subjectsOf(
                extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
                listOf(owner("Karel Vlastník")),
            ).map { it.copy(sanctions = ScreeningVerdict.CLEAR) },
            UboSource.REGISTER,
        )
        assertThat(before.complete).isTrue()
        val fresh = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
            listOf(owner("Nový Majitel")),
            declaredPersons = listOf("Jana Nováková", "Tichý Společník"),
        )
        val rebuilt = before.rebuilt(fresh, UboSource.REGISTER)
        assertThat(rebuilt.subjects.map { it.name })
            .containsExactly("Příklad s.r.o.", "Jana Nováková", "Nový Majitel", "Tichý Společník")
        assertThat(rebuilt.subjects.map { it.sanctions }).containsExactly(
            ScreeningVerdict.CLEAR,
            ScreeningVerdict.CLEAR,
            ScreeningVerdict.PENDING,
            ScreeningVerdict.PENDING,
        )
        assertThat(rebuilt.subjects.last().kind).isEqualTo(ScreeningSubjectKind.DECLARED_PERSON)
        assertThat(rebuilt.complete).isFalse()
    }

    @Test
    fun `a hit and a PEP both require review, and everyone clear does not`() {
        val subjects = CaseScreening.subjectsOf(
            extract("Příklad s.r.o.", LegalFormClass.LIMITED_COMPANY, listOf("Jana Nováková")),
            emptyList(),
        ).map { it.copy(sanctions = ScreeningVerdict.CLEAR) }
        assertThat(CaseScreening(subjects).requiresReview).isFalse()
        val hit = subjects.map { if (it.kind == ScreeningSubjectKind.REPRESENTATIVE) it.copy(sanctions = ScreeningVerdict.HIT) else it }
        val pep = subjects.map { if (it.kind == ScreeningSubjectKind.REPRESENTATIVE) it.copy(pep = true) else it }
        assertThat(CaseScreening(hit).requiresReview).isTrue()
        assertThat(CaseScreening(pep).requiresReview).isTrue()
    }
}
