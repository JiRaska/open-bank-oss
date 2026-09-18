// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.DisclosureSnapshotContentReader
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@ApplicationScoped
class RestDisclosureSnapshotContentReader(@RestClient private val client: DocumentServiceRestClient) :
    DisclosureSnapshotContentReader {
    override suspend fun read(snapshotId: UUID, expectedSha256: String): ByteArray =
        client.getDisclosureSnapshotContent(snapshotId, expectedSha256)
}
