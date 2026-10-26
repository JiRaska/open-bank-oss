// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Stands in for the OPA sidecar and generates the E2E issuer keys. Two real ports get their first
 * wire here:
 *
 *  - **OPA PDP**: the default stub answers `{"result":{"allow":true}}` on the exact
 *    `opa.path` the service configures (`/v1/data/openbank/rest/allow`), so the REAL
 *    [com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint] HTTP client is what the endpoint
 *    drives. Per-test stubs flip the decision to deny ([stubDeny]) or an outage ([stubOutage]) at
 *    a priority that beats the default; [resetToAllow] restores determinism before each test.
 *    The request journal is the proof that the endpoint sends the `agent:`-prefixed principal id
 *    from `X-Agent-Id` and the `mandateKind` attribute.
 *  - **Trust list**: an ES256 (P-256) and an Ed25519 keypair are generated at start, published as
 *    `ap2.trust-list` (`issuer-e2e=<spkiB64>;issuer-e2e-ed=<spkiB64>`), and the private halves are
 *    held for the suite to sign real mandates — so the keys cross the real config property into
 *    the real [com.openbank.ap2.infrastructure.keys.TrustListKeyResolver] and the signature is
 *    verified by the real JCA [com.openbank.ap2.infrastructure.crypto.JcaSignatureVerifier].
 *    "mallory" is never on the list, which is what the untrusted-issuer test refuses against.
 */
class Ap2E2EWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val wm = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server = wm
        wm.start()
        stubAllow()

        esKeys = KeyPairGenerator.getInstance("EC").let { g ->
            g.initialize(256)
            g.generateKeyPair()
        }
        edKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val trustList = "issuer-e2e=" + spkiB64(esKeys!!) + ";" + "issuer-e2e-ed=" + spkiB64(edKeys!!)
        return mapOf(
            "opa.url" to wm.baseUrl(),
            "ap2.trust-list" to trustList,
        )
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    private fun stubAllow() {
        wm().stubFor(
            post(urlEqualTo(OPA_PATH)).willReturn(
                okJson("""{"result":{"allow":true,"policy_version":"e2e-1"}}"""),
            ),
        )
    }

    companion object {
        private const val OPA_PATH = "/v1/data/openbank/rest/allow"
        const val TRUSTED_ISSUER = "issuer-e2e"
        const val TRUSTED_ISSUER_ED = "issuer-e2e-ed"

        private var server: WireMockServer? = null
        private var esKeys: KeyPair? = null
        private var edKeys: KeyPair? = null

        fun wm(): WireMockServer = server ?: error("Ap2E2EWireMockResource not started")

        private fun spkiB64(keys: KeyPair): String = Base64.getEncoder().encodeToString(keys.public.encoded)

        /** Signs [input] with the trusted ES256 key — the real SHA256withECDSA JCA path. */
        fun signEs256(input: String): String {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(esKeys!!.private)
            sig.update(input.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(sig.sign())
        }

        /** Signs [input] with the trusted Ed25519 key — the second real JCA path. */
        fun signEd25519(input: String): String {
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(edKeys!!.private)
            sig.update(input.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(sig.sign())
        }

        /** Flips the PDP answer to an explicit deny with the ADR-0034 full contract shape. */
        fun stubDeny() {
            wm().stubFor(
                post(urlEqualTo(OPA_PATH)).atPriority(1).willReturn(
                    okJson("""{"result":{"allow":false,"reason":"no matching allow rule","policy_version":"e2e-1"}}"""),
                ),
            )
        }

        /** Flips the PDP answer to a transport-level outage — the endpoint must fail closed. */
        fun stubOutage() {
            wm().stubFor(
                post(urlEqualTo(OPA_PATH)).atPriority(1).willReturn(
                    aResponse().withStatus(500).withBody("opa is down"),
                ),
            )
        }

        /** Drops per-test stubs and the request journal; restores the allow default. */
        fun resetToAllow() {
            wm().resetMappings()
            wm().resetRequests()
            // The default allow stub was registered in start() as a programmatic mapping, so
            // resetMappings() removed it too — re-register it.
            wm().stubFor(
                post(urlEqualTo(OPA_PATH)).willReturn(
                    okJson("""{"result":{"allow":true,"policy_version":"e2e-1"}}"""),
                ),
            )
        }

        /** Journal size for a matcher — the zero-fan-out / wire proof. */
        fun requestsTo(requestPattern: RequestPatternBuilder): Int = wm().findAll(requestPattern).count()

        fun opaPosts(): Int = requestsTo(postRequestedFor(urlEqualTo(OPA_PATH)))
    }
}
