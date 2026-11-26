// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor

/** A single activation set accompanies the components assembled for a hosted OAuth site. */
internal class ComposedHostedOAuthRuntime(
    override val enabledVendors: Set<Vendor>,
    override val start: HostedOAuthStart,
    override val complete: HostedOAuthComplete,
    override val grants: HostedVendorGrants,
    override val dispatch: HostedMcpDispatch,
) : HostedOAuthRuntime
