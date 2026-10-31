// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.openbank.libs.product.ir.IrJson
import com.openbank.productcatalog.application.port.out.ApprovalPortfolio
import com.openbank.productcatalog.application.port.out.ApprovalPortfolioSource
import com.openbank.productcatalog.domain.catalog.ProductCodes
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest

/**
 * `approval-portfolios/` on the classpath: openbank-products/portfolios, packaged by this module's
 * build. Read once at startup (`@Startup`), so a manifest naming a missing file fails the boot rather
 * than the first approval.
 *
 * `byProductCode` is the only lookup: its keys are IR product codes (`SAVINGS-FIXED`), matched to an
 * offering code by [ProductCodes.offeringCode] (`SAVINGS_FIXED`). Any other section — a currency
 * fallback above all — is refused at boot rather than silently ignored.
 */
@Startup
@ApplicationScoped
class ClasspathApprovalPortfolios internal constructor(
    /** Reads `approval-portfolios/<name>`; the classpath in production, a map in the loader's own test. */
    private val resource: (String) -> ByteArray,
) : ApprovalPortfolioSource {

    constructor() : this({ name ->
        checkNotNull(ClasspathApprovalPortfolios::class.java.getResourceAsStream("/$DIR/$name")) {
            "$DIR/$name is missing"
        }
            .use { it.readBytes() }
    })

    private val manifest: Map<String, Any?> = tree(read(MANIFEST)) as? Map<String, Any?>
        ?: error("$DIR/$MANIFEST is not a JSON object")

    private val byOfferingCode: Map<String, ApprovalPortfolio> = run {
        require(manifest.keys == MANIFEST_KEYS) {
            "$DIR/$MANIFEST must carry exactly $MANIFEST_KEYS, has ${manifest.keys}"
        }
        @Suppress("UNCHECKED_CAST")
        val entries = manifest["byProductCode"] as? Map<String, String>
            ?: error("$DIR/$MANIFEST byProductCode is not an object of file names")
        val loaded = entries.values.toSet().associateWith { name ->
            val bytes = read(name)
            ApprovalPortfolio(name, tree(bytes), sha256(bytes))
        }
        val keyed = entries.entries.groupBy({ ProductCodes.offeringCode(it.key) }, { loaded.getValue(it.value) })
        keyed.forEach { (code, found) -> require(found.size == 1) { "$DIR/$MANIFEST names offering $code twice" } }
        keyed.mapValues { it.value.single() }
    }

    override fun find(offeringCode: String): ApprovalPortfolio? = byOfferingCode[offeringCode]

    private fun read(name: String): ByteArray {
        require(NAME.matches(name)) { "approval portfolio name '$name' is not a plain file name" }
        return resource(name)
    }

    private fun tree(bytes: ByteArray): Any? = IrJson.parse(bytes.toString(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIR = "approval-portfolios"
        const val MANIFEST = "approval.json"
        val MANIFEST_KEYS = setOf("schema", "description", "byProductCode")
        val NAME = Regex("^[a-z0-9][a-z0-9-]*\\.json$")
    }
}
