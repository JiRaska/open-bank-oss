// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Creates independent, ephemeral server and client identities for the real listener test. */
class ServerMutualTlsTestResource : QuarkusTestResourceLifecycleManager {
    private var directory: Path? = null

    override fun start(): Map<String, String> {
        val dir = Files.createTempDirectory("pension-fund-server-mtls-")
        directory = dir
        try {
            val password = UUID.randomUUID().toString()
            val serverStore = generateStore(
                dir.resolve("server.p12"),
                "server",
                "CN=localhost",
                listOf("SAN=dns:localhost,ip:127.0.0.1", "EKU=serverAuth"),
                password,
            )
            val clientStore = generateStore(
                dir.resolve("client.p12"),
                "client",
                "CN=pension-fund-it-client",
                listOf("EKU=clientAuth"),
                password,
            )
            val untrustedStore = generateStore(
                dir.resolve("untrusted-server.p12"),
                "untrusted-server",
                "CN=localhost",
                listOf("SAN=dns:localhost,ip:127.0.0.1", "EKU=serverAuth"),
                password,
            )
            val serverCertificate = serverStore.getCertificate("server")
            val clientCertificate = clientStore.getCertificate("client")
            val untrustedCertificate = untrustedStore.getCertificate("untrusted-server")
            val serverCertPath = dir.resolve("server.crt")
            val serverKeyPath = dir.resolve("server.key")
            val clientCertPath = dir.resolve("client.crt")
            val untrustedCertPath = dir.resolve("untrusted-server.crt")
            writePem(serverCertPath, "CERTIFICATE", serverCertificate.encoded)
            writePem(serverKeyPath, "PRIVATE KEY", serverStore.getKey("server", password.toCharArray()).encoded)
            writePem(clientCertPath, "CERTIFICATE", clientCertificate.encoded)
            writePem(untrustedCertPath, "CERTIFICATE", untrustedCertificate.encoded)

            return mapOf(
                "quarkus.http.ssl.certificate.files" to serverCertPath.toString(),
                "quarkus.http.ssl.certificate.key-files" to serverKeyPath.toString(),
                "quarkus.http.ssl.certificate.trust-store-files" to clientCertPath.toString(),
                "quarkus.http.ssl.protocols" to "TLSv1.3",
                "quarkus.http.ssl.client-auth" to "required",
                "quarkus.http.test-ssl-port" to "0",
                "quarkus.http.insecure-requests" to "enabled",
                "pensionfund.it.tls.client-store" to dir.resolve("client.p12").toString(),
                "pensionfund.it.tls.client-store-password" to password,
                "pensionfund.it.tls.server-certificate" to serverCertPath.toString(),
                "pensionfund.it.tls.untrusted-certificate" to untrustedCertPath.toString(),
            )
        } catch (failure: Throwable) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        directory?.let { dir ->
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        directory = null
    }

    private fun generateStore(
        storePath: Path,
        alias: String,
        subject: String,
        extensions: List<String>,
        password: String,
    ): KeyStore {
        val command = mutableListOf(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", subject,
        )
        extensions.forEach { extension -> command.addAll(listOf("-ext", extension)) }
        command.addAll(
            listOf(
                "-validity", "1", "-storetype", "PKCS12", "-keystore", storePath.toString(),
                "-storepass", password, "-keypass", password, "-noprompt",
            ),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Ephemeral $alias TLS certificate generation failed: $output" }
        return KeyStore.getInstance("PKCS12").also { store ->
            Files.newInputStream(storePath).use { store.load(it, password.toCharArray()) }
        }
    }

    private fun writePem(path: Path, label: String, bytes: ByteArray) {
        val encoded = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)
        Files.writeString(path, "-----BEGIN $label-----\n$encoded\n-----END $label-----\n")
    }
}
