// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.event

/**
 * The `actorId`/`actorType` a producer puts on the wire so the audit trail can say WHO (#3994).
 *
 * ## The two states this exists to separate
 *
 * `openbank-audit-service`'s consumer reads `requestedBy` / `actorId` / `initiatedByPartyId` off
 * the event body and stores NULL when it finds none. Measured on the live audit database on
 * 2026-08-09, **1341 of 1789 rows carry `actor_id IS NULL`** — and every one of them is a producer
 * that emits no actor key at all, so there was never anything for the consumer to read. That single
 * NULL is currently doing two completely different jobs:
 *
 *  - **"nobody did this — a scheduler or an event projection did"**, which is a correct and
 *    complete record. `BALANCE_UPDATED` is balance-service catching up to a posting the ledger
 *    already made; `account.statement.period.closed.v1` is a cron.
 *  - **"a person did this and we failed to record which person"**, which is an evidentiary gap —
 *    GDPR Art. 30 (records of processing) and DORA Art. 17 both want the actor.
 *
 * Those are the two states issue #3994 exists to tell apart, and one NULL cannot.
 *
 * ## The representation, and why not the obvious alternatives
 *
 * A system-originated event states its origin explicitly: `actorType = "SYSTEM"` and an
 * `actorId` of `system:<service>:<mechanism>` ([system]). A human-originated event carries the
 * real subject identity and its own type. **Absence keeps its meaning**: no key at all still means
 * "not recorded", still stores NULL, and is still the state to go fix.
 *
 * Rejected: inventing a placeholder person (`"system"`, `"admin"`, the service account's subject)
 * for the scheduler case. That is worse than the NULL it replaces, and this repo has measured
 * exactly why twice — a confident false value in a tamper-evident record reads as attributed and
 * so is never revisited (the `"unknown"` sourceService that reached 76%, and the four-character
 * string `"null"` that #4307 removed from `actor_id`). The `system:` prefix is deliberately not a
 * UUID, not a Keycloak subject and not an email, so a `system:` value can never be mistaken for a
 * person by `findByActorId`, by the ADR-0226 cross-channel person query, or by the GDPR Art. 15
 * access log — and a query for real people can exclude it with a prefix match.
 *
 * Rejected: a `SERVICE` actor *type*. `input.principal.type == "SERVICE"` is already structurally
 * unreachable in this platform's authorization layer (`rules.yaml: authz_policy`), and reusing the
 * word here would invite a rego rule keyed on a value the interceptor never emits. `SYSTEM` names
 * "no principal was involved", which is the fact being recorded.
 *
 * ## Why a shared value and not a per-service convention
 *
 * The alternative was N services each spelling their own sentinel, which is how `"unknown"`,
 * `"UNKNOWN"` and `"null"` all became separate problems. One canonical spelling means the audit
 * side can classify a row by prefix rather than by a hand-kept list of sentinels, and the mechanism
 * segment keeps the origin answerable ("which scheduler wrote this?") instead of collapsing every
 * automated write into one indistinguishable bucket.
 *
 * Pure Kotlin by construction — this is `openbank-libs-domain` and the domain layer carries zero
 * framework imports (ADR-0002/ADR-0122). Producers serialise these two strings with whatever
 * mechanism they already use; there is nothing to inject and no CDI bean to consume, so adding
 * this file cannot affect a module that does not call it.
 */
object EventActor {

    /** `actorType` for an event no principal originated: a scheduler, a consumer, a projection. */
    const val TYPE_SYSTEM: String = "SYSTEM"

    /** Prefix reserved for [system] ids. Never a person; safe to exclude with a prefix match. */
    const val SYSTEM_PREFIX: String = "system:"

    /** Wire key for the actor identity — the spelling `AuditConsumer` reads. */
    const val FIELD_ACTOR_ID: String = "actorId"

    /** Wire key for the actor kind. */
    const val FIELD_ACTOR_TYPE: String = "actorType"

    /**
     * The `actorId` for an event that no person originated.
     *
     * @param service the producing module without the `openbank-` prefix — the fleet's audit
     *   convention, and the same spelling `sourceService` uses, so the two agree.
     * @param mechanism what inside that service originated it: a scheduler name, the consumed
     *   topic, the projection. Required, and required to be non-blank: "some automation in
     *   balance-service" is not an answer anyone can act on, and making it optional is how every
     *   call site ends up omitting it.
     */
    fun system(service: String, mechanism: String): String {
        require(service.isNotBlank()) { "service must not be blank" }
        require(mechanism.isNotBlank()) { "mechanism must not be blank" }
        return "$SYSTEM_PREFIX$service:$mechanism"
    }

    /** True when [actorId] is a [system] id rather than a person. Null/blank is neither. */
    fun isSystem(actorId: String?): Boolean = actorId != null && actorId.startsWith(SYSTEM_PREFIX)
}
