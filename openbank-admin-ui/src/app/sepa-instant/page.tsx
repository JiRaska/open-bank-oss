// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { redirect } from 'next/navigation'

export default function SepaInstantRedirect() {
  redirect('/payments?tab=sct-inst')
}
