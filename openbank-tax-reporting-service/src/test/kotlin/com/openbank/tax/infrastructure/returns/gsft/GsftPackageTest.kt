// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.util.Date
import java.util.zip.ZipInputStream

class GsftPackageTest {
    private val bc = BouncyCastleProvider()
    private val files = linkedMapOf(
        "DIA6363.00001" to "T,a\r\n".toByteArray(),
        "DIW6363.00001" to "R,w\r\n".toByteArray(),
        "DIC6363.00001" to "E,\u008a\r\n".toByteArray(Charsets.ISO_8859_1),
    )

    /** A throwaway test key pair (RSA-2048), generated per run — no key material is committed. */
    private fun keyPair(): PGPKeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA", bc).apply { initialize(2048) }
        return JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, kpg.generateKeyPair(), Date())
    }

    private fun armored(pair: PGPKeyPair): String {
        val sha1 = JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)
        val ring = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pair,
            "test-recipient",
            sha1,
            null,
            null,
            JcaPGPContentSignerBuilder(pair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(bc),
            JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1).setProvider(bc)
                .build("x".toCharArray()),
        ).generatePublicKeyRing()
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { ring.encode(it) }
        return out.toString(Charsets.US_ASCII)
    }

    private fun decrypt(message: ByteArray, pair: PGPKeyPair): Pair<String, ByteArray> {
        val list = JcaPGPObjectFactory(message).nextObject() as PGPEncryptedDataList
        val data = list.encryptedDataObjects.next() as PGPPublicKeyEncryptedData
        val factory = JcePublicKeyDataDecryptorFactoryBuilder().setProvider(bc).build(pair.privateKey)
        assertThat(data.getSymmetricAlgorithm(factory)).isEqualTo(SymmetricKeyAlgorithmTags.AES_256)
        val clear = data.getDataStream(factory)
        val literal = JcaPGPObjectFactory(clear).nextObject() as PGPLiteralData
        val bytes = literal.inputStream.readBytes()
        assertThat(data.isIntegrityProtected).isTrue()
        assertThat(data.verify()).isTrue()
        return literal.fileName to bytes
    }

    private fun unzip(zip: ByteArray): List<Pair<String, ByteArray>> =
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.map { it.name to z.readBytes() }.toList()
        }

    @Test
    fun `zips the batch files without paths in decree order with their exact bytes`() {
        val entries = unzip(GsftPackage.zip(files))
        assertThat(entries.map { it.first }).containsExactly("DIW6363.00001", "DIC6363.00001", "DIA6363.00001")
        entries.forEach { (name, bytes) -> assertThat(bytes).isEqualTo(files.getValue(name)) }
    }

    @Test
    fun `entries carry the fixed ZIP epoch time, not the clock`() {
        val times = ZipInputStream(ByteArrayInputStream(GsftPackage.zip(files))).use { z ->
            generateSequence { z.nextEntry }.map { it.timeLocal.toString() }.toList()
        }
        assertThat(times).containsOnly("1980-01-01T00:00")
    }

    @Test
    fun `the archive is deterministic`() {
        assertThat(GsftPackage.zip(files)).isEqualTo(
            GsftPackage.zip(
                LinkedHashMap(
                    files.entries.reversed().associate {
                        it.toPair()
                    },
                ),
            ),
        )
    }

    @Test
    fun `rejects a path, a foreign file and a batch without DIW`() {
        assertThatThrownBy { GsftPackage.zip(mapOf("dir/DIW6363.00001" to byteArrayOf())) }
            .isInstanceOf(TaxValidationException::class.java)
        assertThatThrownBy { GsftPackage.zip(mapOf("DIW6363.00001" to byteArrayOf(), "XYZ" to byteArrayOf())) }
            .isInstanceOf(TaxValidationException::class.java)
        assertThatThrownBy { GsftPackage.zip(mapOf("DIC6363.00001" to byteArrayOf())) }
            .isInstanceOf(TaxValidationException::class.java).hasMessageContaining("DIW")
    }

    @Test
    fun `encrypts to the configured key and only that key's holder recovers the exact archive`() {
        val pair = keyPair()
        val zip = GsftPackage.zip(files)
        val recipient = GsftPackage.readPublicKey(armored(pair))
        val message = GsftPackage.encrypt(zip, "GSFT6363.00001.zip", recipient)

        assertThat(message).isNotEqualTo(zip)
        assertThat(String(message, Charsets.ISO_8859_1)).doesNotContain("DIW6363.00001")
        val (name, clear) = decrypt(message, pair)
        assertThat(name).isEqualTo("GSFT6363.00001.zip")
        assertThat(clear).isEqualTo(zip)
    }

    @Test
    fun `a different private key cannot open the package`() {
        val message = GsftPackage.encrypt(
            GsftPackage.zip(files),
            "x.zip",
            GsftPackage.readPublicKey(armored(keyPair())),
        )
        assertThatThrownBy { decrypt(message, keyPair()) }.isNotNull()
    }

    @Test
    fun `a key ring with no key fails clearly`() {
        assertThatThrownBy { GsftPackage.readPublicKey("not a key") }.isNotNull()
    }
}
