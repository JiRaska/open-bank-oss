# Dedicated ruleset evidence App

Refs #13448 and #13797. The default-branch collector uses a dedicated GitHub App;
its credentials are not shared with GitOps or exposed as repository-wide secrets.
The collector requests a short-lived token for this repository only, with
`Administration:write` and implicit `Metadata:read`. GitHub requires ruleset-writer
access to return `bypass_actors`; the collector performs GETs and then revokes its
own token before publishing data. No ruleset bypass role is needed or permitted.

## Owner setup

1. Sign in as the repository owner and open the
   [preconfigured registration form](https://github.com/settings/apps/new?name=openbank-ruleset-evidence&url=https%3A%2F%2Fgithub.com%2FJiRaska%2Fopen-bank-oss&public=false&webhook_active=false&request_oauth_on_install=false&administration=write).
   Use a unique name if the suggested name is unavailable. Confirm **Only on this
   account**, webhook **Active unchecked**, no callback/setup URLs, no OAuth
   authorization, no subscribed events. Under Repository permissions, set only
   **Administration: Read and write**; **Metadata: Read-only** is implicit. Leave
   all other repository, organization and account permissions at **No access**.
2. Create the App. Under **Install App**, select the repository owner, then
   **Only select repositories → open-bank-oss**. Do not add the App to any ruleset
   bypass list and do not change the shared GitOps App.
3. The repository environment `ruleset-evidence` must use **Selected branches and
   tags**, with exactly one **Branch** rule named `main`, no tag rules or wildcards.
   This is a credential boundary, not a manual approval on every CI run. Inspect
   the actual branch rules, not just the environment's existence. `workflow_run`
   executes on the default branch; ordinary PR refs do not match this environment.
4. Copy the numeric **App ID** (not Client ID). Generate a private key under
   **Private keys**. Store these only as **environment secrets** in
   [ruleset-evidence settings](https://github.com/JiRaska/open-bank-oss/settings/environments):
   - `RULESET_EVIDENCE_APP_ID`: the numeric App ID.
   - `RULESET_EVIDENCE_APP_PRIVATE_KEY`: the entire downloaded PEM, including header,
     footer and newlines.
   Do not put the PEM in a PR, chat, tracked file, repository secret or organization
   secret. Store its local copy outside the checkout.

   Alternatively, upload from a local terminal without putting the PEM on the
   command line (substitute the local filename):

   ```sh
   gh secret set RULESET_EVIDENCE_APP_ID --repo JiRaska/open-bank-oss --env ruleset-evidence
   gh secret set RULESET_EVIDENCE_APP_PRIVATE_KEY --repo JiRaska/open-bank-oss --env ruleset-evidence < /absolute/path/outside-repository/app.private-key.pem
   ```

## Rollout and acceptance

Merge the collector credential change through normal review and all required
checks first. A change that only exists in the consumer PR cannot configure a
`workflow_run` producer on main. Keep #13797 open without auto-merge until positive
live verification is complete. Do not widen environment access to test a PR.

Run the **entire** consumer CI, not just failed jobs, once the default-branch
collector and credentials are ready. Verify its current target run and attempt,
then require successful token mint, actual GET reads, successful explicit
revocation before upload, and a JSON snapshot containing `bypass_actors`. Verify
producer workflow identity/event/conclusion and both producer/target run-attempt
bindings. Require the full CI and the gate JSON to finish successfully. A verified
empty actor list may pass; absent credentials, fields or trusted evidence must
remain blocking `NOT_EVALUATED`, with no PAT fallback or fabricated status.

The existing collector tests cover missing-field/read-denial cases and failed
revocation. The workflow boundary test requires the dedicated environment,
credential names and exact token scope. These tests do not prove the installed
App's grant or the live environment policy; check those separately.

Rollback: revert the collector credential change through a PR and remove the new
installation/credentials only when no live collector needs them. Reverting to an
underprivileged App restores the blocking failure; never substitute a passing
verdict. Never grant the shared App broader access as a rollback shortcut.

References: [registration parameters](https://docs.github.com/en/apps/sharing-github-apps/registering-a-github-app-using-url-parameters),
[environment restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments),
[workflow_run semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run).
