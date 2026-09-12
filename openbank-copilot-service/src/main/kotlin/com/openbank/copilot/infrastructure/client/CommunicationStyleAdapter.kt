// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.client

import com.openbank.copilot.application.port.out.PublishedStylePort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

@ApplicationScoped
class CommunicationStyleAdapter(@param:RestClient private val client: CommunicationStyleClient) : PublishedStylePort {
    override suspend fun fetch(personaKey: String): PublishedStyleDto =
        client.getPublishedStyle(personaKey).awaitSuspending()
}
