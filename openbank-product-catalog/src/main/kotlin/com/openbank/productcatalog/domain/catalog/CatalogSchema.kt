// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain.catalog

import java.time.Instant

/** Immutable, hash-addressed schema document contributed by a trusted industry pack. */
data class CatalogSchema(
    val ref: SchemaRef,
    val document: CatalogValue.ObjectValue,
    val sha256: String,
    val registeredAt: Instant,
) {
    init {
        require(SHA256_PATTERN.matches(sha256)) { "sha256 must be 64 lowercase hexadecimal characters" }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

data class SchemaViolation(val instancePath: String, val schemaPath: String, val keyword: String, val message: String)

sealed interface SchemaValidationResult {
    data object Valid : SchemaValidationResult
    data class Invalid(val violations: List<SchemaViolation>) : SchemaValidationResult {
        init {
            require(violations.isNotEmpty()) { "invalid result must contain at least one violation" }
        }
    }
}

/** Port implemented by the JSON Schema 2020-12 infrastructure adapter. */
fun interface CatalogSchemaValidator {
    fun validate(schema: CatalogSchema, attributes: CatalogValue.ObjectValue): SchemaValidationResult
}
