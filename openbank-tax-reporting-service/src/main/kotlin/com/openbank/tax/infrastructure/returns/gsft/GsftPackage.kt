// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.domain.model.TaxValidationException
import com.openbank.tax.domain.model.requireValid
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.Deflater
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
 * The ZIP is reproducible ON ONE RUNTIME (fixed entry time, decree file order, fixed deflate level):
 * DEFLATE output may differ between zlib/JDK builds, so an archive hash is only comparable on the
 * same runtime. Attest the per-file SHA-256s (and the archive hash as built); a rebuild elsewhere
 * is checked by the file hashes, never by the archive hash alone. Encryption is not deterministic
 * (session key).
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
            zip.setLevel(Deflater.BEST_COMPRESSION)
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
        requireValid(usable(recipient, Instant.now())) {
            "GSFT key ${java.lang.Long.toHexString(recipient.keyID)} is not a valid encryption key"
        }
        val generator = PGPEncryptedDataGenerator(
            JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
                .setProvider(BC_PROVIDER),
        )
        generator.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(recipient).setProvider(BC_PROVIDER))
        // Streamed: the literal packet is written straight into the encrypted stream (partial
        // packets), so the batch is not copied several times in memory.
        val out = ByteArrayOutputStream()
        generator.open(out, ByteArray(STREAM_BUFFER)).use { encrypted ->
            PGPLiteralDataGenerator()
                .open(encrypted, PGPLiteralData.BINARY, fileName, payload.size.toLong(), Date(0))
                .use { it.write(payload) }
        }
        return out.toByteArray()
    }

    /**
     * The key to encrypt to, from ONE ASCII-armoured public key block (as GSFT publishes it): an
     * encryption-capable key that is not revoked, not expired at [now] and, where its self-signature
     * carries key flags, flagged for encryption. A subkey is preferred over the primary, the newest
     * over older ones. Anything else — no such key, a parse failure — is a [TaxValidationException].
     */
    fun readPublicKey(armored: String, now: Instant = Instant.now()): PGPPublicKey {
        val rings = parseRings(armored)
        val keys = rings.keyRings.asSequence().flatMap { it.publicKeys.asSequence() }.toList()
        requireValid(keys.isNotEmpty()) { "the configured GSFT public key block holds no key" }
        return keys.filter { usable(it, now) }
            .sortedWith(compareBy<PGPPublicKey> { it.isMasterKey }.thenByDescending { it.creationTime })
            .firstOrNull()
            ?: throw TaxValidationException("the configured GSFT public key holds no valid encryption key")
    }

    private fun parseRings(armored: String): PGPPublicKeyRingCollection = try {
        ArmoredInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.US_ASCII))).use {
            PGPPublicKeyRingCollection(it, JcaKeyFingerprintCalculator())
        }
    } catch (e: IOException) {
        throw unreadable(e)
    } catch (e: PGPException) {
        throw unreadable(e)
    }

    private fun unreadable(cause: Exception): TaxValidationException =
        TaxValidationException("the configured GSFT public key is not a readable key block: ${cause.message}")
            .apply { initCause(cause) }

    private fun usable(key: PGPPublicKey, now: Instant): Boolean {
        if (!key.isEncryptionKey || key.hasRevocation()) return false
        val validSeconds = key.validSeconds
        if (validSeconds > 0 && !key.creationTime.toInstant().plusSeconds(validSeconds).isAfter(now)) return false
        val flags = key.signatures.asSequence().mapNotNull { it.hashedSubPackets?.keyFlags }.filter { it != 0 }.toList()
        return flags.isEmpty() || flags.any { it and ENCRYPT_FLAGS != 0 }
    }

    /** The archive name. NOT stated by the annex — "unverified" until GSFT confirms one. */
    fun archiveName(batchSuffix: String): String = "GSFT$batchSuffix.zip"

    private val BC_PROVIDER = org.bouncycastle.jce.provider.BouncyCastleProvider()
    private const val STREAM_BUFFER = 1 shl 16
    private const val ENCRYPT_FLAGS = KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE
}
