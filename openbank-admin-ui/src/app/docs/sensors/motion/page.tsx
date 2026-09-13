// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One family of the /docs/sensors catalogue. Layout and fields come from the
// shared SensorFamilyView so the five subpages cannot drift apart.

import { cookies } from 'next/headers'
import { SensorFamilyView } from '@/components/docs/SensorFamilyView'

export const dynamic = 'force-dynamic'

export default async function SensorsPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  return <SensorFamilyView family="motion" lang={lang} />
}
