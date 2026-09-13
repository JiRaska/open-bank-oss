// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.model

/** A machine-readable reason why a FINREP preview is not a complete regulatory return. */
data class FinrepDataGap(val code: String, val affectedScope: String, val reason: String)
