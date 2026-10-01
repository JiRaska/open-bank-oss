// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * The JSON form [Money] and [CurrencyCode] are WRITTEN in. Reading never depends on it: the
 * deserialisers accept every shape below, so a producer can change shape without its consumers
 * being redeployed first.
 */
enum class MoneyWireShape {
    /** `{"amount":"10.00","currency":"EUR"}` — the amount as decimal text, the currency as its code. */
    CANONICAL,

    /**
     * The shape bean introspection produced before this module existed: a numeric `amount`, a
     * nested `{"code","defaultFractionDigits"}` currency and the four `is…` predicates. Kept only
     * so a service whose published wire already carries it can stay byte-compatible until its
     * readers accept [CANONICAL]; nothing new should select it.
     */
    LEGACY_OBJECT,
}

/**
 * Jackson binding for [Money] and [CurrencyCode]. It lives here, not on the types, because
 * `openbank-libs-domain` is the framework-free side of the ADR-0122 split: this module IS the
 * serialisation boundary.
 *
 * Every Quarkus service gets it through [MoneyObjectMapperCustomizer]. A hand-built mapper needs
 * `registerModule(MoneyJacksonModule())` (or `findAndRegisterModules()`, which discovers it).
 */
class MoneyJacksonModule(shape: MoneyWireShape = MoneyWireShape.CANONICAL) : SimpleModule("openbank-money") {
    init {
        addSerializer(Money::class.java, MoneySerializer(shape))
        addSerializer(CurrencyCode::class.java, CurrencyCodeSerializer(shape))
        addDeserializer(Money::class.java, MoneyDeserializer())
        addDeserializer(CurrencyCode::class.java, CurrencyCodeDeserializer())
    }
}

/**
 * Installs [MoneyJacksonModule] on the service's `ObjectMapper` — the one REST and every injected
 * mapper share. The written shape is `openbank.json.money-wire-shape` (default [MoneyWireShape.CANONICAL]).
 */
@Singleton
class MoneyObjectMapperCustomizer(
    @ConfigProperty(name = "openbank.json.money-wire-shape", defaultValue = "CANONICAL")
    private val shape: MoneyWireShape,
) : ObjectMapperCustomizer {
    override fun customize(objectMapper: ObjectMapper) {
        objectMapper.registerModule(MoneyJacksonModule(shape))
    }
}

private const val AMOUNT = "amount"
private const val CURRENCY = "currency"
private const val CODE = "code"

internal class MoneySerializer(private val shape: MoneyWireShape) : StdSerializer<Money>(Money::class.java) {
    override fun serialize(value: Money, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        when (shape) {
            MoneyWireShape.CANONICAL -> {
                gen.writeStringField(AMOUNT, value.amount.toPlainString())
                gen.writeStringField(CURRENCY, value.currency.code)
            }
            MoneyWireShape.LEGACY_OBJECT -> {
                gen.writeNumberField(AMOUNT, value.amount)
                gen.writeFieldName(CURRENCY)
                writeLegacyCurrency(value.currency, gen)
                gen.writeBooleanField("isNonNegative", value.isNonNegative())
                gen.writeBooleanField("isZero", value.isZero())
                gen.writeBooleanField("isNegative", value.isNegative())
                gen.writeBooleanField("isPositive", value.isPositive())
            }
        }
        gen.writeEndObject()
    }
}

internal class CurrencyCodeSerializer(private val shape: MoneyWireShape) :
    StdSerializer<CurrencyCode>(CurrencyCode::class.java) {
    override fun serialize(value: CurrencyCode, gen: JsonGenerator, provider: SerializerProvider) = when (shape) {
        MoneyWireShape.CANONICAL -> gen.writeString(value.code)
        MoneyWireShape.LEGACY_OBJECT -> writeLegacyCurrency(value, gen)
    }
}

private fun writeLegacyCurrency(value: CurrencyCode, gen: JsonGenerator) {
    gen.writeStartObject()
    gen.writeStringField(CODE, value.code)
    gen.writeNumberField("defaultFractionDigits", value.defaultFractionDigits)
    gen.writeEndObject()
}

/**
 * Reads `{"amount", "currency"}` where the amount is decimal text or a JSON number and the
 * currency is a code or a `{"code": …}` object; any other member is skipped, which is what lets
 * the [MoneyWireShape.LEGACY_OBJECT] predicates through.
 *
 * The amount is taken as the token's TEXT in both cases and handed to [Money.parseAmount], so a
 * JSON number is never materialised by the parser: the length and magnitude bounds apply to it
 * exactly as they do to text, and no binary floating point sits between the wire and the value.
 */
internal class MoneyDeserializer : StdDeserializer<Money>(Money::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Money {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return mismatch(context, "expected an object with '$AMOUNT' and '$CURRENCY'")
        }
        var amountText: String? = null
        var currency: CurrencyCode? = null
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName()
            val token = parser.nextToken()
            when (name) {
                AMOUNT -> amountText = when (token) {
                    JsonToken.VALUE_STRING, JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> parser.text
                    else -> return mismatch(context, "'$AMOUNT' must be a decimal string or a number")
                }
                CURRENCY -> currency = readCurrency(parser, context)
                else -> parser.skipChildren()
            }
        }
        if (amountText == null || currency == null) {
            return mismatch(context, "'$AMOUNT' and '$CURRENCY' are both required")
        }
        return try {
            Money(Money.parseAmount(amountText), currency)
        } catch (e: IllegalArgumentException) {
            mismatch(context, e.message ?: "invalid amount")
        }
    }

    private fun mismatch(context: DeserializationContext, message: String): Money =
        context.reportInputMismatch(Money::class.java, "Money: %s", message)
}

internal class CurrencyCodeDeserializer : StdDeserializer<CurrencyCode>(CurrencyCode::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): CurrencyCode =
        readCurrency(parser, context)
}

/** A currency is its code, or an object whose `code` member is; the parser is left on the value's last token. */
private fun readCurrency(parser: JsonParser, context: DeserializationContext): CurrencyCode {
    val code: String? = when (parser.currentToken()) {
        JsonToken.VALUE_STRING -> parser.text
        JsonToken.START_OBJECT -> {
            var found: String? = null
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val name = parser.currentName()
                val token = parser.nextToken()
                if (name == CODE && token == JsonToken.VALUE_STRING) found = parser.text else parser.skipChildren()
            }
            found
        }
        else -> null
    }
    if (code == null) {
        return context.reportInputMismatch(
            CurrencyCode::class.java,
            "currency must be a code or an object with '$CODE'",
        )
    }
    return try {
        CurrencyCode.of(code)
    } catch (e: IllegalArgumentException) {
        context.reportInputMismatch(CurrencyCode::class.java, "%s", e.message ?: "invalid currency code")
    }
}
