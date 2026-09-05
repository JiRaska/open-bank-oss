// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.referral

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReferralProgramClientTest {

    private val id = UUID.randomUUID()
    private val client = mockk<ReferralProgramClient>()
    private val adapter = LiveReferralProgramCatalogAdapter(client)

    @Test
    fun `minimal published reference contract decodes and resolves`(): Unit = runBlocking {
        val wire = """{"id":"$id","name":"September referral","version":3}"""
        val response = jacksonObjectMapper().readValue(wire, ReferralProgramResponse::class.java)
        every { client.published(id) } returns Uni.createFrom().item(response)

        assertThat(adapter.resolvePublished(id))
            .isEqualTo(com.openbank.campaign.domain.model.ReferralProgramRef(id, "September referral", 3))
    }

    @Test
    fun `missing unpublished or expired reference resolves to null`(): Unit = runBlocking {
        every { client.published(id) } returns Uni.createFrom().failure(
            WebApplicationException(Response.status(Response.Status.NOT_FOUND).build()),
        )

        assertThat(adapter.resolvePublished(id)).isNull()
    }

    @Test
    fun `other referral failures remain visible`(): Unit = runBlocking {
        every { client.published(id) } returns Uni.createFrom().failure(
            WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE).build()),
        )

        assertThat(runCatching { adapter.resolvePublished(id) }.exceptionOrNull())
            .isInstanceOf(WebApplicationException::class.java)
    }
}
