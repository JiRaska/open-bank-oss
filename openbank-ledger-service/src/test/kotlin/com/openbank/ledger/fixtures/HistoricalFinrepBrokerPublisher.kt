// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import au.com.dius.pact.core.pactbroker.IHalClient
import au.com.dius.pact.core.pactbroker.PactBrokerClient
import au.com.dius.pact.core.pactbroker.PactBrokerClientConfig
import au.com.dius.pact.core.pactbroker.TestResult
import au.com.dius.pact.core.support.Result
import java.lang.reflect.Proxy
import java.net.URI

/** Supported Pact publisher over a scoped transport; this adapter is not wired to a task or workflow. */
internal class HistoricalFinrepBrokerPublisher(
    private val origin: URI,
    private val transport: HistoricalFinrepBrokerTransport,
    override val buildUrl: URI,
) : HistoricalFinrepPublisher {
    override fun publish(replay: HistoricalFinrepBrokerReplay): URI {
        require(
            buildUrl.scheme == "https" &&
                buildUrl.host == "github.com" &&
                buildUrl.userInfo == null &&
                buildUrl.rawQuery == null &&
                buildUrl.rawFragment == null &&
                Regex("/JiRaska/open-bank-oss/actions/runs/[0-9]+").matches(buildUrl.rawPath),
        ) { "Publication requires the repository's hosted run link" }
        var receipt: URI? = null
        val hal = Proxy.newProxyInstance(IHalClient::class.java.classLoader, arrayOf(IHalClient::class.java)) {
                _,
                method,
                args,
            ->
            check(method.name == "postJson" && args?.size == 2) { "Unsupported broker operation" }
            require(args[0] == replay.publicationLink.toString()) { "Publisher changed the validated broker link" }
            check(receipt == null) { "Publisher attempted another request" }
            receipt = transport.post(replay.publicationLink, args[1] as String).first
            Result.Ok(true)
        } as IHalClient
        val client = object : PactBrokerClient(
            origin.toString(),
            mutableMapOf<String, Any>(),
            PactBrokerClientConfig(),
        ) {
            override fun newHalClient(): IHalClient = hal
        }
        val result = client.publishVerificationResults(
            mapOf("pb:publish-verification-results" to mapOf("href" to replay.publicationLink.toString())),
            TestResult.Ok(),
            replay.providerSha,
            buildUrl.toString(),
        )
        check(result is Result.Ok && result.value) { "Broker publication was not acknowledged" }
        return requireNotNull(receipt) { "Broker publication has no receipt" }
    }
}
