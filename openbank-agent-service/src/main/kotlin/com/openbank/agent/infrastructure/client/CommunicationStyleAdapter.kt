// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.infrastructure.client

import com.openbank.agent.application.port.out.PublishedStylePort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

@ApplicationScoped
class CommunicationStyleAdapter(@param:RestClient private val client: CommunicationStyleClient) : PublishedStylePort {
    override suspend fun fetch(personaKey: String): PublishedStyleDto =
        client.getPublishedStyle(personaKey).awaitSuspending()
}
