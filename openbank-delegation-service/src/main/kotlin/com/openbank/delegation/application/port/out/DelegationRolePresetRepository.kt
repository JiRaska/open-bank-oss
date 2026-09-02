// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationRolePreset
import java.util.UUID

interface DelegationRolePresetRepository {
    suspend fun list(): List<DelegationRolePreset>
    suspend fun findById(id: UUID): DelegationRolePreset?
    suspend fun save(preset: DelegationRolePreset): DelegationRolePreset
    suspend fun delete(id: UUID): Boolean
}
