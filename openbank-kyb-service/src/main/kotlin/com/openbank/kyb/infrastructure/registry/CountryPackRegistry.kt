// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryDescriptor
import com.openbank.kyb.domain.model.UboRegisterDescriptor
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.LocalDate

/**
 * Loads every pack file listed in `country-packs/index.json` (classpath directories cannot be
 * enumerated reliably, so the index is explicit) and serves the pack effective for a country on a
 * date. Two versions of one country may coexist; the newest effective one wins.
 */
@ApplicationScoped
class CountryPackRegistry(private val mapper: ObjectMapper) {

    private val log = Logger.getLogger(CountryPackRegistry::class.java)

    val packs: List<CountryPack> by lazy { load() }

    fun packFor(country: String?, on: LocalDate): CountryPack? {
        val cc = country?.trim()?.uppercase() ?: return null
        return packs.filter { it.country == cc && it.isEffectiveOn(on) }.maxByOrNull { it.version }
    }

    /** The pack the scheme's issuing country ships, if any (cross-border schemes have none). */
    fun packForScheme(scheme: IdentifierScheme, on: LocalDate): CountryPack? = packFor(scheme.country, on)

    private fun load(): List<CountryPack> {
        val index =
            resource("country-packs/index.json")
                ?: return emptyList<CountryPack>().also { log.warn("no country-packs/index.json on the classpath") }
        return mapper.readTree(index).path("packs").mapNotNull { file ->
            val json =
                resource("country-packs/${file.asText()}")
                    ?: return@mapNotNull null.also { log.warnf("country pack %s listed but missing", file.asText()) }
            parse(mapper.readTree(json)).also {
                log.infof("country pack loaded: %s v%d effective %s", it.country, it.version, it.effectiveFrom)
            }
        }
    }

    private fun resource(path: String): ByteArray? =
        Thread.currentThread().contextClassLoader.getResourceAsStream(path)?.use {
            it.readBytes()
        }

    internal fun parse(n: JsonNode): CountryPack = CountryPack(
        country = n.path("country").asText().uppercase(),
        version = n.path("version").asInt(1),
        effectiveFrom = LocalDate.parse(n.path("effectiveFrom").asText()),
        displayName = n.path("displayName").properties().associate { (k, v) -> k to v.asText() },
        schemes = n.path("schemes").map { IdentifierScheme.valueOf(it.asText()) },
        registry = n.path("registry").let {
            RegistryDescriptor(
                adapter = it.path("adapter").takeIf { a -> a.isTextual }?.asText(),
                name = it.path("name").asText(""),
                publicSource = it.path("publicSource").takeIf { a -> a.isTextual }?.asText(),
                free = it.path("free").asBoolean(false),
                listsRepresentatives = it.path("listsRepresentatives").asBoolean(false),
                listsRepresentationRule = it.path("listsRepresentationRule").asBoolean(false),
            )
        },
        uboRegister = n.path("uboRegister").let {
            UboRegisterDescriptor(
                name = it.path("name").takeIf { a -> a.isTextual }?.asText(),
                publicApi = it.path("publicApi").asBoolean(false),
                fallback = it.path("fallback").asText("SELF_DECLARATION"),
                threshold = it.path("threshold").asDouble(UBO_DEFAULT_THRESHOLD),
                legalBasis = it.path("legalBasis").takeIf { a -> a.isTextual }?.asText(),
            )
        },
        representationRuleParser = n.path("representationRuleParser").takeIf { it.isTextual }?.asText(),
        legalForms = n.path("legalForms").properties().associate { (k, v) ->
            k to LegalFormClass.valueOf(v.asText())
        },
        legalFormLabels = n.path("legalFormLabels").properties().associate { (k, v) ->
            k to v.properties().associate { (lang, label) -> lang to label.asText() }
        },
        requiredEvidence = n.path("requiredEvidence").properties().associate { (k, v) ->
            LegalFormClass.valueOf(k) to v.map { it.asText() }
        },
        amlLegalBasis = n.path("amlLegalBasis").takeIf { it.isTextual }?.asText(),
    )

    private companion object {
        const val UBO_DEFAULT_THRESHOLD = 0.25
    }
}
