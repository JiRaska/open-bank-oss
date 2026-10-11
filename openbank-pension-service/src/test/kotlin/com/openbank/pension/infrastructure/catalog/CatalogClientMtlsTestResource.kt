// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Selects one real TLS server variant while sharing the fixture setup and cleanup. */
enum class CatalogTlsScenario {
    TRUSTED,
    WRONG_CA,
    WRONG_HOSTNAME,
}

private const val TEST_APPROVAL_HASH = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

/** WireMock peers owned by the currently active, class-scoped test resource. */
internal data class CatalogTlsServers(val token: WireMockServer, val catalog: WireMockServer)

internal object CatalogTlsTestState {
    @Volatile
    var servers: CatalogTlsServers? = null
}

abstract class CatalogClientTlsTestResource(private val scenario: CatalogTlsScenario) :
    QuarkusTestResourceLifecycleManager {
    private var fixture: CatalogTlsFixture? = null

    override fun start(): Map<String, String> = CatalogTlsFixture(scenario).also {
        fixture = it
        try {
            CatalogTlsTestState.servers = it.start()
        } catch (failure: Throwable) {
            try {
                it.stop()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            } finally {
                fixture = null
                CatalogTlsTestState.servers = null
            }
            throw failure
        }
    }.config

    override fun stop() {
        fixture?.stop()
        fixture = null
        CatalogTlsTestState.servers = null
    }
}

class CatalogClientMtlsTestResource : CatalogClientTlsTestResource(CatalogTlsScenario.TRUSTED)

class CatalogClientWrongCaTestResource : CatalogClientTlsTestResource(CatalogTlsScenario.WRONG_CA)

class CatalogClientWrongHostnameTestResource : CatalogClientTlsTestResource(CatalogTlsScenario.WRONG_HOSTNAME)

/** Ephemeral, explicitly trusted client and server identities for the production REST-client TLS registry. */
private class CatalogTlsFixture(private val scenario: CatalogTlsScenario) {
    private val directory = Files.createTempDirectory("pension-catalog-mtls-")
    private val password = UUID.randomUUID().toString()
    private val tokenServer = WireMockServer(options().dynamicPort())
    private var catalogServer: WireMockServer? = null
    private var tokenServerStarted = false
    private var catalogServerStarted = false

    val config: Map<String, String>
        get() {
            val catalog = checkNotNull(catalogServer)
            val serverCertificate = directory.resolve("server.crt")
            val clientCertificate = directory.resolve("client.crt")
            val clientKey = directory.resolve("client.key")
            val serverTrust = when (scenario) {
                CatalogTlsScenario.WRONG_CA -> clientCertificate
                CatalogTlsScenario.TRUSTED,
                CatalogTlsScenario.WRONG_HOSTNAME,
                -> serverCertificate
            }
            return mapOf(
                // OIDC token minting stays HTTP; the catalog request itself uses the real TLS client.
                "quarkus.oidc-client.auth-server-url" to tokenServer.baseUrl(),
                "quarkus.oidc-client.discovery-enabled" to "false",
                "quarkus.oidc-client.token-path" to "/token",
                "quarkus.oidc-client.client-id" to "openbank-pension",
                "quarkus.oidc-client.credentials.secret" to "test-secret",
                "quarkus.oidc-client.grant.type" to "client",
                "quarkus.rest-client.product-catalog.url" to "https://localhost:${catalog.httpsPort()}",
                "quarkus.rest-client.product-catalog.tls-configuration-name" to "catalog-authority",
                "quarkus.tls.catalog-authority.key-store.pem.client.cert" to clientCertificate.toString(),
                "quarkus.tls.catalog-authority.key-store.pem.client.key" to clientKey.toString(),
                "quarkus.tls.catalog-authority.trust-store.pem.certs" to serverTrust.toString(),
                "quarkus.tls.catalog-authority.protocols" to "TLSv1.3",
                "quarkus.rest-client.account-service.url" to "https://localhost:${catalog.httpsPort()}",
                "quarkus.rest-client.account-service.tls-configuration-name" to "account-authority",
                "quarkus.tls.account-authority.key-store.pem.client.cert" to clientCertificate.toString(),
                "quarkus.tls.account-authority.key-store.pem.client.key" to clientKey.toString(),
                "quarkus.tls.account-authority.trust-store.pem.certs" to serverTrust.toString(),
                "quarkus.tls.account-authority.protocols" to "TLSv1.3",
                "quarkus.rest-client.party-service.url" to "https://localhost:${catalog.httpsPort()}",
                "quarkus.rest-client.party-service.tls-configuration-name" to "party-authority",
                "quarkus.tls.party-authority.key-store.pem.client.cert" to clientCertificate.toString(),
                "quarkus.tls.party-authority.key-store.pem.client.key" to clientKey.toString(),
                "quarkus.tls.party-authority.trust-store.pem.certs" to serverTrust.toString(),
                "quarkus.tls.party-authority.protocols" to "TLSv1.3",
                "quarkus.rest-client.sca-service.url" to "https://localhost:${catalog.httpsPort()}",
                "quarkus.rest-client.sca-service.tls-configuration-name" to "sca-authority",
                "quarkus.tls.sca-authority.key-store.pem.client.cert" to clientCertificate.toString(),
                "quarkus.tls.sca-authority.key-store.pem.client.key" to clientKey.toString(),
                "quarkus.tls.sca-authority.trust-store.pem.certs" to serverTrust.toString(),
                "quarkus.tls.sca-authority.protocols" to "TLSv1.3",
            )
        }

