// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.net.URI
import java.util.Optional
import java.util.UUID

@ConfigMapping(prefix = "openbank.pension-fund.accounting")
interface PensionAccountingConfig {
    @WithDefault("false")
    fun deliveryEnabled(): Boolean
    fun companyBookId(): Optional<UUID>
    fun companyUrl(): Optional<URI>
    fun funds(): Map<String, URI>
    @WithDefault("5s")
    fun pollInterval(): java.time.Duration
    @WithDefault("5m")
    fun closePollInterval(): java.time.Duration
}
