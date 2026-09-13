// SPDX-License-Identifier: Apache-2.0

/**
 * @param {Array<{evidence: Array<{observedAt?: string | null, state: string}>}>} components
 */
export function executionEvidenceTotals(components) {
  const componentsWithExecutionEvidence = components.filter(component =>
    component.evidence.some(item => {
      const observed = Date.parse(item.observedAt ?? '')
      return Number.isFinite(observed) && item.state !== 'not-run' && item.state !== 'blocked'
    }),
  ).length

  return {
    componentsWithExecutionEvidence,
    missingEvidence: components.length - componentsWithExecutionEvidence,
  }
}
