// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.FindingDisposition
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.UboSource
import java.time.Instant

/** The persisted JSON shape of `kyb_cases.screening_json`. Separate from [KybJson] only for its size. */
internal object KybScreeningJson {
    private val mapper = KybJson.mapper

    /**
     * An explicit document, like the extract: the domain types carry derived properties
     * (`complete`, `sanctionsHits`) that must never be stored as though they were facts.
     */
    data class ScreeningSubjectDoc(
        val key: String,
        val kind: String,
        val name: String,
        val organisation: Boolean,
        val sanctions: String,
        val pep: Boolean,
        val matchedName: String?,
        val screenedAt: Instant?,
        val current: Boolean = true,
        val disposition: String? = null,
        val disposedBy: String? = null,
        val dispositionReason: String? = null,
        val disposedAt: Instant? = null,
    )

    data class ScreeningDoc(val subjects: List<ScreeningSubjectDoc>, val ownerSource: String? = null)

    fun write(screening: CaseScreening): String = mapper.writeValueAsString(
        ScreeningDoc(
            screening.subjects.map {
                ScreeningSubjectDoc(
                    key = it.key,
                    kind = it.kind.name,
                    name = it.name,
                    organisation = it.organisation,
                    sanctions = it.sanctions.name,
                    pep = it.pep,
                    matchedName = it.matchedName,
                    screenedAt = it.screenedAt,
                    current = it.current,
                    disposition = it.disposition?.name,
                    disposedBy = it.disposedBy,
                    dispositionReason = it.dispositionReason,
                    disposedAt = it.disposedAt,
                )
            },
            screening.ownerSource?.name,
        ),
    )

    fun read(json: String): CaseScreening {
        val doc = mapper.readValue<ScreeningDoc>(json)
        return CaseScreening(doc.subjects.map(::subjectOf), doc.ownerSource?.let(UboSource::valueOf))
    }

    private fun subjectOf(doc: ScreeningSubjectDoc) = ScreeningSubject(
        key = doc.key,
        kind = ScreeningSubjectKind.valueOf(doc.kind),
        name = doc.name,
        organisation = doc.organisation,
        sanctions = ScreeningVerdict.valueOf(doc.sanctions),
        pep = doc.pep,
        matchedName = doc.matchedName,
        screenedAt = doc.screenedAt,
        current = doc.current,
        disposition = doc.disposition?.let(FindingDisposition::valueOf),
        disposedBy = doc.disposedBy,
        dispositionReason = doc.dispositionReason,
        disposedAt = doc.disposedAt,
    )
}
