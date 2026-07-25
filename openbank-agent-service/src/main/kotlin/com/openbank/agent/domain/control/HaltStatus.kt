// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.domain.control

import java.time.Instant

/**
 * A runtime kill-switch halt (ADR-0031 D7): one scope is suspended, with who/why/when.
 * [scope] is an agent id, or the sentinel `*` meaning every agent.
 */
data class HaltStatus(val scope: String, val reason: String, val setBy: String, val setAt: Instant)
