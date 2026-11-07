// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.contract

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/** Publication fixtures must never advance the default HTTP integration suite's persona state. */
class CommunicationPactTestProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources(): Boolean = true

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        listOf(QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java))
}
