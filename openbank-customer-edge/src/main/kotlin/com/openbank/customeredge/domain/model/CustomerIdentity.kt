// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.domain.model

import java.util.UUID

/**
 * The effective profile may be a company, but the authenticated SCA actor is always the human
 * from the customer-realm JWT. They coincide on a personal profile.
 */
data class CustomerIdentity(val partyId: UUID, val actorPartyId: UUID = partyId)
