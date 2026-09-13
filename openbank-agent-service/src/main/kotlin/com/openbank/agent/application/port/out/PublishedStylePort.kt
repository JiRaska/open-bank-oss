// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.application.port.out

import com.openbank.agent.infrastructure.client.PublishedStyleDto

/**
 * Plain hexagonal port, deliberately NOT the `@RegisterRestClient`-annotated
 * `CommunicationStyleClient` interface itself: implementing that interface directly makes Quarkus
 * Arc's REST-client extension sweep an implementer into the CDI bean graph (found the hard way in
 * `openbank-copilot-service` — a test fake implementing `CommunicationStyleClient` broke
 * `ArcProcessor#validate` for the whole module). `CommunicationStyleAdapter` is the only production
 * implementer, wrapping the real `@RestClient`; a test fake implements this port instead and needs
 * no CDI machinery at all.
 */
interface PublishedStylePort {
    suspend fun fetch(personaKey: String): PublishedStyleDto
}
