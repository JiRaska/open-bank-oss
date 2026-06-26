// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.psd2.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "psd2_outbox")
class Psd2OutboxEntity : PanacheOutboxEntity()
