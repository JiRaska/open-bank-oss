// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.pack

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.OnboardingRules
import com.openbank.pension.domain.pack.PackNotFoundException
import com.openbank.pension.domain.questionnaire.QuestionSet
import com.openbank.pension.domain.questionnaire.QuestionSetRegistry

/**
 * Question sets keyed by `(jurisdiction, productLine)`; the highest version is the one asked. Fails
 * closed at BOOT: every onboarding pack must have a question set of the SAME regime, so a product
 * line can never be onboarded with a questionnaire built for another regime.
 */
class StaticQuestionSetRegistry(sets: List<QuestionSet>, onboarding: List<OnboardingRules>) : QuestionSetRegistry {

    private val byVersion: Map<Pair<String, Int>, QuestionSet> = sets.associateBy { it.id to it.version }
    private val latest: Map<Pair<String, ProductLine>, QuestionSet> =
        sets.groupBy { it.jurisdiction to it.productLine }.mapValues { (_, v) -> v.maxBy { it.version } }

    init {
        val keys = sets.map { Triple(it.jurisdiction, it.productLine, it.version) }
        require(keys.toSet().size == keys.size) { "duplicate question set version" }
        require(byVersion.size == sets.size) { "duplicate question set id/version" }
        onboarding.forEach { rules ->
            val set = latest[rules.jurisdiction to rules.productLine]
            require(set != null) { "no question set for ${rules.jurisdiction}/${rules.productLine}" }
            require(set.regime == rules.questionnaire.regime) {
                "question set ${set.id} is ${set.regime} but ${rules.jurisdiction}/${rules.productLine} " +
                    "requires ${rules.questionnaire.regime}"
            }
        }
    }

    override fun questionSet(jurisdiction: String, productLine: ProductLine): QuestionSet =
        latest[jurisdiction to productLine]
            ?: throw PackNotFoundException("no question set for $jurisdiction/$productLine")

    override fun questionSet(id: String, version: Int): QuestionSet = byVersion[id to version]
        ?: throw PackNotFoundException("no question set $id version $version")
}

/** Loads `jurisdiction-packs/questionnaire/` through `index.json` with a STRICT mapper (ADR-0212 D2). */
object QuestionSetLoader {

    private const val ROOT = "jurisdiction-packs/questionnaire"

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)

    fun loadAll(): List<QuestionSet> {
        val files: List<String> = mapper.readValue(read("$ROOT/index.json"))
        check(files.isNotEmpty()) { "question set index is empty" }
        return files.map { file ->
            runCatching { mapper.readValue<QuestionSet>(read("$ROOT/$file")) }
                .getOrElse { throw IllegalStateException("invalid question set $file: ${it.message}", it) }
        }
    }

    private fun read(path: String): String =
        checkNotNull(QuestionSetLoader::class.java.classLoader.getResourceAsStream(path)) {
            "question set resource $path is missing"
        }.use { it.readBytes().decodeToString() }
}
