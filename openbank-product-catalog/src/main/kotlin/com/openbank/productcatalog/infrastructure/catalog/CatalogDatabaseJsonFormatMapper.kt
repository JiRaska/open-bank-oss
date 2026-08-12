// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.hibernate.orm.JsonFormat
import io.quarkus.hibernate.orm.PersistenceUnitExtension
import io.vertx.core.json.JsonObject
import jakarta.inject.Singleton
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.format.FormatMapper
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper

/** Reactive Hibernate receives JSONB as Vert.x objects; keep that adapter explicit and lossless. */
@Singleton
@JsonFormat
@PersistenceUnitExtension
class CatalogDatabaseJsonFormatMapper(mapper: ObjectMapper) : FormatMapper {
    private val delegate = JacksonJsonFormatMapper(mapper.copy())

    override fun <T> fromString(charSequence: CharSequence, javaType: JavaType<T>, wrapperOptions: WrapperOptions): T =
        if (javaType.javaTypeClass == JsonObject::class.java) {
            javaType.cast(JsonObject(charSequence.toString()))
        } else {
            delegate.fromString(charSequence, javaType, wrapperOptions)
        }

    override fun <T> toString(value: T, javaType: JavaType<T>, wrapperOptions: WrapperOptions): String =
        if (value is JsonObject) {
            value.encode()
        } else {
            delegate.toString(value, javaType, wrapperOptions)
        }
}
