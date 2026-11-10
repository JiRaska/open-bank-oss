// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.domain.model.TaxValidationException
import com.openbank.tax.domain.model.requireValid
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Transport of the GSFT depositor batch (issue #15356), vyhláška č. 71/2011 Sb., annex 1.1.6:
 *   "Datová dávka je komprimována algoritmem ZIP do jednoho společného souboru" and the compressed
 *   file is "zašifrován asymetrickým šifrovacím algoritmem PGP" (another method of at least equal
 *   strength only by agreement with GSFT).
 *
 * Verified from the annex: one ZIP for the whole batch, the batch files added WITHOUT paths, then
 * PGP public-key encryption.
 * NOT stated by the annex (docs/gsft-depositor-data.md, "unverified"): the ZIP file name, the
 * symmetric cipher and whether the PGP output is ASCII-armoured. This writes binary OpenPGP with
 * AES-256 and an integrity packet (the strongest common choice) — confirm with GSFT before the first
 * live delivery.
 *
 * The ZIP is deterministic (fixed entry time, DEFLATED, decree file order), so the same batch always
 * yields the same archive and its SHA-256 can be attested before encryption. Encryption is not
 * deterministic (session key), which is why the attestation is over the archive.
 *
 * GSFT's public key comes from configuration (`openbank.gsft.pgp-public-key`, Optional) and is
 * never committed.
 */
object GsftPackage {
    /** 1980-01-01 00:00, the ZIP epoch: an entry time that depends on neither the clock nor the zone. */
    private val ZIP_EPOCH: LocalDateTime = LocalDateTime.parse("1980-01-01T00:00")
    private const val PREFIX_LENGTH = 3
    private val FILE_ORDER = listOf("DIW", "DIC", "DIA", "DIL", "DII")

    /** One ZIP over the batch files, entries without paths, in decree order. */
    fun zip(files: Map<String, ByteArray>): ByteArray {
        requireValid(files.isNotEmpty()) { "a GSFT batch has no files" }
        files.keys.forEach { name ->
            requireValid(name.none { it == '/' || it == '\\' }) { "batch file '$name' carries a path" }
            requireValid(name.take(PREFIX_LENGTH) in FILE_ORDER) { "batch file '$name' is not a decree file" }
        }
        requireValid(files.keys.any { it.startsWith("DIW") }) { "a GSFT batch must contain DIW" }
        val ordered = files.entries.sortedWith(
            compareBy({
                FILE_ORDER.indexOf(it.key.take(PREFIX_LENGTH))
            }, { it.key }),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.DEFLATED)
            for ((name, bytes) in ordered) {
                val entry = ZipEntry(name)
                // timeLocal is written as DOS date/time verbatim; `time` (epoch millis) would be
                // converted through the JVM's default time zone and make the bytes host-dependent.
                entry.timeLocal = ZIP_EPOCH
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Binary OpenPGP message for [recipient], AES-256 with an integrity packet. */
    fun encrypt(payload: ByteArray, fileName: String, recipient: PGPPublicKey): ByteArray {
        requireValid(recipient.isEncryptionKey) { "GSFT key ${recipient.keyID} is not an encryption key" }
        val literal = ByteArrayOutputStream()
        PGPLiteralDataGenerator().open(literal, PGPLiteralData.BINARY, fileName, payload.size.toLong(), Date(0))
            .use { it.write(payload) }
        val generator = PGPEncryptedDataGenerator(
            JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
                .setProvider(BC_PROVIDER),
        )
        generator.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(recipient).setProvider(BC_PROVIDER))
        val out = ByteArrayOutputStream()
        generator.open(out, literal.size().toLong()).use { it.write(literal.toByteArray()) }
        return out.toByteArray()
    }

    /** The first encryption-capable key in an ASCII-armoured public key ring (as GSFT publishes it). */
    fun readPublicKey(armored: String): PGPPublicKey {
        val rings = ArmoredInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.US_ASCII))).use {
            PGPPublicKeyRingCollection(it, JcaKeyFingerprintCalculator())
        }
        return rings.keyRings.asSequence().flatMap { it.publicKeys.asSequence() }.firstOrNull { it.isEncryptionKey }
            ?: throw TaxValidationException("the configured GSFT public key ring holds no encryption key")
    }

    /** The archive name. NOT stated by the annex — "unverified" until GSFT confirms one. */
    fun archiveName(batchSuffix: String): String = "GSFT$batchSuffix.zip"

    private val BC_PROVIDER = org.bouncycastle.jce.provider.BouncyCastleProvider()
}
