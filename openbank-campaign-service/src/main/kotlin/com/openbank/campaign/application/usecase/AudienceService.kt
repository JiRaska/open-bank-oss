// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.AudienceRegistry
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.domain.model.Audience
import com.openbank.campaign.domain.model.AudienceState
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentCatalog
import com.openbank.campaign.domain.model.SegmentRule
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

data class AudienceSummary(
    val name: String,
    val version: Int,
    val rules: List<String>,
    val state: AudienceState,
    val createdBy: String,
    val approvedBy: String?,
)

data class AudienceReach(val name: String, val version: Int, val size: Int, val asOf: Instant)

/**
 * Governs the authoring lifecycle around the existing closed segment DSL.
 *
 * Creating a draft does not make it campaign-targetable. The same typed rules can be previewed to
 * help the maker, but only the separate checker's immutable APPROVED version is visible to the
 * campaign path.
 */
@ApplicationScoped
class AudienceService(
    private val audiences: AudienceRegistry,
    private val evaluation: SegmentEvaluationPort,
    private val clock: Clock,
) {
    suspend fun list(): List<AudienceSummary> = audiences.list().map(::summary)

    fun summary(audience: Audience) = AudienceSummary(
        name = audience.segment.name,
        version = audience.segment.version,
        rules = audience.segment.rules.map { it.describe() },
        state = audience.state,
        createdBy = audience.createdBy,
        approvedBy = audience.approvedBy,
    )

    suspend fun create(name: String, rules: List<SegmentRule>, maker: String): Audience {
        require(SegmentCatalog.ALL.none { it.name == name }) { "a catalogue audience already owns '$name'" }
        val version = audiences.nextVersion(name)
        val now = clock.instant()
        return audiences.save(
            Audience(
                segment = Segment(name, version, rules),
                state = AudienceState.DRAFT,
                createdBy = maker,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun submit(name: String, version: Int, maker: String): Audience =
        audiences.load(name, version)?.let { audiences.save(it.submit(maker)) }
            ?: throw NoSuchElementException("audience $name@$version not found")

    suspend fun approve(name: String, version: Int, checker: String): Audience =
        audiences.load(name, version)?.let { audiences.save(it.approve(checker)) }
            ?: throw NoSuchElementException("audience $name@$version not found")

    suspend fun preview(name: String, version: Int): AudienceReach? {
        val audience = audiences.load(name, version) ?: return null
        return AudienceReach(name, version, evaluation.evaluate(audience.segment).size, clock.instant())
    }
}
