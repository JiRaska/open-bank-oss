// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.contract

import io.quarkus.security.runtime.QuarkusSecurityIdentity

internal object PensionPactReplayIdentity {
    val identity: QuarkusSecurityIdentity = QuarkusSecurityIdentity.builder()
        // A verified service-account token (azp bound to preferred_username): the reservation
        // follows ServiceAccountIdentity.verifiedPrincipalName, never a bare principal name.
        .setPrincipal(
            io.mockk.mockk<org.eclipse.microprofile.jwt.JsonWebToken>(relaxed = true) {
                io.mockk.every { name } returns "service-account-openbank-pension"
                io.mockk.every { subject } returns "pension-sa"
                io.mockk.every { getClaim<Any?>("azp") } returns "openbank-pension"
                io.mockk.every { getClaim<Any?>("preferred_username") } returns "service-account-openbank-pension"
            },
        )
        .addRole("ROLE_API")
        .build()
}
