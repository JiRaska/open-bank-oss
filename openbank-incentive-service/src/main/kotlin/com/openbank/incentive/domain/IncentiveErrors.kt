// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

class IncentiveNotFound(message: String) : RuntimeException(message)
class IncentiveConflict(message: String) : RuntimeException(message)
class IncentiveValidation(message: String) : RuntimeException(message)
