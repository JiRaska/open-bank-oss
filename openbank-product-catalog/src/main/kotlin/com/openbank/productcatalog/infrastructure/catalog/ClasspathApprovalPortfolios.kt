// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.openbank.libs.product.ir.IrJson
import com.openbank.productcatalog.application.port.out.ApprovalPortfolio
import com.openbank.productcatalog.application.port.out.ApprovalPortfolioSource
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest

/**
 * `approval-portfolios/` on the classpath: openbank-products/portfolios, packaged by this module's
 * build. Read once at startup (`@Startup`), so a manifest naming a missing file fails the boot rather
 * than the first approval.
 */
@Startup
@ApplicationScoped
class ClasspathApprovalPortfolios : ApprovalPortfolioSource {

    private val manifest: Map<String, Any?> = tree(read(MANIFEST)) as? Map<String, Any?>
        ?: error("$DIR/$MANIFEST is not a JSON object")

    private val portfolios: Map<String, ApprovalPortfolio> =
        (section("byProductCode").values + section("byCurrency").values).toSet().associateWith { name ->
            val bytes = read(name)
            ApprovalPortfolio(name, tree(bytes), sha256(bytes))
        }

    override fun find(productCode: String, currency: String): ApprovalPortfolio? =
        (section("byProductCode")[productCode] ?: section("byCurrency")[currency])?.let(portfolios::get)

    @Suppress("UNCHECKED_CAST")
    private fun section(key: String): Map<String, String> = (manifest[key] as? Map<String, String>).orEmpty()

    private fun read(name: String): ByteArray {
        require(NAME.matches(name)) { "approval portfolio name '$name' is not a plain file name" }
        return checkNotNull(javaClass.getResourceAsStream("/$DIR/$name")) { "$DIR/$name is missing" }
            .use { it.readBytes() }
    }

    private fun tree(bytes: ByteArray): Any? = IrJson.parse(bytes.toString(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIR = "approval-portfolios"
        const val MANIFEST = "approval.json"
        val NAME = Regex("^[a-z0-9][a-z0-9-]*\\.json$")
    }
}
