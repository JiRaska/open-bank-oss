// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.application.port.out.AnchorSigningException
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Failure-mode coverage for the OpenBao transit anchor signer (issue #5838).
 *
 * Written negative-first: every test here asserts that something REFUSES. A signer whose only
 * tests are happy-path cannot distinguish "signs correctly" from "silently produced something",
 * and the defect this replaces was exactly a signing path that carried on when it could not sign.
 *
 * The boot-time gate is exercised through the real `StartupEvent` observer rather than a
 * constructor call, because `@ApplicationScoped` is lazy: a guard written in an initializer of a
 * bean nothing has touched does not run at boot, and a test that constructs the class directly
 * cannot tell the two apart. `@Startup` on the bean is what makes the observer fire; this test
 * pins the observer's behaviour, and [OpenBaoTransitAnchorSignerStartupAnnotationTest] pins the
 * annotation that gets it invoked.
 */
class OpenBaoTransitAnchorSignerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun signer(
        enabled: Boolean = true,
        tokenPath: String,
        // Port 1 is reserved and never listening: any call fails as a transport error.
        baoAddr: String = "http://127.0.0.1:1",
    ) = OpenBaoTransitAnchorSigner(
        anchoringEnabled = enabled,
        baoAddr = baoAddr,
        role = "audit-service-anchor",
        transitMount = "transit",
        keyName = "audit-anchor",
        saTokenPath = tokenPath,
        objectMapper = ObjectMapper(),
    )

    private fun missingTokenPath() = tempDir.resolve("no-such-token").toString()

    private fun presentTokenPath(): String {
        val p = tempDir.resolve("token")
        Files.writeString(p, "a.projected.jwt")
        return p.toString()
    }

    @Test
    fun `boot fails closed when anchoring is enabled and there is no workload identity`() {
        assertThatThrownBy { signer(tokenPath = missingTokenPath()).assertKeyMaterialReachable(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Refusing to start")
    }

    @Test
    fun `boot succeeds when anchoring is disabled`() {
        assertThatCode {
            signer(enabled = false, tokenPath = missingTokenPath()).assertKeyMaterialReachable(StartupEvent())
        }
            .doesNotThrowAnyException()
    }

    @Test
    fun `boot succeeds when the workload identity token is present`() {
        assertThatCode { signer(tokenPath = presentTokenPath()).assertKeyMaterialReachable(StartupEvent()) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `signing with no key material throws rather than returning an improvised signature`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { signer(tokenPath = missingTokenPath()).sign("digest".toByteArray()) } }
            .isInstanceOf(AnchorSigningException::class.java)
            .hasMessageContaining("no projected ServiceAccount token")
    }

    @Test
    fun `signing against an unreachable signing backend throws`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { signer(tokenPath = presentTokenPath()).sign("digest".toByteArray()) } }
            .isInstanceOf(AnchorSigningException::class.java)
            .hasMessageContaining("OpenBao login transport failure")
    }

    @Test
    fun `an unreadable public key is reported as absent, not as an empty key`(): Unit = runBlocking {
        val s = signer(tokenPath = presentTokenPath())
        assertThat(s.publicKeyPem(s.keyId)).isNull()
    }

    @Test
    fun `public key material is never returned for a foreign key id`(): Unit = runBlocking {
        assertThat(signer(tokenPath = presentTokenPath()).publicKeyPem("local-hmac-sha256")).isNull()
    }

    @Test
    fun `key id names the transit mount and key so a verifier can resolve the public key`() {
        assertThat(signer(tokenPath = presentTokenPath()).keyId).isEqualTo("openbao-transit:transit/audit-anchor")
    }
}
