// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.it

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.testing.containers.PostgresBase

class ReferralPostgresTestResource : PostgresBase(RESOURCE_SCOPE_ID) {
    override fun init(initArgs: Map<String, String>) {
        super.init(mapOf("db" to "openbank_referral_it") + initArgs)
    }

    override fun start(): Map<String, String> = postgresConfig(startPostgres())

    private companion object {
        val RESOURCE_SCOPE_ID = Ids.randomId().toString()
    }
}
