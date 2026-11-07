// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.domain.model

import java.util.UUID

/**
 * The rate config is the local cache of a published catalog revision (ADR-0341), so the admin API
 * refuses to edit it: changing terms is a new catalog revision plus an explicit re-pin. The REST
 * layer maps this to 409.
 */
class CatalogSourcedRateException(val configId: UUID) :
    RuntimeException("Rate config $configId is sourced from a published catalog revision and cannot be edited")
