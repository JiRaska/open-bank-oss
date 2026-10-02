// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.persistence

import com.openbank.libs.persistence.outbox.PanacheOutboxEntityV2
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * Outbox row on the kernel v2 base (ADR-0327): `claimed_at` and `next_attempt_at` come from
 * [PanacheOutboxEntityV2]; the table gains them in `V10__outbox_v2.sql`.
 */
@Entity
@Table(name = "case_outbox")
class CaseOutboxEntity : PanacheOutboxEntityV2()
