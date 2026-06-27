// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.domain.model

import java.util.UUID

/**
 * The authenticated customer principal extracted from the customer-realm JWT (ADR-0065).
 * The `sub` claim carries the party ID issued by the `openbank-customers` Keycloak realm.
 */
data class CustomerIdentity(val partyId: UUID)
