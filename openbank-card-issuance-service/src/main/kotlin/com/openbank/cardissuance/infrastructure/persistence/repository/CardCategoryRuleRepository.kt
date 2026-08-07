// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.repository

import com.openbank.cardissuance.domain.model.CategoryRule
import com.openbank.cardissuance.infrastructure.persistence.entity.CardCategoryRuleEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class CardCategoryRuleRepository(private val clock: Clock) : PanacheRepositoryBase<CardCategoryRuleEntity, UUID> {

    suspend fun findByCard(cardId: UUID): List<CategoryRule> = Panache.withSession {
        find("cardId", cardId).list()
    }.awaitSuspending().map { CategoryRule(it.category, it.blocked, it.monthlyLimitMinorUnits) }

    /**
     * Replaces the card's rules with [rules] wholesale.
     *
     * Replace rather than patch, deliberately: the payload is the complete desired state, so the
     * client is never in a read-modify-write race with itself, and a category the customer removed
     * from the screen genuinely stops being enforced rather than lingering server-side where they
     * cannot see it.
     *
     * Rules that say nothing — not blocked, no cap — are not stored at all. An unblocked, uncapped
     * category is the absence of a rule; keeping a row for it would grow the table with entries
     * that can never change a decision.
     */
    suspend fun replaceForCard(cardId: UUID, rules: List<CategoryRule>): List<CategoryRule> {
        val meaningful = rules.filter { it.blocked || it.monthlyLimitMinorUnits != null }
        val now = Instant.now(clock)
        Panache.withTransaction {
            delete("cardId", cardId).flatMap {
                val entities = meaningful.map { r ->
                    CardCategoryRuleEntity().also { e ->
                        e.cardId = cardId
                        e.category = r.category
                        e.blocked = r.blocked
                        e.monthlyLimitMinorUnits = r.monthlyLimitMinorUnits
                        e.updatedAt = now
                    }
                }
                if (entities.isEmpty()) {
                    io.smallrye.mutiny.Uni.createFrom().voidItem()
                } else {
                    persist(entities)
                }
            }
        }.awaitSuspending()
        return meaningful
    }
}
