// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

/**
 * Machine-readable failure codes for the card-issuance business rules. The wire contract is the
 * code, not the message: a caller (customer-edge, admin-ui) branches on these strings, so they are
 * append-only — renaming one is a breaking API change.
 */
enum class CardErrorCode {
    /** The party already holds `cardConfig.maxCards` cards in a live status. */
    CARD_QUOTA_EXCEEDED,

    /** The product's `cardConfig.enabled` is false — this product does not carry cards at all. */
    CARD_PRODUCT_DISABLED,

    /** The requested network is not in the product's `cardConfig.networks`. */
    CARD_NETWORK_NOT_ALLOWED,

    /** A VIRTUAL/SINGLE_USE card was requested but `cardConfig.virtualCardAllowed` is false. */
    CARD_VIRTUAL_NOT_ALLOWED,

    /** Secure details were requested for a card with plastic — its PAN is not re-served here. */
    CARD_SECURE_DETAILS_NOT_SUPPORTED,

    /** Secure details were requested for a BLOCKED / CANCELLED / EXPIRED card. */
    CARD_SECURE_DETAILS_CARD_NOT_LIVE,

    /** The card predates the synthetic-PAN vault, so there is nothing stored to return. */
    CARD_SECURE_DETAILS_NOT_STORED,
}

/**
 * A product-catalog entitlement rule rejected the issue. Distinct from a validation error: the
 * request is well-formed, the *product* forbids it. Surfaces as HTTP 409 with [code].
 */
class CardEntitlementException(val code: CardErrorCode, message: String) : RuntimeException(message)

/**
 * The caller may not read this card's PAN/CVV (wrong form factor, or the card is no longer live).
 * Surfaces as HTTP 403 with [code].
 */
class SecureDetailsForbiddenException(val code: CardErrorCode, message: String) : RuntimeException(message)

/** There is no stored PAN for this card (issued before the vault existed). Surfaces as HTTP 404. */
class SecureDetailsNotStoredException(val code: CardErrorCode, message: String) : RuntimeException(message)
