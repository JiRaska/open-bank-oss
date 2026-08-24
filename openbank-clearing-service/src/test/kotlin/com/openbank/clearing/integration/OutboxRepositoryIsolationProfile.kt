// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.clearing.integration

import io.quarkus.test.junit.QuarkusTestProfile

/** Keeps repository fixtures owned by the test, not the background dispatcher. */
class OutboxRepositoryIsolationProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
}
