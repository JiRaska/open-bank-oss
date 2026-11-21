// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64

/**
 * Ephemeral key material for the gateway-mTLS ITs (#16847, owner decision 2026-11-21): a server
 * certificate for the 8443 listener and TWO client certificates the listener trusts — the edge
 * gateway's (DNS SAN [GATEWAY_SAN], the value pinned in `mcp.edge.gateway-identity`) and another
 * fleet workload's ([OTHER_SAN]). Both are trusted at the TLS layer, as every fleet-CA certificate
 * is in the cluster, so only the SAN pin separates them. Nothing is committed; the directory and
 * password travel through system properties because a test profile's resources load in a
 * different classloader from the test class.
 */
class GatewayMtlsMaterial : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> {
        val dir = Files.createTempDirectory("mcp-gateway-mtls-")
        val password = Ids.randomId().toString()
        generate(dir, "server", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1", password)
        // The gateway's CN deliberately differs from its SAN: the pin is the SAN, never the CN.
        generate(dir, "gateway", "CN=$OTHER_SAN", "SAN=dns:$GATEWAY_SAN", password)
        generate(dir, "other", "CN=$GATEWAY_SAN", "SAN=dns:$OTHER_SAN", password)
        val server = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(dir.resolve("server.p12")).use { load(it, password.toCharArray()) }
        }
        write(dir.resolve("tls.crt"), "CERTIFICATE", server.getCertificate("server").encoded)
        write(dir.resolve("tls.key"), "PRIVATE KEY", server.getKey("server", password.toCharArray()).encoded)
        Files.writeString(dir.resolve("clients.crt"), pemOf(dir, "gateway", password) + pemOf(dir, "other", password))
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", server.getCertificate("server"))
            Files.newOutputStream(dir.resolve("trust.p12")).use { store(it, password.toCharArray()) }
        }
        System.setProperty(DIR_PROPERTY, dir.toString())
        System.setProperty(PASSWORD_PROPERTY, password)
        return mapOf(
            "quarkus.http.ssl.certificate.files" to dir.resolve("tls.crt").toString(),
            "quarkus.http.ssl.certificate.key-files" to dir.resolve("tls.key").toString(),
            "quarkus.http.ssl.certificate.trust-store-files" to dir.resolve("clients.crt").toString(),
            "quarkus.http.ssl.protocols" to "TLSv1.3",
            "quarkus.http.test-ssl-port" to "0",
        ).flatMap { (k, v) -> listOf(k to v, "%test.$k" to v) }.toMap()
    }

    override fun stop() {
        System.getProperty(DIR_PROPERTY)?.let { d ->
            Files.walk(Path.of(d)).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    companion object {
        const val GATEWAY_SAN = "edge-gateway.envoy-gateway-system.test.example"
        const val OTHER_SAN = "other-workload.platform.test.example"
        private const val DIR_PROPERTY = "openbank.test.mcp-gateway-mtls.dir"
        private const val PASSWORD_PROPERTY = "openbank.test.mcp-gateway-mtls.password"
        private const val LINE = 64
        private const val NEWLINE: Byte = 10

        val dir: Path get() = Path.of(System.getProperty(DIR_PROPERTY))
        val password: String get() = System.getProperty(PASSWORD_PROPERTY)

        /** PKCS12 key store of one client identity: "gateway" or "other". */
        fun clientStore(name: String): Path = dir.resolve("$name.p12")

        fun trustStore(): Path = dir.resolve("trust.p12")

        private fun pemOf(dir: Path, alias: String, password: String): String {
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(dir.resolve("$alias.p12")).use { store.load(it, password.toCharArray()) }
            return pem("CERTIFICATE", store.getCertificate(alias).encoded)
        }

        private fun pem(label: String, bytes: ByteArray): String {
            val encoded = Base64.getMimeEncoder(LINE, byteArrayOf(NEWLINE)).encodeToString(bytes)
            return "-----BEGIN $label-----\n$encoded\n-----END $label-----\n"
        }

        private fun write(path: Path, label: String, bytes: ByteArray) = Files.writeString(path, pem(label, bytes))

        private fun generate(dir: Path, alias: String, dname: String, san: String, password: String) {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", dname, "-ext", san, "-validity", "1", "-storetype", "PKCS12",
                "-keystore", dir.resolve("$alias.p12").toString(), "-storepass", password, "-noprompt",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Ephemeral TLS certificate generation failed: $output" }
        }
    }
}
