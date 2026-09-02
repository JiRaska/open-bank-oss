// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationRolePresetOpenApiTest {
    @Test
    fun `contract publishes role preset CRUD`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        assertThat(contract).contains("/api/v1/delegation-role-presets:")
        assertThat(contract).contains("/api/v1/delegation-role-presets/{id}:")
        assertThat(contract).contains("DelegationRolePresetRequest:")
        assertThat(contract).contains("DelegationRolePresetResponse:")
    }
}
