// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "interest_outbox")
class InterestOutboxEntity : PanacheOutboxEntity()
