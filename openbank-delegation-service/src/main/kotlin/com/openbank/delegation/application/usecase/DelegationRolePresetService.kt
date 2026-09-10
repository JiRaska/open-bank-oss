// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRolePresetRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationRolePreset
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class DelegationRolePresetNotFound(id: UUID) : RuntimeException("Delegation role preset not found: $id")

@ApplicationScoped
class DelegationRolePresetService(private val repository: DelegationRolePresetRepository, private val clock: Clock) {
    @Inject
    constructor(repository: DelegationRolePresetRepository) : this(repository, Clock.systemUTC())

    suspend fun list(): List<DelegationRolePreset> = repository.list()

    suspend fun create(
        name: String,
        description: String,
        resourceType: DelegationResourceType,
        capabilities: Set<DelegationCapability>,
    ): DelegationRolePreset {
        // Idempotent replay (ADR-0292, #8351): one preset per (name, resourceType). A retried admin
        // create replays the original row — no duplicate catalog entry. The check runs first;
        // uq_delegation_role_presets_name_type (V15) is the race backstop, recovered below.
        val trimmedName = name.trim()
        repository.findByNameAndResourceType(trimmedName, resourceType)?.let { return it }

        val now = OffsetDateTime.now(clock)
        return try {
            repository.save(
                DelegationRolePreset(
                    name = trimmedName,
                    description = description.trim(),
                    resourceType = resourceType,
                    capabilities = capabilities,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Lost the race against a concurrent first create with the same natural key — the
            // winner's row is the replay answer. The catch is deliberately wide (Hibernate Reactive
            // wraps the PgException deep) and immediately narrowed by the constraint-name check.
            if (!e.isPresetNaturalKeyConflict()) throw e
            repository.findByNameAndResourceType(trimmedName, resourceType) ?: throw e
        }
    }

    suspend fun update(
        id: UUID,
        name: String,
        description: String,
        resourceType: DelegationResourceType,
        capabilities: Set<DelegationCapability>,
    ): DelegationRolePreset {
        val existing = repository.findById(id) ?: throw DelegationRolePresetNotFound(id)
        return repository.save(
            existing.copy(
                name = name.trim(),
                description = description.trim(),
                resourceType = resourceType,
                capabilities = capabilities,
                updatedAt = OffsetDateTime.now(clock),
            ),
        )
    }

    suspend fun delete(id: UUID) {
        if (!repository.delete(id)) throw DelegationRolePresetNotFound(id)
    }
}

private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
private const val PRESET_NATURAL_CONSTRAINT = "uq_delegation_role_presets_name_type"

/**
 * True when the failure chain carries the unique violation of `uq_delegation_role_presets_name_type`
 * (V15). Hibernate Reactive adapts the Vert.x PgException into a plain [SQLException] whose sqlState
 * may or may not survive, so accept either the 23505 sqlState or the "(23505)" marker in the
 * message — and ALWAYS require the constraint name (same shape as #8953).
 */
private fun Throwable.isPresetNaturalKeyConflict(): Boolean = generateSequence(this) { it.cause }
    .filterIsInstance<SQLException>()
    .any {
        it.message?.contains(PRESET_NATURAL_CONSTRAINT) == true &&
            (it.sqlState == SQLSTATE_UNIQUE_VIOLATION || it.message.orEmpty().contains("(23505)"))
    }
