// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ---------------------------------------------------------------------------
// Business Process Diagrams (BPMN 2.0). Prefer the independently published,
// schema-validated source manifests and fall back to the image-baked files with
// visible stale provenance. Adding/changing a process = editing a YAML source
// manifest, not this page (ADR-0019 / ADR-0029).
// ---------------------------------------------------------------------------

import { loadBpmnContent } from '@/lib/docs/bpmn/load'
import { BpmnView } from '@/components/docs/BpmnView'

export const dynamic = 'force-dynamic'

export default async function BpmnPage() {
  const { processes, contentSource } = await loadBpmnContent()
  return <BpmnView processes={processes} contentSource={contentSource} />
}
