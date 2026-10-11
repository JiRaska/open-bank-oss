// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { DocsHub } from '@/components/docs/DocsHub'
import { listBpmnSlugs } from '@/lib/docs/bpmn/load'

// Server component: the BPMN process count is read from the manifests on disk (the same listing
// /docs/bpmn renders), so the hub card can never disagree with the page it links to.
export default function DocsPage() {
  return <DocsHub bpmnCount={listBpmnSlugs().length} />
}
