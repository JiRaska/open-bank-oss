// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.schema

import com.openbank.analytics.application.port.out.SchemaCatalogSource
import com.openbank.libs.analytics.SchemaCatalog
import com.openbank.libs.analytics.SchemaKey
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Default [SchemaCatalogSource]: builds the catalogue from the static config spec
 * `openbank.analytics.schema.known` (comma-separated `eventType:maxVersion`). Empty → ungoverned
 * (gate open). Keeps the service offline-buildable; the durable registry is
 * [ApicurioSchemaCatalogSource].
 */
@ApplicationScoped
class ConfigSchemaCatalogSource : SchemaCatalogSource {

    // Optional<String>, not a plain String (CLAUDE.md pitfall): SmallRye's built-in String converter
    // treats an empty-string-resolved value as "no value" and throws SRCFG00040 at boot.
    @ConfigProperty(name = "openbank.analytics.schema.known")
    lateinit var knownSpec: Optional<String>

    override fun load(): SchemaCatalog = SchemaCatalog(parse(knownSpec.orElse("")))

    internal fun parse(spec: String): Set<SchemaKey> = spec.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val parts = entry.split(":")
            val type = parts.getOrNull(0)?.trim().orEmpty()
            val version = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (type.isNotEmpty() && version != null) SchemaKey(type, version) else null
        }
        .toSet()
}
