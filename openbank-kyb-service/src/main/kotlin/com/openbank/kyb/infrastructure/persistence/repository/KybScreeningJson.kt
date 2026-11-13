// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.FindingDisposition
import com.openbank.kyb.domain.model.MonitoringMatch
import com.openbank.kyb.domain.model.PastDisposition
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.UboSource
import java.time.Instant
import java.util.UUID

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
        val ref: String? = null,
        val monitoringFindings: List<MonitoringMatchDoc> = emptyList(),
        val pastDispositions: List<PastDispositionDoc> = emptyList(),
    )

    /** Explicit, like the rest: [MonitoringMatch] has derived properties that are not facts to store. */
    data class MonitoringMatchDoc(
        val findingId: UUID,
        val matchKind: String,
        val listType: String,
        val listEntryRef: String,
        val listEntryVersion: String,
        val matchScore: Double,
        val matchStatus: String,
        val detection: String,
        val receivedAt: Instant,
    )

    data class PastDispositionDoc(
        val disposition: String,
        val disposedBy: String?,
        val reason: String?,
        val disposedAt: Instant?,
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
                    ref = it.ref,
                    monitoringFindings = it.monitoringFindings.map { m ->
                        MonitoringMatchDoc(
                            m.findingId, m.matchKind, m.listType, m.listEntryRef, m.listEntryVersion,
                            m.matchScore, m.matchStatus, m.detection, m.receivedAt,
                        )
                    },
                    pastDispositions = it.pastDispositions.map { p ->
                        PastDispositionDoc(p.disposition.name, p.disposedBy, p.reason, p.disposedAt)
                    },
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
        ref = doc.ref,
        monitoringFindings = doc.monitoringFindings.map {
            MonitoringMatch(
                it.findingId, it.matchKind, it.listType, it.listEntryRef, it.listEntryVersion,
                it.matchScore, it.matchStatus, it.detection, it.receivedAt,
            )
        },
        pastDispositions = doc.pastDispositions.map {
            PastDisposition(FindingDisposition.valueOf(it.disposition), it.disposedBy, it.reason, it.disposedAt)
        },
    )
}
