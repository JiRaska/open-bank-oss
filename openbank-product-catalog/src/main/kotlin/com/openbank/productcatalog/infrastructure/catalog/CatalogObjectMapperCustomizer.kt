// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/** Preserve arbitrary-precision JSON numbers before they enter the framework-free catalog algebra. */
@Singleton
class CatalogObjectMapperCustomizer : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    }
}
