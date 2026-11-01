// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.billing.application.port.out.CatalogRevisionIrPort
import com.openbank.billing.application.port.out.CatalogRevisionMismatchException
import com.openbank.billing.application.port.out.CatalogRevisionUnavailableException
import com.openbank.billing.application.port.out.StoredRevisionIr
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.libs.productrevision.IrSignature
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Reads a pinned revision's product IR and publish-path signature from product-catalog
 * (`GET /api/v2/revisions/{id}`, openapi 2.9.0 `content.behaviour` + `irSignature`). Only the shadow
 * comparator calls it, off the posting path; what it returns is stored once and read locally after.
 *
 * Its own mapper keeps every JSON number a BigDecimal: the canonical hash the loader recomputes must
 * see exactly the decimals the catalog signed.
 */
@ApplicationScoped
class RestCatalogRevisionIrPort(@RestClient private val catalog: ProductCatalogRestClient) : CatalogRevisionIrPort {

    private val mapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)

    override suspend fun fetch(revisionId: String): StoredRevisionIr = try {
        parse(revisionId, catalog.getRevisionJson(revisionId).awaitSuspending())
    } catch (e: WebApplicationException) {
        throw CatalogRevisionUnavailableException("catalog answered HTTP ${e.response?.status}", e)
    } catch (e: ProcessingException) {
        throw CatalogRevisionUnavailableException("catalog unreachable", e)
    } catch (e: JsonProcessingException) {
        throw CatalogRevisionUnavailableException("catalog returned an unreadable revision", e)
    }

    internal fun parse(revisionId: String, body: String): StoredRevisionIr {
        val root = mapper.readTree(body)
        // The copy is stored under revisionId and trusted by that id afterwards, so the body must be that revision.
        val answered = root.get("id")?.takeIf(JsonNode::isTextual)?.asText()
        if (answered != revisionId) {
            throw CatalogRevisionMismatchException("catalog answered revision $answered for $revisionId")
        }
        val behaviour = root.path("content").get("behaviour")?.takeUnless(JsonNode::isNull)
            ?: return StoredRevisionIr(revisionId, hasIr = false, revisionHash = null, irJson = null, signature = null)
        val sig = root.get("irSignature")?.takeUnless(JsonNode::isNull)
        return StoredRevisionIr(
            revisionId = revisionId,
            hasIr = true,
            revisionHash = sig?.get("revisionHash")?.asText(),
            irJson = mapper.writeValueAsString(behaviour),
            identity = RevisionIdentityMetadata.parse(
                revisionId,
                root.get("number"),
                behaviour,
                sig,
                root.path("state").asText(),
            ),
            signature = sig?.let {
                IrSignature(it.path("keyId").asText(), it.path("algorithm").asText(), it.path("signature").asText())
            },
        )
    }
}
