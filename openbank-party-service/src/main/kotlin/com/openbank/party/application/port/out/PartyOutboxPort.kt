// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.party.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface PartyOutboxRepository : OutboxRepository
