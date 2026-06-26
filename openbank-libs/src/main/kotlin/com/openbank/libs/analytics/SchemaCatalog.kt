// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.analytics

/**
 * Schema governance for ingested events (ADR-0023, finding F7).
 *
 * The bronze layer keeps the (PII-masked) payload as JSON with a [AnalyticsEnvelope.schemaVersion],
 * which preserves *decodability* over the 10-year horizon — but only if we actually know which
 * (eventType, schemaVersion) pairs are legitimate. An unknown or unexpected schema arriving on the
 * stream is a governance gap: it may be a producer bug, a skipped migration, or a malformed event.
 *
 * This catalogue lets the sink answer "is this a known, accepted schema?" before writing. Unknown
 * schemas are routed to the dead-letter quarantine rather than silently persisted, so a schema drift
 * is surfaced as an operational signal instead of corrupting the log of record. Compatibility uses a
 * simple "same eventType, version <= max known" backward-compatibility rule; the durable registry
 * (Apicurio) is the documented follow-up — this is the in-process contract it would enforce.
 */
data class SchemaKey(val eventType: String, val schemaVersion: Int)

class SchemaCatalog(known: Set<SchemaKey>) {

    private val known: Set<SchemaKey> = known.toSet()
    private val maxVersionByType: Map<String, Int> =
        known.groupBy { it.eventType }.mapValues { (_, keys) -> keys.maxOf { it.schemaVersion } }

    /** Exact membership: this precise (eventType, schemaVersion) has been registered. */
    fun isKnown(key: SchemaKey): Boolean = key in known

    /**
     * Backward-compatibility: the eventType is registered and the incoming version is not newer than
     * the highest known version for that type. A *newer* version than we know about is treated as
     * incompatible (a producer shipped a schema the sink has not been taught), so it is quarantined
     * rather than written under an assumption.
     */
    fun isCompatible(key: SchemaKey): Boolean {
        val maxKnown = maxVersionByType[key.eventType] ?: return false
        return key.schemaVersion in 1..maxKnown
    }

    fun knownEventTypes(): Set<String> = maxVersionByType.keys
}
