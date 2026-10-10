// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.testsupport

import com.openbank.pension.application.ProviderBoundary
import java.util.UUID

/** Matches the explicit test-profile provider in application.yaml; never a production default. */
object ProviderFixtures {
    val ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val boundary = ProviderBoundary(ID)
}
