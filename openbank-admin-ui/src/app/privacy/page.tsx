// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Static, pre-login legal notice — same class as the /auth/* screens
// (layout-shell.guard EXEMPT). GDPR Art. 13 requires this notice to be
// reachable WITHOUT an account (proxy.ts carries the bypass), since the data
// subjects it describes are the operators who are about to log in. Content
// lives in the client component below so it can switch language via
// useLanguage() while this file keeps the server-rendered <title>.

import PrivacyContent from "@/components/privacy/PrivacyContent"

export const metadata = {
  title: "Privacy notice / Ochrana osobních údajů — OpenBank Admin",
}

export default function PrivacyPage() {
  return <PrivacyContent />
}
