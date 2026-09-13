// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.`in`.RegistryLookupUseCase
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant

@ApplicationScoped
class RegistryLookupService : RegistryLookupUseCase {

    @Inject lateinit var registry: BusinessRegistryPort

    @Inject lateinit var cache: RegistryExtractCache

    @Inject lateinit var clock: Clock

    /** How long a fetched extract is reused. Register records change rarely; a day is the fleet default. */
    @ConfigProperty(name = "openbank.kyb.extract-cache-ttl", defaultValue = "PT24H")
    lateinit var cacheTtl: Duration

    override suspend fun lookup(cmd: LookupCommand): RegistryExtract? =
        lookup(LegalEntityIdentifier.of(cmd.scheme, cmd.identifier), cmd)

    internal suspend fun lookup(identifier: LegalEntityIdentifier, cmd: LookupCommand?): RegistryExtract? {
        val now = Instant.now(clock)
        cache.find(identifier, now.minus(cacheTtl))?.let { return it }
        val fetched = registry.lookup(identifier, cmd?.declared) ?: return null
        cache.put(fetched)
        return fetched
    }
}
