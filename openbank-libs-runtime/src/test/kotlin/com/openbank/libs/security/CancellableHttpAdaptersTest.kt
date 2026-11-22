// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.security

import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.libs.llm.ContentSafetyPort
import com.openbank.libs.llm.LlamaGuardContentSafetyAdapter
import com.openbank.libs.llm.OpenAiCompatibleEmbeddingAdapter
import com.openbank.libs.llm.OpenAiCompatibleLlmGatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CancellableHttpAdaptersTest {
    @Test
    fun `OPA cancellation aborts the original request without becoming a policy outage`() {
        assertCancellation { url, client ->
            OpaSidecarPolicyDecisionPoint(url, timeout = Duration.ofSeconds(10), httpClient = client)
                .allow(AuthzQuery(Principal("synthetic", "HUMAN"), "synthetic.read"))
        }
    }

    @Test
    fun `content safety cancellation aborts the original request without returning unavailable`() {
        assertCancellation { url, client ->
            LlamaGuardContentSafetyAdapter(url, "synthetic", "synthetic-key", http = client)
                .classify(ContentSafetyPort.SafetyRole.USER, "synthetic")
        }
    }

    @Test
    fun `embedding cancellation aborts the original request without returning a fallback`() {
        assertCancellation { url, client ->
            OpenAiCompatibleEmbeddingAdapter(url, "synthetic", 1, "synthetic-key", http = client)
                .embed(listOf("synthetic"))
        }
    }

    @Test
    fun `chat cancellation aborts the original request without returning a fallback`() {
        assertCancellation { url, client ->
            OpenAiCompatibleLlmGatewayClient(url, "synthetic", "synthetic-key", http = client)
                .chat("synthetic", "synthetic")
        }
    }

    private fun assertCancellation(call: suspend (String, HttpClient) -> Any?): Unit = runBlocking {
        OpenBaoExchangeDeadlineTest.StalledPeer(200).use { peer ->
            val client = HttpClient.newBuilder().build()
            try {
                val returned = AtomicBoolean()
                val job = launch(Dispatchers.IO) {
                    call(peer.uri.toString().trimEnd('/'), client)
                    returned.set(true)
                }
                try {
                    assertThat(peer.headersSent.await(2, TimeUnit.SECONDS)).isTrue()
                    job.cancel()
                    withTimeout(2000) { job.join() }
                    assertThat(job.isCancelled).isTrue()
                    assertThat(
                        returned.get(),
                    ).describedAs("cancellation must not return a successful fallback").isFalse()
                    peer.assertClosedBeforeCleanup()
                } finally {
                    job.cancel()
                }
            } finally {
                client.shutdownNow()
                assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue()
            }
        }
    }
}
