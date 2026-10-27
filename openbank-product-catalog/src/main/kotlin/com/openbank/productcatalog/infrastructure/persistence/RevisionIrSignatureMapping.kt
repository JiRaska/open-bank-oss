// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.RevisionIrSignature
import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/** Row <-> domain mapping for the ADR-0352 publish-path signature (V16). */
internal fun CatalogRevisionIrSignatureEntity.toDomain() =
    RevisionIrSignature(revisionHash, keyId, algorithm, signature, signedAt)

internal fun signatureEntity(revisionId: UUID, sig: RevisionIrSignature) = CatalogRevisionIrSignatureEntity().apply {
    this.revisionId = revisionId
    revisionHash = sig.revisionHash
    keyId = sig.keyId
    algorithm = sig.algorithm
    signature = sig.signature
    signedAt = sig.signedAt
}

/** Every revision read carries its IR signature (ADR-0352 §4): null means unsigned, never "not loaded". */
internal fun Mutiny.Session.withIrSignature(
    row: CatalogRevisionEntity?,
    toDomain: (CatalogRevisionEntity) -> ProductRevision,
): Uni<ProductRevision?> = if (row == null) {
    Uni.createFrom().item(null as ProductRevision?)
} else {
    find(CatalogRevisionIrSignatureEntity::class.java, row.id)
        .map { sig -> toDomain(row).copy(irSignature = sig?.toDomain()) }
}

/** Persists the publish-path signature in the publishing transaction; a no-op for a revision without IR. */
internal fun Mutiny.Session.persistIrSignature(revisionId: UUID, sig: RevisionIrSignature?): Uni<Void> =
    if (sig == null) Uni.createFrom().voidItem() else persist(signatureEntity(revisionId, sig))

/** The list form of [withIrSignature]: one query for every signature of [rows], in row order. */
internal fun Mutiny.Session.withIrSignatures(
    rows: List<CatalogRevisionEntity>,
    toDomain: (CatalogRevisionEntity) -> ProductRevision,
): Uni<List<ProductRevision>> = if (rows.isEmpty()) {
    Uni.createFrom().item(emptyList())
} else {
    createQuery(
        "FROM CatalogRevisionIrSignatureEntity WHERE revisionId IN :ids",
        CatalogRevisionIrSignatureEntity::class.java,
    ).setParameter("ids", rows.map { it.id }).resultList.map { sigs ->
        val byRevision = sigs.associateBy { it.revisionId }
        rows.map { row -> toDomain(row).copy(irSignature = byRevision[row.id]?.toDomain()) }
    }
}