    fun start(): CatalogTlsServers {
        val serverName = if (scenario == CatalogTlsScenario.WRONG_HOSTNAME) "wrong-host" else "localhost"
        val serverStore = generateStore(
            directory.resolve("server.p12"),
            "server",
            "CN=$serverName",
            "SAN=dns:$serverName",
            "EKU=serverAuth",
        )
        val clientStore = generateStore(
            directory.resolve("client.p12"),
            "client",
            "CN=pension-catalog-it-client",
            "EKU=clientAuth",
        )
        val serverCertificate = serverStore.getCertificate("server")
        val clientCertificate = clientStore.getCertificate("client")
        writePem(directory.resolve("server.crt"), "CERTIFICATE", serverCertificate.encoded)
        writePem(directory.resolve("client.crt"), "CERTIFICATE", clientCertificate.encoded)
        writePem(
            directory.resolve("client.key"),
            "PRIVATE KEY",
            clientStore.getKey("client", password.toCharArray()).encoded,
        )

        val clientTrustStorePath = directory.resolve("client-trust.p12")
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("pension-client", clientCertificate)
            FileOutputStream(clientTrustStorePath.toFile()).use { store(it, password.toCharArray()) }
        }

        tokenServer.start()
        tokenServerStarted = true
        tokenServer.stubFor(
            post(urlEqualTo("/token"))
                .willReturn(okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}""")),
        )
        val catalog = WireMockServer(
            options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(directory.resolve("server.p12").toString())
                .keystorePassword(password)
                .keyManagerPassword(password)
                .keystoreType("PKCS12")
                .trustStorePath(clientTrustStorePath.toString())
                .trustStorePassword(password)
                .trustStoreType("PKCS12")
                .needClientAuth(true),
        )
        catalogServer = catalog
        catalog.start()
        catalogServerStarted = true
        catalog.stubFor(get(urlEqualTo("/api/v2/offerings")).willReturn(okJson("[]")))
        catalog.stubFor(
            post(urlEqualTo("/api/v1/accounts/ownership-verifications"))
                .withRequestBody(
                    equalToJson(
                        """
                            {
                              "iban": "CZ0000000000000000000000",
                              "partyId": "66666666-7777-4888-8999-aaaaaaaaaaaa"
                            }
                        """.trimIndent(),
                    ),
                )
                .willReturn(
                    okJson(
                        """{"owned":true,"active":true,"accountId":"11111111-2222-4333-8444-555555555555"}""",
                    ),
                ),
        )
        catalog.stubFor(
            get(urlEqualTo("/api/v1/parties/d1d1d1d1-e2e2-4f4f-8a8a-b1b1b1b1b1b1"))
                .willReturn(
                    okJson(
                        """
                            {
                              "id": "d1d1d1d1-e2e2-4f4f-8a8a-b1b1b1b1b1b1",
                              "partyType": "INDIVIDUAL",
                              "status": "ACTIVE",
                              "legalName": "Pact Joint Signatory",
                              "kycStatus": "APPROVED"
                            }
                        """.trimIndent(),
                    ),
                ),
        )
        catalog.stubFor(
            post(urlEqualTo("/api/v1/sca/challenges/7e5a0001-0000-4000-8000-000000000335/consume"))
                .withRequestBody(
                    equalToJson(
                        """
                            {
                              "partyId": "7e5a0002-0000-4000-8000-000000000335",
                              "approvalRequestId": "pension-exit:$TEST_APPROVAL_HASH",
                              "payloadSha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                            }
                        """.trimIndent(),
                    ),
                )
                .willReturn(
                    okJson(
                        """
                            {
                              "id": "7e5a0001-0000-4000-8000-000000000335",
                              "partyId": "7e5a0002-0000-4000-8000-000000000335",
                              "purpose": "APPROVAL",
                              "status": "COMPLETED",
                              "consumedAt": "2026-10-10T08:00:00Z"
                            }
                        """.trimIndent(),
                    ),
                ),
        )
        return CatalogTlsServers(tokenServer, catalog)
    }

    fun stop() {
        try {
            if (catalogServerStarted) catalogServer?.stop()
        } finally {
            try {
                if (tokenServerStarted) tokenServer.stop()
            } finally {
                catalogServer = null
                catalogServerStarted = false
                tokenServerStarted = false
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            }
        }
    }

    private fun generateStore(storePath: Path, alias: String, subject: String, vararg extensions: String): KeyStore {
        val command = mutableListOf(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", subject,
        )
        extensions.forEach { extension -> command.addAll(listOf("-ext", extension)) }
        command.addAll(
            listOf(
                "-validity", "30", "-storetype", "PKCS12", "-keystore", storePath.toString(),
                "-storepass", password, "-keypass", password, "-noprompt",
            ),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Ephemeral catalog TLS certificate generation failed: $output" }
        return KeyStore.getInstance("PKCS12").also { store ->
            Files.newInputStream(storePath).use { store.load(it, password.toCharArray()) }
        }
    }

    private fun writePem(path: Path, label: String, bytes: ByteArray) {
        val encoded = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)
        Files.writeString(path, "-----BEGIN $label-----\n$encoded\n-----END $label-----\n")
    }
}
