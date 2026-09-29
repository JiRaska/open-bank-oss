// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.domain.model

import java.util.UUID

/**
 * The authenticated customer principal extracted from the customer-realm JWT (ADR-0065).
 * The `sub` claim carries the party ID issued by the `openbank-customers` Keycloak realm.
 */
data class CustomerIdentity(
    /** The party downstream calls are scoped to: the entity under `X-Acting-For`, otherwise the human. */
    val partyId: UUID,
    /**
     * The natural person who authenticated — never switched by `X-Acting-For`. Strong customer
     * authentication is always the HUMAN's (PSD2 RTS Art. 4: authentication is of the payment
     * service user, and a legal entity has no inherence or possession factor of its own), so SCA
     * challenges, device enrolment and decisions bind to this, with the entity only as context.
     */
    val human: UUID = partyId,
) {
    /** The entity being acted for, or null when the request is the human's own. */
    val actingFor: UUID? get() = partyId.takeIf { it != human }
}
