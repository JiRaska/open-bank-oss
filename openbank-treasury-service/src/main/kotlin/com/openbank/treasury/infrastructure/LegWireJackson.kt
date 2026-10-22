// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.StubType
import com.openbank.treasury.infrastructure.rest.LegSpecDto
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/**
 * The wire spelling of a leg's `openbank-libs-finance` schedule types, unchanged from when treasury
 * declared its own copies: a frequency is its [Frequency.NAMED] code (`ONCE`, `MONTHLY`, ...), and
 * a leg that names no stub (`null` in the domain) is written and read as `NONE`. One registration
 * covers the REST DTOs, the stored deal legs and the published deal events.
 */
object LegWireJackson {
    const val NO_STUB = "NONE"

    fun module(): SimpleModule = SimpleModule("treasury-leg-wire").apply {
        addSerializer(Frequency::class.java, FrequencySerializer())
        addDeserializer(Frequency::class.java, FrequencyDeserializer())
        addDeserializer(StubType::class.java, StubDeserializer())
        setMixInAnnotation(LegSpec::class.java, NoStubAsNone::class.java)
        setMixInAnnotation(LegSpecDto::class.java, NoStubAsNone::class.java)
    }

    fun register(mapper: ObjectMapper): ObjectMapper = mapper.registerModule(module())

    private abstract class NoStubAsNone {
        @get:JsonInclude(JsonInclude.Include.ALWAYS)
        @get:JsonSerialize(nullsUsing = NoStubSerializer::class)
        abstract val stub: StubType?
    }

    private class FrequencySerializer : StdSerializer<Frequency>(Frequency::class.java) {
        override fun serialize(value: Frequency, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeString(
                Frequency.codeOf(value) ?: throw IllegalArgumentException("frequency ${value.token} has no named code"),
            )
        }
    }

    private class FrequencyDeserializer : StdDeserializer<Frequency>(Frequency::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Frequency {
            val text = p.valueAsString
            return text?.let(Frequency::named) ?: throw ctxt.weirdStringException(
                text,
                Frequency::class.java,
                "not one of ${Frequency.NAMED.keys}",
            )
        }
    }

    private class StubDeserializer : StdDeserializer<StubType>(StubType::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): StubType? {
            val text = p.valueAsString
            if (text == NO_STUB) return null
            return StubType.entries.firstOrNull { it.name == text } ?: throw ctxt.weirdStringException(
                text,
                StubType::class.java,
                "not one of ${StubType.entries.map { it.name } + NO_STUB}",
            )
        }
    }

    class NoStubSerializer : StdSerializer<Any>(Any::class.java) {
        override fun serialize(value: Any?, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeString(NO_STUB)
        }
    }
}

/** Registers [LegWireJackson] on the service's ObjectMapper. */
@Singleton
class LegWireObjectMapperCustomizer : ObjectMapperCustomizer {
    override fun customize(objectMapper: ObjectMapper) {
        LegWireJackson.register(objectMapper)
    }
}
