// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.api.error.UnauthorizedExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Registers the SHARED 401 mapper for this service. Not a service-local mapper (#526 forbids
 * those): the behaviour lives in openbank-libs-runtime and this is only the `@Provider` that opts
 * this service in, the pattern the shared persistence mappers document — `quarkus-security` is
 * `compileOnly` in libs-runtime, so auto-registration there would crash any service without the
 * extension at ArC init (#6240).
 *
 * Opting in matters here specifically: swift-service, sdd-service and interest-service each
 * publish a pact asserting that a money-path debit without a token is refused, and pact-jvm cannot
 * read the bare `Not Authorized` string Quarkus returns by default (#8993).
 */
@Provider
class TransactionUnauthorizedExceptionMapper : UnauthorizedExceptionMapper()
