// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { DevOpsFinding } from '@/app/api/devops/insights/route'

// Short bilingual label for the DORA metric a finding impacts. Shared between the DevOps page
// (compact tag on the finding card) and RemediationReviewDialog (final-review detail line) — kept
// out of both so neither module has to import the other.
export const DORA_METRIC_LABEL: Record<NonNullable<DevOpsFinding['doraMetricImpacted']>, { cs: string; en: string }> = {
  DEPLOYMENT_FREQUENCY: { cs: 'Frekvence nasazení', en: 'Deployment freq.' },
  LEAD_TIME_FOR_CHANGES: { cs: 'Průběžná doba', en: 'Lead time' },
  CHANGE_FAILURE_RATE: { cs: 'Chybovost změn', en: 'Change failure' },
  TIME_TO_RESTORE: { cs: 'Doba obnovy', en: 'Time to restore' },
}
