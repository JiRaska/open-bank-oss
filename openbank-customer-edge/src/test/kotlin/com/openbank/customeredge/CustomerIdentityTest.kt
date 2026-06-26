// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.customeredge

import com.openbank.customeredge.domain.model.CustomerIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerIdentityTest {

    @Test
    fun `party id is preserved`() {
        val id = UUID.randomUUID()
        val identity = CustomerIdentity(partyId = id)
        assertThat(identity.partyId).isEqualTo(id)
    }

    @Test
    fun `two identities with the same party id are equal`() {
        val id = UUID.randomUUID()
        assertThat(CustomerIdentity(id)).isEqualTo(CustomerIdentity(id))
    }
}
