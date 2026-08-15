// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

/**
 * How much a party master-data change matters to downstream compliance (ADR-0256 D1, issue #4458).
 *
 * Declared by the PUBLISHER and put on the wire, never inferred by a consumer from a generic
 * update payload: kyc-service's deferred `PARTY_DATA_CHANGED` re-screening trigger fires on
 * [MATERIAL] only, and "which fields does a contact-preference edit touch" is not a question a
 * consumer of a flat envelope can answer.
 *
 * Three values, not a boolean, for the reason `PushSendOutcome` has ACCEPTED/SKIPPED/FAILED
 * (ADR-0252 phase 0, #4348): a *no-op* update — a PATCH whose every field was null or unchanged —
 * is a third outcome, and folding it into [NON_MATERIAL] would let "nothing happened" and
 * "something happened that KYC does not care about" share one count. The metric
 * `openbank.parties.change.classified` is tagged with this enum's name, so an environment whose
 * updates are all no-ops is visibly different from one that is quietly never seeing name changes.
 */
enum class PartyChangeMateriality {
    /** At least one field in [PartyChange.MATERIAL_FIELDS] changed — a KYC re-screening trigger. */
    MATERIAL,

    /** Something changed, none of it material — contact details, trading name, address, preferences. */
    NON_MATERIAL,

    /** Nothing changed at all. Not a trigger, and deliberately not counted as a non-material change. */
    NO_CHANGE,
}

/**
 * The result of comparing a party record before and after an update.
 *
 * [changedFields] is every field that moved (material or not) and [materialFields] is the subset
 * that made the change material; both are sorted so the envelope is byte-stable for a given diff.
 */
data class PartyChangeClassification(
    val materiality: PartyChangeMateriality,
    val changedFields: List<String>,
    val materialFields: List<String>,
)

/**
 * Classifies a party update by comparing the two records, field by field.
 *
 * The material set is CLOSED and matches ADR-0256 D1's wording — name, date of birth, residency
 * country. The fourth member the ADR names, the beneficial-owner set, is **not** here because
 * party-service does not model beneficial owners at all; adding the key with nothing behind it
 * would be a classification that can never fire while reading as covered. It joins this set the
 * day the model does.
 *
 * `partyType` is material too and is not in the ADR's list: an INDIVIDUAL becoming a COMPANY
 * changes which identity checks apply, which is the same reason name and DOB are there.
 *
 * Deliberately excluded from BOTH sets: `status`, `kycStatus`, `amlStatus` and `updatedAt`. Those
 * are lifecycle state, not master data — they already have their own events
 * (`KYC_STATUS_CHANGED`), and letting a KYC verdict classify itself as a material party change
 * would make kyc-service re-trigger on its own output.
 */
object PartyChange {

    /** ADR-0256 D1's material master-data fields, as they appear in the wire envelope. */
    val MATERIAL_FIELDS: Set<String> = linkedSetOf("legalName", "dateOfBirth", "nationality", "partyType")

    private val COMPARED: List<Pair<String, (Party) -> Any?>> = listOf(
        "legalName" to { p: Party -> p.legalName },
        "dateOfBirth" to { p: Party -> p.dateOfBirth },
        "nationality" to { p: Party -> p.nationality },
        "partyType" to { p: Party -> p.partyType },
        "tradingName" to { p: Party -> p.tradingName },
        "email" to { p: Party -> p.email },
        "phone" to { p: Party -> p.phone },
        "address" to { p: Party -> p.address },
        "discoverable" to { p: Party -> p.discoverable },
        "consentMarketing" to { p: Party -> p.consentMarketing },
    )

    fun classify(before: Party, after: Party): PartyChangeClassification {
        val changed = COMPARED.filter { (_, read) -> read(before) != read(after) }.map { it.first }.sorted()
        val material = changed.filter { it in MATERIAL_FIELDS }
        val materiality = when {
            changed.isEmpty() -> PartyChangeMateriality.NO_CHANGE
            material.isNotEmpty() -> PartyChangeMateriality.MATERIAL
            else -> PartyChangeMateriality.NON_MATERIAL
        }
        return PartyChangeClassification(materiality, changed, material)
    }
}
