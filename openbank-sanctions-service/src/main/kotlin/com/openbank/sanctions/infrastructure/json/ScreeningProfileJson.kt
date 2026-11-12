// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsMatch
import com.openbank.sanctions.domain.model.ScreeningProfile
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/*
 * The screening-profile fields (#15723) are absent, not null, on a check screened without them.
 * A profile-less check therefore keeps the exact JSON it had before those fields existed — on the
 * REST response, in the stored `matches` payload and in the outbox event alike. The rule lives
 * here as Jackson mix-ins because the domain model carries no serialisation annotations (ADR-0002).
 */

@Suppress("unused") // read reflectively by Jackson as a mix-in
internal abstract class SanctionsCheckAbsentFields {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    abstract val profile: ScreeningProfile?

    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    abstract val country: String?

    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    abstract val bankIdentifier: String?

    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    abstract val pepStatus: SanctionsCheckStatus?
}

@Suppress("unused") // read reflectively by Jackson as a mix-in
internal abstract class SanctionsMatchAbsentFields {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    abstract val matchKind: MatchKind?
}

/** Apply the mix-ins to a mapper this service builds by hand (persistence and outbox payloads). */
fun ObjectMapper.omitAbsentScreeningProfileFields(): ObjectMapper = this
    .addMixIn(SanctionsCheck::class.java, SanctionsCheckAbsentFields::class.java)
    .addMixIn(SanctionsMatch::class.java, SanctionsMatchAbsentFields::class.java)

/** Apply the same mix-ins to the container-managed mapper that renders REST responses. */
@Singleton
class ScreeningProfileJsonCustomizer : ObjectMapperCustomizer {
    override fun customize(objectMapper: ObjectMapper) {
        objectMapper.omitAbsentScreeningProfileFields()
    }
}
