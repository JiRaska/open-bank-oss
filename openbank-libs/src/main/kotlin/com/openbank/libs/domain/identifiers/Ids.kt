// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.identifiers

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator
import java.util.UUID

/**
 * The single source of new identifiers across the fleet (ADR-0106).
 *
 * [newId] returns a **UUIDv7** (RFC 9562) — a 128-bit UUID whose high 48 bits are a Unix
 * millisecond timestamp, so values are *time-ordered*. Used as a primary/indexed key this gives
 * sequence-like insert locality (new rows append to the right-hand edge of the B-tree) while
 * keeping every property we rely on from UUIDs: decentralised, collision-free, non-enumerable,
 * and safe to mint client-side/offline before the row exists.
 *
 * **Why a vetted generator, not a hand-rolled one.** Correct intra-millisecond ordering and
 * clock-regression handling (RFC 9562 §6.2) are the subtle, easy-to-get-wrong part — and a
 * silently wrong implementation defeats the whole locality benefit. We delegate to FasterXML's
 * Java UUID Generator (JUG, Apache-2.0), the same org as Jackson. For columns that default
 * server-side, prefer PostgreSQL 18's built-in `DEFAULT uuidv7()` (ADR-0106 Tier 1, as-touched).
 *
 * **Privacy caveat.** A UUIDv7 embeds its creation timestamp. This is fine — often useful — for
 * internal banking identifiers, but anywhere a created-at time must NOT be inferable from an
 * exposed value, use [randomId] (a random v4) deliberately instead.
 *
 * **Which one to call (ADR-0106 intent split).** Not every identifier wants time-ordering — and a
 * blind switch of every `UUID.randomUUID()` to v7 would be a regression, since v7 leaks a creation
 * timestamp. Choose by *intent*, and route every generation through this object so the choice is
 * explicit and auditable rather than implied by a bare JDK call:
 *   - [newId] — **durable, indexed identifiers**: entity/aggregate ids, outbox `event_id`,
 *     anything that becomes a primary/indexed key and benefits from insert locality. The default.
 *   - [randomId] — **values that must stay unordered/unlinkable**: idempotency keys, correlation
 *     and trace ids, one-time tokens and nonces, and anything externally exposed where a creation
 *     time must not be inferable. Equivalent to [java.util.UUID.randomUUID] but names the intent.
 *
 * Thread-safe: a single shared [TimeBasedEpochGenerator] is reused; its `generate()` is safe to
 * call concurrently. Domain code may call this directly — it is framework-free (ADR-0002).
 */
object Ids {
    private val generator: TimeBasedEpochGenerator = Generators.timeBasedEpochGenerator()

    /** A fresh time-ordered UUIDv7 — the default for durable, indexed identifiers. */
    fun newId(): UUID = generator.generate()

    /**
     * A fresh random **UUIDv4**, for values that must NOT carry a creation timestamp or insert
     * ordering: idempotency keys, correlation/trace ids, one-time tokens, nonces. Prefer this over
     * a bare [java.util.UUID.randomUUID] so the deliberate "random, not time-ordered" choice is
     * explicit at the call site. Use [newId] for anything that becomes a primary/indexed key.
     */
    fun randomId(): UUID = UUID.randomUUID()
}
