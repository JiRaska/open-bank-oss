// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/**
 * A size-capped replacement for `HttpResponse.BodyHandlers.ofString()` (ADR-0320 P1).
 *
 * `ofString()` buffers whatever the peer sends: a misbehaving or compromised model gateway, guard
 * or flag daemon can stream an unbounded body and take the JVM heap with it. This handler cancels
 * the subscription — closing the connection — the moment the body passes [maxBytes], and the
 * `send` call fails with an [IOException] instead of allocating. For the in-cluster callers that
 * keep a JDK client (an injected `HttpClient` is their test seam); third-party egress belongs on
 * [SafeHttpClient], which caps bodies itself.
 */
object BoundedBodyHandlers {
    /** 4 MiB: an order of magnitude over the largest legitimate chat/embedding/flag/OPA answer. */
    const val DEFAULT_MAX_BYTES: Int = 4 * 1024 * 1024

    fun ofString(maxBytes: Int = DEFAULT_MAX_BYTES): HttpResponse.BodyHandler<String> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        return HttpResponse.BodyHandler { info -> CappedStringSubscriber(maxBytes, charsetOf(info.headers())) }
    }

    private fun charsetOf(headers: java.net.http.HttpHeaders): Charset = headers.firstValue("content-type").orElse("")
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"')
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charsets.UTF_8

    private class CappedStringSubscriber(private val maxBytes: Int, private val charset: Charset) :
        HttpResponse.BodySubscriber<String> {
        private val result = CompletableFuture<String>()
        private val buffer = ByteArrayOutputStream()

        @Volatile private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<String> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(1)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            for (bb in item) {
                if (bb.remaining() > maxBytes - buffer.size()) {
                    result.completeExceptionally(IOException("response body exceeds $maxBytes bytes"))
                    subscription?.cancel()
                    return
                }
                val bytes = ByteArray(bb.remaining())
                bb.get(bytes)
                buffer.write(bytes)
            }
            subscription?.request(1)
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(buffer.toString(charset))
        }
    }
}
