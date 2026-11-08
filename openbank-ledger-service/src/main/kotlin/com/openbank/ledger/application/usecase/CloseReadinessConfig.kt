// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

import io.smallrye.config.ConfigMapping

/** Each required product job must have a successful persisted receipt in the observed month. */
@ConfigMapping(prefix = "openbank.ledger.close.readiness")
interface CloseReadinessConfig {
    fun requiredJobs(): Map<String, Set<String>>
}
