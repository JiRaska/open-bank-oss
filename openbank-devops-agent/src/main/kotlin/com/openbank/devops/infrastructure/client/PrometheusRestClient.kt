// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.client

import com.openbank.devops.infrastructure.adapter.PrometheusInstantResult
import com.openbank.devops.infrastructure.adapter.PrometheusRangeResult
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam

@Path("/api/v1")
interface PrometheusRestClient {
    @GET
    @Path("/query")
    fun queryInstant(
        @QueryParam("query") query: String,
    ): java.util.concurrent.CompletableFuture<PrometheusInstantResult>

    @GET
    @Path("/query_range")
    fun queryRange(
        @QueryParam("query") query: String,
        @QueryParam("start") start: String,
        @QueryParam("end") end: String,
        @QueryParam("step") step: String,
    ): java.util.concurrent.CompletableFuture<PrometheusRangeResult>
}
