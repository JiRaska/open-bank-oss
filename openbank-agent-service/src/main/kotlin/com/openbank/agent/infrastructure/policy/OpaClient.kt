// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.policy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Thin client for the OPA sidecar Data API (ADR-0018). Queries the `decision` rule of the
 * agents policy (ADR-0031 D2, see openbank-infra/opa/policies/agents.rego), which returns the
 * full decision object — a superset of the `allow` boolean, including the reason.
 *
 * OPA Data API: POST /v1/data/<path> with `{ "input": {...} }` -> `{ "result": {...} }`.
 */
@RegisterRestClient(configKey = "opa")
@Path("/v1/data/openbank/agents")
interface OpaClient {

    @POST
    @Path("/decision")
    @Consumes(MediaType.APPLICATION_JSON)
    fun decision(request: OpaRequest): OpaResponse
}

data class OpaRequest(val input: Map<String, Any?>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpaResponse(val result: OpaDecision? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpaDecision(val allow: Boolean = false, val reason: String? = null)
