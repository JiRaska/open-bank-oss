// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.rest

import com.openbank.regwatcher.domain.WatchRunResult
import com.openbank.regwatcher.infrastructure.schedule.WatchRunner
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * Operator trigger for one run. It goes through the same limits as the cron (runs_per_day, the
 * kill switch, tokens_per_run), so it cannot be used to exceed the charter.
 */
@Path("/api/v1/regulatory-watcher")
@Produces(MediaType.APPLICATION_JSON)
class WatcherResource(private val runner: WatchRunner) {
    @POST
    @Path("/run")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun trigger(): WatchRunResult = runner.runOnce("operator")
}
