// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationRolePreset
import java.util.UUID

interface DelegationRolePresetRepository {
    suspend fun list(): List<DelegationRolePreset>
    suspend fun findById(id: UUID): DelegationRolePreset?

    /**
     * The preset recorded for the admin-supplied natural key (name, resourceType), or null — the
     * replay handle for POST /api/v1/delegation-role-presets (ADR-0292). Backed by
     * `uq_delegation_role_presets_name_type` (V15), the race backstop for concurrent creates.
     */
    suspend fun findByNameAndResourceType(name: String, resourceType: DelegationResourceType): DelegationRolePreset?
    suspend fun save(preset: DelegationRolePreset): DelegationRolePreset
    suspend fun delete(id: UUID): Boolean
}
