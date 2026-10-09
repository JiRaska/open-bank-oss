// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.pack

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.onboarding.OnboardingRulesRegistry
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.OnboardingRules
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.PackNotFoundException

/** Onboarding extensions keyed by the core pack they extend. */
class StaticOnboardingRulesRegistry(rules: List<OnboardingRules>, corePacks: List<JurisdictionPack>) :
    OnboardingRulesRegistry {

    private val byKey: Map<Triple<String, ProductLine, Int>, OnboardingRules> =
        rules.associateBy { Triple(it.jurisdiction, it.productLine, it.packVersion) }

    init {
        require(byKey.size == rules.size) { "duplicate onboarding rules for one pack version" }
        val core = corePacks.map { Triple(it.jurisdiction, it.productLine, it.version) }.toSet()
        val missing = core - byKey.keys
        // Fail closed at BOOT: a pack with no onboarding rules would otherwise fail on the first
        // application in the cluster, long after the deploy went green.
        require(missing.isEmpty()) { "jurisdiction packs without onboarding rules: $missing" }
        val orphan = byKey.keys - core
        require(orphan.isEmpty()) { "onboarding rules for packs that are not loaded: $orphan" }
    }

    override fun rules(jurisdiction: String, productLine: ProductLine, packVersion: Int): OnboardingRules =
        byKey[Triple(jurisdiction, productLine, packVersion)]
            ?: throw PackNotFoundException("no onboarding rules for $jurisdiction/$productLine v$packVersion")
}

/**
 * Loads `jurisdiction-packs/onboarding/` through an explicit `index.json`, with the same STRICT
 * mapper as the core packs: an unknown key is a load failure, never a silently ignored typo in a
 * statutory field (ADR-0212 D2).
 */
object OnboardingRulesLoader {

    private const val ROOT = "jurisdiction-packs/onboarding"

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)

    fun loadAll(): List<OnboardingRules> {
        val files: List<String> = mapper.readValue(read("$ROOT/index.json"))
        check(files.isNotEmpty()) { "onboarding rules index is empty" }
        return files.map { file ->
            runCatching { mapper.readValue<OnboardingRules>(read("$ROOT/$file")) }
                .getOrElse { throw IllegalStateException("invalid onboarding rules $file: ${it.message}", it) }
        }
    }

    private fun read(path: String): String =
        checkNotNull(OnboardingRulesLoader::class.java.classLoader.getResourceAsStream(path)) {
            "onboarding rules resource $path is missing"
        }.use { it.readBytes().decodeToString() }
}
