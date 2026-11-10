// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.usecase.StatutoryReturnService
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.returns.ReturnStatus
import com.openbank.tax.domain.returns.StatutoryReturn
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.ByteArrayInputStream
import java.util.Optional
import java.util.UUID
import java.util.zip.ZipInputStream

/** A submitted GSFT return and the encrypted package that was handed over for it. */
class GsftDeliveryResult(val statutoryReturn: StatutoryReturn, val fileName: String, val encrypted: ByteArray)

/**
 * Submits an APPROVED `cz-gsft` return (issue #15356): reloads the stored archive by the SHA-256
 * the approver attested, proves the archive and every file inside it still hash to the attested
 * lines, encrypts it to GSFT's configured public key and only then records the submission.
 *
 * The key is `openbank.gsft.pgp-public-key`, read as Optional so the service boots without it;
 * delivery refuses by name while it is absent. Encryption runs BEFORE the state change, so a
 * key or package failure never leaves a return SUBMITTED with nothing delivered.
 */
@ApplicationScoped
class GsftDelivery(
    private val service: StatutoryReturnService,
    private val packages: GsftPackageStore,
    @ConfigProperty(name = "openbank.gsft.pgp-public-key") private val publicKey: Optional<String>,
) {
    suspend fun deliver(id: UUID, reference: String, by: String): GsftDeliveryResult {
        val armored = publicKey.map { it.trim() }.filter { it.isNotEmpty() }.orElseThrow {
            ReturnDataUnavailableException("openbank.gsft.pgp-public-key is not configured; no GSFT package can be encrypted")
        }
        val current = service.get(id)
        if (current.catalogueId != GsftReturnDataAdapter.CATALOGUE_ID) {
            throw TaxConflictException("${current.returnCode} is not a GSFT return")
        }
        if (current.status != ReturnStatus.APPROVED) {
            throw TaxConflictException(
                "${current.returnCode} ${current.period.label} is not APPROVED (status=${current.status})",
            )
        }
        val stored = verifiedArchive(current)
        val encrypted = GsftPackage.encrypt(stored.archive, stored.archiveName, GsftPackage.readPublicKey(armored))
        val submitted = service.submit(id, reference, by)
        return GsftDeliveryResult(submitted, "${stored.archiveName}.pgp", encrypted)
    }

    /** The stored archive, refused unless it and each entry match the attested lines exactly. */
    private suspend fun verifiedArchive(attested: StatutoryReturn): StoredGsftPackage {
        val archiveLines = attested.lines.filter { it.fields["kind"] == GsftReturnDataAdapter.KIND_ARCHIVE }
        val archiveLine = archiveLines.singleOrNull()
            ?: throw TaxConflictException("${attested.returnCode}: expected one ARCHIVE line, found ${archiveLines.size}")
        val sha = archiveLine.fields.getValue("sha256").orEmpty()
        val stored = packages.get(sha)
            ?: throw TaxConflictException("${attested.returnCode}: the attested archive $sha is not stored")
        if (sha256Hex(stored.archive) != sha || stored.archiveName != archiveLine.fields["name"]) {
            throw TaxConflictException("${attested.returnCode}: the stored archive does not match the attestation")
        }
        val expected = attested.lines.filter { it.fields["kind"] == GsftReturnDataAdapter.KIND_FILE }
            .associate { it.fields.getValue("name").orEmpty() to it.fields.getValue("sha256").orEmpty() }
        val actual = entries(stored.archive).mapValues { sha256Hex(it.value) }
        if (actual != expected) {
            throw TaxConflictException("${attested.returnCode}: the archive's files do not match the attested files")
        }
        return stored
    }

    private fun entries(archive: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes()) }
        }
    }
}
