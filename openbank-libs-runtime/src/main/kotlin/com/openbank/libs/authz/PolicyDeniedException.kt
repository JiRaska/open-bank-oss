// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import jakarta.ws.rs.ForbiddenException

/**
 * A policy-decision-point deny raised by [AuthorizeInterceptor]. [reason] is for the log and the
 * trace only — `WebApplicationExceptionMapper` answers this subtype with a constant message
 * (see [PolicyDecisionPoint]). Other `ForbiddenException`s keep their own message.
 */
class PolicyDeniedException(val reason: String) : ForbiddenException(reason)
