// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export const CASE_STATUSES = ['OPEN', 'CONVERGING', 'CONTESTED', 'SYNTHESIZED', 'CLOSED'] as const
export type CaseStatus = (typeof CASE_STATUSES)[number]

export interface CaseStatusPresentation {
  label: string
  detail: string
}

// A case status describes only the collaboration thread. In particular, it does
// not establish delivery of a proposal or a human decision outside the thread.
export function caseStatusPresentation(status: CaseStatus, language: 'cs' | 'en'): CaseStatusPresentation {
  const cs = language === 'cs'
  switch (status) {
    case 'OPEN':
      return cs
        ? { label: 'Sbírají se podklady', detail: 'Specialisté teprve přidávají své pohledy a důkazy.' }
        : { label: 'Gathering inputs', detail: 'Specialists are still adding their views and evidence.' }
    case 'CONVERGING':
      return cs
        ? { label: 'Porovnávají se podklady', detail: 'Koordinátor porovnává příspěvky v jednom vlákně.' }
        : { label: 'Comparing inputs', detail: 'The coordinator is comparing contributions in one thread.' }
    case 'CONTESTED':
      return cs
        ? { label: 'Neshoda zůstává viditelná', detail: 'Příspěvky si odporují; systém ji neskrývá ani nepředstírá shodu.' }
        : { label: 'Dissent remains visible', detail: 'Inputs conflict; the system neither hides it nor pretends there is agreement.' }
    case 'SYNTHESIZED':
      return cs
        ? { label: 'Návrh je ve vlákně', detail: 'Vlákno zaznamenává návrh; stav doručení ani lidského rozhodnutí zde není.' }
        : { label: 'A proposal is in the thread', detail: 'The thread records a proposal; delivery and the human decision are not shown here.' }
    case 'CLOSED':
      return cs
        ? { label: 'Vlákno je uzavřené', detail: 'Koordinační vlákno už nepřijímá další příspěvky.' }
        : { label: 'The thread is closed', detail: 'The coordination thread is no longer taking contributions.' }
  }
}
