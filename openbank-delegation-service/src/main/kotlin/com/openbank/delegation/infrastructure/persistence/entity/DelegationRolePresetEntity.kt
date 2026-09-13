// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationRolePreset
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "delegation_role_presets")
class DelegationRolePresetEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID

    @Column(nullable = false, length = 100)
    lateinit var name: String

    @Column(nullable = false, length = 500)
    lateinit var description: String

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    lateinit var resourceType: DelegationResourceType

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delegation_role_preset_capabilities", joinColumns = [JoinColumn(name = "preset_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "capability")
    var capabilities: MutableSet<DelegationCapability> = mutableSetOf()

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    fun toDomain() = DelegationRolePreset(id, name, description, resourceType, capabilities, createdAt, updatedAt)

    companion object {
        fun fromDomain(value: DelegationRolePreset) = DelegationRolePresetEntity().apply {
            id = value.id
            name = value.name
            description = value.description
            resourceType = value.resourceType
            capabilities = value.capabilities.toMutableSet()
            createdAt = value.createdAt
            updatedAt = value.updatedAt
        }
    }
}
