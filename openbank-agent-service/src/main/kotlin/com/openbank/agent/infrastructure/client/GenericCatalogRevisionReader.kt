// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0-only.txt for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.out.CatalogRevisionReadPort
import com.openbank.agent.application.port.out.CatalogRevisionSnapshot
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient

/** REST adapter for [CatalogRevisionReadPort]. Every call is the v2 GET-only revision endpoint. */
@ApplicationScoped
class GenericCatalogRevisionReader : CatalogRevisionReadPort {

    @Inject @RestClient
    lateinit var client: GenericCatalogReadClient

    @Inject
    lateinit var objectMapper: ObjectMapper

    override fun get(offeringId: String, revisionId: String): CatalogRevisionSnapshot {
        val body = client.getRevision(offeringId, revisionId)
        val returnedOffering = body.requiredText("offeringId")
        val returnedRevision = body.requiredText("id")
        require(returnedOffering == offeringId && returnedRevision == revisionId) {
            "catalog returned a revision different from the requested snapshot"
        }
        return CatalogRevisionSnapshot(
            offeringId = returnedOffering,
            revisionId = returnedRevision,
            state = body.requiredText("state"),
            schemaRef = body.requiredObject("schemaRef").requiredText("id") + "@" +
                body.requiredObject("schemaRef").requiredText("version"),
            contentHash = body["contentHash"]?.asText()?.takeIf { it.isNotBlank() },
            document = objectMapper.writeValueAsString(body),
        )
    }

    private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
        get(name)?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("catalog revision is missing '$name'")

    private fun com.fasterxml.jackson.databind.JsonNode.requiredObject(
        name: String,
    ): com.fasterxml.jackson.databind.JsonNode = get(name)?.takeIf { it.isObject }
        ?: throw IllegalArgumentException("catalog revision is missing object '$name'")
}
