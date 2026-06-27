// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.time.Instant

/**
 * Panache entity for `eudi_status_list_entry` (V9) — one row per issued credential's Token Status
 * List index (ADR-0094). [idx] is allocated from the durable `eudi_status_list_idx_seq` sequence
 * (allocationSize 1 ⇒ every credential takes the next value with no client-side pooling, monotonic
 * and safe across replicas, surviving restart). The row's existence records "this index was
 * allocated"; [revoked] flips when the bank revokes that credential.
 */
@Entity
@Table(name = "eudi_status_list_entry")
class StatusListEntryEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "eudi_status_list_idx_gen")
    @SequenceGenerator(
        name = "eudi_status_list_idx_gen",
        sequenceName = "eudi_status_list_idx_seq",
        allocationSize = 1,
    )
    @Column(name = "idx")
    var idx: Long = 0

    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false

    @Column(name = "allocated_at", nullable = false)
    lateinit var allocatedAt: Instant

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
}
