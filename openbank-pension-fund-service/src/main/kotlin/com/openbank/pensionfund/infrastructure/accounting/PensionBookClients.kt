// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import io.quarkus.runtime.LaunchMode
import io.quarkus.tls.TlsConfigurationRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.util.concurrent.TimeUnit

@ApplicationScoped
class PensionBookClients(private val tls: TlsConfigurationRegistry) {
    fun open(url: URI): PensionBookClient {
        require(url.scheme == "https" || LaunchMode.current() != LaunchMode.NORMAL) {
            "Pension accounting requires TLS"
        }
        require(url.userInfo == null && url.query == null && url.fragment == null) { "Invalid accounting base URI" }
        val builder = QuarkusRestClientBuilder.newBuilder().baseUri(url)
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS).readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
        if (url.scheme == "https") {
            builder.tlsConfiguration(
                tls.get("pension-books").orElseThrow {
                    IllegalStateException("Pension accounting TLS configuration is missing")
                },
            )
        }
        return builder.build(PensionBookClient::class.java)
    }

    private companion object {
        const val CONNECT_SECONDS = 2L
        const val READ_SECONDS = 5L
    }
}
