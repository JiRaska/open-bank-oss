// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.model

import com.openbank.libs.web.SyntheticTaintExternalBoundary
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
@SyntheticTaintExternalBoundary("third-party OpenAI-compatible endpoint is outside the banking trust boundary")
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
