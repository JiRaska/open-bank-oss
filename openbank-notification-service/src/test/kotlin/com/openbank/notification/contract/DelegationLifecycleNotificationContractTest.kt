// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Pins the producer-owned lifecycle vocabulary consumed by the notification fan-out. */
class DelegationLifecycleNotificationContractTest {

    @Test
    fun `delegation contract declares every customer-notified lifecycle event`() {
        val contract = Files.readString(
            Path.of("../openbank-contracts/openbank-delegation-service/asyncapi.yaml"),
        )

        assertThat(contract).contains(
            "DelegationOffered:",
            "DelegationActivated:",
            "DelegationDeclined:",
            "DelegationRevoked:",
            "DelegationSuspended:",
            "DelegationReinstated:",
            "DelegationRenounced:",
            "DelegationExpired:",
        )
        assertThat(contract).contains("grantorPartyId:", "granteePartyId:", "resourceType:")
    }
}
