// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.copilot.infrastructure.model

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** MicroProfile REST Client interface for any OpenAI-compatible /v1/chat/completions endpoint. */
@RegisterRestClient
@Path("/")
interface OpenAiCompatibleChatClient {

    @POST
    @Path("chat/completions")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun complete(
        @HeaderParam(HttpHeaders.AUTHORIZATION) authorization: String,
        body: OpenAiChatRequest,
    ): OpenAiChatResponse
}
