// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.application

/** Low-cardinality operational evidence for completed incentive lifecycle transitions. */
interface IncentiveMetrics {
    fun offerPublished()
}
