// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

/**
 * #12115: funding for a placement. An asset money-market deal settles Dr placement / Cr nostro, so
 * booking it commits cash the contra (nostro) account must hold. Which products consume funding is a
 * domain rule — a legacy asset product whose settlement credits the nostro — and which deals count
 * as already committed is the set whose cash is contracted but has not moved: BOOKED and CONFIRMED.
 * A borrowing brings cash in and consumes nothing; a DRAFT or PENDING_APPROVAL deal is not
 * concluded; a SETTLED deal already moved and is inside the ledger's balance.
 */
object Funding {
    /** The products whose settlement takes cash OUT of the contra account (the posting table's Cr nostro leg). */
    val CONSUMING_PRODUCTS: Set<ProductType> = ProductType.entries.filter { it.legacy && it.isAsset }.toSet()

    /** The states whose contracted outflow is still pending against the contra account. */
    val PENDING_STATES: Set<DealState> = setOf(DealState.BOOKED, DealState.CONFIRMED)

    /** True when booking [deal] commits the contra account's cash (and so needs the funding check). */
    fun consumes(deal: Deal): Boolean = deal.kind == DealKind.ACTUAL && deal.product in CONSUMING_PRODUCTS
}
