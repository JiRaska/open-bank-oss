// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.integration

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Ephemeral independent server/client identities for the real mTLS listener test. */
class ServerMutualTlsTestResource : QuarkusTestResourceLifecycleManager {
    private var directory: Path? = null

    override fun start(): Map<String, String> {
        val dir = Files.createTempDirectory("sdd-server-mtls-")
        directory = dir
        val password = UUID.randomUUID().toString()
        val serverStore = generateStore(
            dir.resolve("server.p12"),
            "server",
            "CN=localhost",
            "SAN=dns:localhost,ip:127.0.0.1",
            password,
        )
        val clientStore = generateStore(
            dir.resolve("client.p12"),
            "client",
            "CN=sdd-it-client",
            "EKU=clientAuth",
            password,
        )
        val serverCertificate = serverStore.getCertificate("server")
        val clientCertificate = clientStore.getCertificate("client")
        val serverCertPath = dir.resolve("server.crt")
        val serverKeyPath = dir.resolve("server.key")
        val clientCertPath = dir.resolve("client.crt")
        writePem(serverCertPath, "CERTIFICATE", serverCertificate.encoded)
        writePem(serverKeyPath, "PRIVATE KEY", serverStore.getKey("server", password.toCharArray()).encoded)
        writePem(clientCertPath, "CERTIFICATE", clientCertificate.encoded)

        return mapOf(
            "quarkus.http.ssl.certificate.files" to serverCertPath.toString(),
            "quarkus.http.ssl.certificate.key-files" to serverKeyPath.toString(),
            // Match the GitOps PEM property: the listener trusts this client identity.
            "quarkus.http.ssl.certificate.trust-store-files" to clientCertPath.toString(),
            "quarkus.http.ssl.protocols" to "TLSv1.3",
            "quarkus.http.test-ssl-port" to "0",
            "quarkus.http.insecure-requests" to "enabled",
            "sdd.it.tls.client-store" to dir.resolve("client.p12").toString(),
            "sdd.it.tls.client-store-password" to password,
            "sdd.it.tls.server-certificate" to serverCertPath.toString(),
            "sdd.it.tls.untrusted-certificate" to clientCertPath.toString(),
        )
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
        extension: String,
        password: String,
    ): KeyStore {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", subject, "-ext", extension, "-validity", "1", "-storetype", "PKCS12",
            "-keystore", storePath.toString(), "-storepass", password, "-noprompt",
        ).redirectErrorStream(true).start()
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
