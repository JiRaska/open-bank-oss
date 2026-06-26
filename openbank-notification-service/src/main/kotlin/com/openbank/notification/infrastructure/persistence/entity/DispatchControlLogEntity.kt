// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.notification.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "dispatch_control_log")
class DispatchControlLogEntity : PanacheEntity() {
    @Column(name = "control_key", nullable = false)
    lateinit var controlKey: String

    @Column(name = "state", nullable = false)
    lateinit var state: String

    @Column(name = "version_no", nullable = false)
    var versionNo: Long = 0

    @Column(name = "reason", columnDefinition = "TEXT")
    var reason: String? = null

    @Column(name = "actor")
    var actor: String? = null

    @Column(name = "effective_from", nullable = false)
    lateinit var effectiveFrom: Instant

    @Column(name = "deferred_review_required", nullable = false)
    var deferredReviewRequired: Boolean = false

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}
