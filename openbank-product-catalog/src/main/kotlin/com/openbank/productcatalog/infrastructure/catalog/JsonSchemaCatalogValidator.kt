// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.CatalogSchemaValidator
import com.openbank.productcatalog.domain.catalog.CatalogValue
import com.openbank.productcatalog.domain.catalog.SchemaValidationResult
import com.openbank.productcatalog.domain.catalog.SchemaViolation
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class JsonSchemaCatalogValidator(private val catalogJson: CatalogJson, private val profile: CatalogSchemaProfile) :
    CatalogSchemaValidator {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val schemas = ConcurrentHashMap<String, com.networknt.schema.JsonSchema>()
    private val config = SchemaValidatorsConfig().apply {
        isTypeLoose = false
        isFailFast = false
        setFormatAssertionsEnabled(true)
        isCacheRefs = true
    }

    override fun validate(schema: CatalogSchema, attributes: CatalogValue.ObjectValue): SchemaValidationResult {
        val document = catalogJson.toNode(schema.document)
        profile.requireValid(document, "urn:catalog-schema:${schema.ref.id}:${schema.ref.version}")
        val compiled = schemas.computeIfAbsent(schema.sha256) { factory.getSchema(document, config) }
        val messages = compiled.validate(catalogJson.toNode(attributes))
            .sortedWith(compareBy({ it.instanceLocation.toString() }, { it.code }, { it.message }))
            .take(MAX_VIOLATIONS)
        if (messages.isEmpty()) return SchemaValidationResult.Valid
        return SchemaValidationResult.Invalid(
            messages.map {
                SchemaViolation(
                    instancePath = it.instanceLocation.toString(),
                    schemaPath = it.evaluationPath.toString(),
                    keyword = it.code,
                    message = it.message,
                )
            },
        )
    }

    private companion object {
        const val MAX_VIOLATIONS = 100
    }
}
