// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.storage

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.Optional

class S3ObjectStoreLifecycleTest {
    @Test
    fun `successful read closes the owned response and preserves bytes`(): Unit = runBlocking {
        val expected = byteArrayOf(1, 2, 3)
        val input = TrackingStream(expected)

        assertThat(store(input).get("synthetic-key")).isEqualTo(expected)
        assertThat(input.closes).isEqualTo(1)
    }

    @Test
    fun `empty object still closes the owned response`(): Unit = runBlocking {
        val input = TrackingStream(byteArrayOf())

        assertThat(store(input).get("synthetic-key")).isEmpty()
        assertThat(input.closes).isEqualTo(1)
    }

    @Test
    fun `read failure closes the response and preserves the original error`(): Unit = runBlocking {
        val failure = IOException("synthetic read failure")
        val input = TrackingStream(readFailure = failure)

        val observed = runCatching { store(input).get("synthetic-key") }.exceptionOrNull()

        assertReadFailure(observed, failure)
        assertThat(input.closes).isEqualTo(1)
    }

    @Test
    fun `close failure is suppressed rather than replacing a read failure`(): Unit = runBlocking {
        val readFailure = IOException("synthetic read failure")
        val closeFailure = IOException("synthetic close failure")
        val input = TrackingStream(readFailure = readFailure, closeFailure = closeFailure)

        val observed = runCatching { store(input).get("synthetic-key") }.exceptionOrNull()

        assertReadFailure(observed, readFailure)
        assertThat(readFailure.suppressed).containsExactly(closeFailure)
        assertThat(input.closes).isEqualTo(1)
    }

    private fun assertReadFailure(observed: Throwable?, original: IOException) {
        assertThat(observed).isInstanceOf(IOException::class.java).hasMessage(original.message)
        // Coroutine stack recovery may copy the IOException while retaining the original as its cause.
        assertThat(if (observed === original) observed else observed?.cause).isSameAs(original)
    }

    private fun store(input: InputStream): S3ObjectStore {
        val response = ResponseInputStream(GetObjectResponse.builder().build(), input)
        val client = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java),
        ) { _, method, _ ->
            check(method.name == "getObject") { "Unexpected synthetic SDK call: ${method.name}" }
            response
        } as S3Client
        val store = S3ObjectStore("synthetic-bucket", "eu-central-1", Optional.empty())
        // Replace the lazy test subject before first use: never build a real client or resolve credentials.
        S3ObjectStore::class.java.getDeclaredField("client\$delegate").apply {
            isAccessible = true
            set(store, lazyOf(client))
        }
        return store
    }

    private class TrackingStream(
        bytes: ByteArray = byteArrayOf(),
        private val readFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : InputStream() {
        private val input = ByteArrayInputStream(bytes)
        var closes = 0
            private set

        override fun read(): Int {
            readFailure?.let { throw it }
            return input.read()
        }

        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }
}
