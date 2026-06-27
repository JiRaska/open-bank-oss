// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.infrastructure.openid4vci.CredentialIssuerService
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * OpenID4VCI issuer metadata (eIDAS 2.0, ADR-0094). Served at the spec-mandated well-known root path
 * `/.well-known/openid-credential-issuer` — its own resource class because that path is NOT under the
 * `/api/v1/parties/eudi` prefix the issuance endpoints share. Public: a wallet reads it before it has
 * any credentials.
 */
@Path("/.well-known/openid-credential-issuer")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "EUDI identity", description = "eIDAS 2.0 wallet credential issuance (ADR-0094 OpenID4VCI)")
class EudiIssuerMetadataResource(private val issuer: CredentialIssuerService, private val objectMapper: ObjectMapper) {
    @GET
    @PermitAll
    @Operation(summary = "OpenID4VCI issuer metadata (ADR-0094)")
    fun issuerMetadata(): Response {
        val meta = objectMapper.createObjectNode().apply {
            put("credential_issuer", issuer.issuerId)
            put("credential_endpoint", issuer.issuerId + "/api/v1/parties/eudi/credential")
            put("token_endpoint", issuer.issuerId + "/api/v1/parties/eudi/token")
            set<com.fasterxml.jackson.databind.JsonNode>("jwks", objectMapper.readTree(issuer.publicJwksJson()))
            set<com.fasterxml.jackson.databind.JsonNode>(
                "credential_configurations_supported",
                objectMapper.createObjectNode().set(
                    PID_CONFIG_ID,
                    objectMapper.createObjectNode().apply {
                        put("format", "vc+sd-jwt")
                        put("vct", PID_CONFIG_ID)
                        set<com.fasterxml.jackson.databind.JsonNode>(
                            "cryptographic_binding_methods_supported",
                            objectMapper.createArrayNode().add("jwk"),
                        )
                        set<com.fasterxml.jackson.databind.JsonNode>(
                            "proof_types_supported",
                            objectMapper.createObjectNode().set(
                                "jwt",
                                objectMapper.createObjectNode().set(
                                    "proof_signing_alg_values_supported",
                                    objectMapper.createArrayNode().add("ES256").add("EdDSA"),
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        return Response.ok(objectMapper.writeValueAsString(meta)).build()
    }

    private companion object {
        const val PID_CONFIG_ID = "eu.europa.ec.eudi.pid.1"
    }
}
