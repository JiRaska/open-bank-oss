// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { DocsHub } from '@/components/docs/DocsHub'
import { loadBpmnContent } from '@/lib/docs/bpmn/load'

export const dynamic = 'force-dynamic'

// Resolve the same published manifests as /docs/bpmn, independently of image rebuilds.
export default async function DocsPage() {
  const { processes } = await loadBpmnContent()
  return <DocsHub bpmnCount={processes.length} />
}
