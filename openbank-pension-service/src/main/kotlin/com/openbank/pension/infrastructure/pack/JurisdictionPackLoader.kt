// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.pack

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.JurisdictionPackRegistry

/**
 * Loads the reviewed pack files under `jurisdiction-packs/` (ADR-0334 §3). The list is an explicit
 * `index.json` rather than a classpath directory scan, because a scan behaves differently inside
 * the fast-jar than on a developer's file system — and a pack silently missing in the cluster is
 * a jurisdiction that fails closed for no visible reason.
 *
 * The mapper is private and STRICT: an unknown key is a load failure, never ignored, so a typo in a
 * statutory field cannot quietly fall back to a default (ADR-0212 D2).
 */
object JurisdictionPackLoader {

    private const val ROOT = "jurisdiction-packs"

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)

    fun loadRegistry(): JurisdictionPackRegistry = JurisdictionPackRegistry(loadAll())

    fun loadAll(): List<JurisdictionPack> {
        val files: List<String> = mapper.readValue(read("$ROOT/index.json"))
        check(files.isNotEmpty()) { "jurisdiction pack index is empty" }
        return files.map { file ->
            runCatching { mapper.readValue<JurisdictionPack>(read("$ROOT/$file")) }
                .getOrElse { throw IllegalStateException("invalid jurisdiction pack $file: ${it.message}", it) }
        }
    }

    private fun read(path: String): String =
        checkNotNull(JurisdictionPackLoader::class.java.classLoader.getResourceAsStream(path)) {
            "jurisdiction pack resource $path is missing"
        }.use { it.readBytes().decodeToString() }
}
