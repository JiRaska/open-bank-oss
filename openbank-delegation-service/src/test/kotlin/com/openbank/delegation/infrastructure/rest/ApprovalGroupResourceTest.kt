// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.ApprovalGroupUseCase
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ApprovalGroupResourceTest {
    private val groups = mockk<ApprovalGroupUseCase>()
    private val resource = ApprovalGroupResource(groups)

    @Test
    fun `null roster element is rejected with its index before SCA binding`() {
        val request = ApprovalGroupScaReferenceRequest(name = "Treasury", members = setOf(null), threshold = 1)

        assertThatThrownBy { resource.scaReference(request, UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("members[0]")
    }
}
