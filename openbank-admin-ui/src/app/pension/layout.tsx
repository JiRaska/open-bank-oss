// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import OperatorLayout from '@/components/layout/OperatorLayout'

export default function PensionLayout({ children }: { children: ReactNode }) {
  return <OperatorLayout><div className="ob-domain-workbench">{children}</div></OperatorLayout>
}
