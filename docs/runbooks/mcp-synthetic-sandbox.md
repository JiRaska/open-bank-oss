# Private MCP synthetic sandbox

The sandbox Argo CD root manages `mcp-synthetic-sandbox` from `main`. The
component establishes a permanent namespace with restricted pod security and
default-deny ingress and egress. Argo CD creates the namespace before applying
the policy. Namespace pruning and deletion require separate confirmation.

The foundation provisions its private CA and a keyless runner service account. It has no workloads or endpoints. Use pull requests for all
changes to the component. The public MCP release has its own delivery,
observability and availability prerequisites.

## Build and pin the browser runner

The `Platform images` workflow builds, scans, signs and attests the image from
`e2e/mcp-harness`. Let the platform GitOps apply create the runner repository
before publication. If the merge-triggered build runs before the repository
exists, dispatch `platform-images.yml` on `main` with `image: mcp-harness` once
the repository is available. The first workload change pins the resulting
digest and removes the corresponding registry bootstrap entry. Use that same
digest for the post-sync run and the periodic read-and-revoke journey.

The runner obtains its app session through the synthetic customer's registered
passkey and the first-party app's S256 authorization code flow. Supply the
enrolled passkey and SCA device credentials through restricted secret mounts.
Retain the WebAuthn assertion counter across runs and serialize jobs that use
the same credential. Supply only sandbox HTTPS origins with exact issuer and
resource parity, and keep the per-run OAuth tokens in memory.

The synthetic connector uses online `openid` with S256 and a fresh DPoP key.
The balance and revoke journey selects only `ACCOUNT_READ_BALANCES`. The
pending proposal journey also selects `ACCOUNT_PROPOSE_PAYMENT` and needs the
bound-proposal flag in the synthetic MCP workload. Keep approval in the bank
app and point its synthetic admission check only at the private fixture.

## Private TLS trust

The `mcp-synthetic-private-ca` Issuer and its signing key are confined to the
synthetic namespace. Verify both Issuers and the root Certificate are
`Ready=True` before adding leaves. The root keeps its key on renewal. Replace
it only with a coordinated rollover of the sandbox trust and leaves.

Use separate leaf keys for HTTPS servers and mTLS clients. Distribute only
the public CA certificate into the dedicated browser and service trust stores.
Keep the signing-key Secret out of every workload mount, including the runner.
The runner uses `mcp-synthetic-runner` with API token automount disabled and
requires no Kubernetes API permissions for the journey.

Use a ClusterIP entry point reachable only from the synthetic runner. Choose
the exact sandbox-labelled HTTPS origin and allow-list suffix together so the
harness accepts it without target overrides. A shared public Gateway listener
must not be used for this test.

## Add and verify the synthetic journey

1. Add digest-pinned candidate services, dedicated databases and cache, and the
   browser runner to this component. Keep the Keycloak realms, bank-owned
   synthetic customer, account, enrolled device and fixture credentials
   isolated from shared customer data. Provide runtime secrets through
   namespace-scoped secret references. Use an approved private issuer and
   match the exact resource, OAuth issuer and TLS identities across the
   runner, Keycloak and MCP gateway.
2. Add NetworkPolicies for the specific runner and service peers on their
   required ports. Include any required resolver access as a separate scoped
   rule. Keep services private and restrict runner access to the sandbox.
   Route the payment approval check to a synthetic admission service inside
   the sandbox with no payment network access.
3. Validate the rendered component and exact image digests in the PR. After
   GitOps synchronization, execute the same versioned browser journey
   against the private sandbox. Record the deployed revision and outcomes for
   passkey and SCA, a scoped balance read, immediate revocation and refresh
   refusal, and the separate proposal appearing as `PENDING` for its owner.
   A payment-approval probe can verify a single request and reload against a
   synthetic admission receipt. Keep scheduled journey coverage marked
   planned until the sandbox run produces evidence.
4. To stop the runner or remove its network access, revert the corresponding
   overlay through GitOps. Preserve the baseline and namespace for the next
   deployment.

Render the foundation locally with
`kubectl kustomize openbank-infra/gitops/components/mcp-synthetic-sandbox`.
The foundation renders a namespace, default-deny NetworkPolicy, two namespace
Issuers, the CA Certificate and the keyless runner service account.

## Render the private ingress

The private ingress publishes one ClusterIP service in the sandbox namespace.
The renderer derives its canonical hostname from the Service DNS name. The
runner reaches MCP, the customer OAuth issuer and the bank app API through
that hostname. The gateway presents a separate client certificate to MCP and
verifies the certificate and Service DNS name on every upstream HTTPS hop.

From the repository root, use a signed, published harness image reference
and the sandbox backend Service names to generate a standalone Kustomization.

```sh
python3 openbank-infra/gitops/components/mcp-synthetic-sandbox/private-ingress/render.py \
  --image "$MCP_RUNNER_IMAGE_BY_DIGEST" \
  --mcp-service "$MCP_SANDBOX_SERVICE" \
  --issuer-workload "$MCP_SANDBOX_KEYCLOAK_DEPLOYMENT" \
  --customer-edge-service "$MCP_SANDBOX_CUSTOMER_EDGE_SERVICE" \
  --customer-realm "$MCP_SANDBOX_CUSTOMER_REALM" \
  "$MCP_PRIVATE_INGRESS_OUTPUT"
kubectl kustomize "$MCP_PRIVATE_INGRESS_OUTPUT"
```

The MCP and Edge pods use `app.kubernetes.io/name` equal to their Service name
and listen on HTTPS port 8443. Their certificates include their individual
Service DNS names. The Keycloak OAuth Service also listens on 8443 and targets
a separate server-auth-only listener on its workload on port 8444. Set MCP's `MCP_EDGE_GATEWAY_IDENTITY` to the canonical
frontend hostname and `MCP_EDGE_CLIENT_ADDRESS_HEADER` to
`x-envoy-external-address`. The runner and Chromium trust the public sandbox CA.
Keep CA signing material in the foundation Secret.

Add the rendered resources through the sandbox GitOps PR once the backend and
runner digests are attested. After synchronization, verify that only the runner
reaches the ClusterIP and that MCP accepts the router certificate. Recycle the
router through GitOps after certificate renewal so Node loads the renewed
leaves. The foundation Kustomization can synchronize separately.

## Render the scheduled runner

Use the same signed harness digest and customer realm as the private ingress.
Select an existing CSI storage class that supports `ReadWriteOncePod`. The
runner accepts the name of a separately provisioned synthetic fixture Secret.

```sh
python3 openbank-infra/gitops/components/mcp-synthetic-sandbox/private-ingress/render-runner.py \
  --image "$MCP_RUNNER_IMAGE_BY_DIGEST" \
  --customer-realm "$MCP_SANDBOX_CUSTOMER_REALM" \
  --client-id "$MCP_SANDBOX_PUBLIC_CLIENT" \
  --fixture-secret "$MCP_SYNTHETIC_FIXTURE_SECRET" \
  --storage-class "$MCP_SANDBOX_STORAGE_CLASS" \
  "$MCP_SANDBOX_RUNNER_OUTPUT"
kubectl kustomize "$MCP_SANDBOX_RUNNER_OUTPUT"
```

Provision `passkey.json` for the registered resident credential, `device.pem`
for the aged synthetic SCA device, and `party-id`, `device-id`,
`read-tool-args.json` with the exact owned `accountId`, and `currency` through
the sandbox's secret workflow. The pod stages owner-only credential copies
in its temporary volume and mounts just the public root certificate from the
ingress server leaf's Secret. It can connect only to the router and cluster DNS.

The CronJob starts suspended and runs the online balance and revoke journey.
Before the first run, initialize the dedicated counter volume as UID 1001
using the harness README's exclusive initialization procedure. Preserve the
counter on every replacement and use the same volume for scheduled and
reviewed manual jobs. A separate exploratory job with a copy of that
passkey would bypass its counter coordination. GitOps requires confirmation
before it removes the counter claim. Resume scheduling in a reviewed GitOps
change after a one-off run has produced sanitized evidence.

For a reviewed, one-off pending-proposal check, render with
`--mode verify-proposal` and supply `propose-args.json` in the same fixture
Secret. Its debtor account must match the owned account in
`read-tool-args.json`. The isolated MCP backend must enable the existing bound
proposal feature. Keep payment destinations outside the sandbox inaccessible.
The runner confirms the app owner sees the exact pending proposal. This uses
the same CronJob identity, suspension, router policy and passkey counter as the
read-and-revoke mode. Keep one definition and restore read-and-revoke for
periodic scheduling.


## Render the Keycloak TLS boundary

The Keycloak component adds two private ClusterIP Services to the isolated
sandbox Keycloak Deployment. The OAuth sidecar serves HTTPS and relays the
customer realm paths over pod loopback to Keycloak's existing HTTP
listener. The router sends browser traffic using the canonical sandbox host.
Named application peers can use the OAuth Service DNS for private discovery.
Keycloak advertises the canonical issuer and trusts forwarding headers only
from the loopback relay.

Only the MCP workload may connect to Keycloak's native read-issuer Service.
That listener requires a verified client certificate. Supply the exact
MCP read client issuing CA in the externally provisioned PKCS12 truststore
Secret under `truststore.p12` and `password`. The customer realm's X.509
client retains its exact workload certificate policy. Keycloak presents a
separate server certificate on each HTTPS listener.

```sh
python3 openbank-infra/gitops/components/mcp-synthetic-sandbox/private-ingress/render-keycloak-tls.py \
  --relay-image "$MCP_RUNNER_IMAGE_BY_DIGEST" \
  --deployment "$MCP_SANDBOX_KEYCLOAK_DEPLOYMENT" \
  --mcp-service "$MCP_SANDBOX_SERVICE" \
  --backchannel-peer "$MCP_SANDBOX_SERVICE" \
  --backchannel-peer "$MCP_SANDBOX_BALANCE_SERVICE" \
  --customer-realm "$MCP_SANDBOX_CUSTOMER_REALM" \
  --trust-secret "$MCP_READ_ISSUER_CLIENT_TRUST_SECRET" \
  "$MCP_SANDBOX_KEYCLOAK_TLS_OUTPUT"
```

Add the generated directory under `components` of the sandbox workload
Kustomization. Select the sandbox Keycloak Deployment whose pod app label
equals its name. Its existing image, database, realm configuration and
outbound TLS to SCA and Delegation remain supplied by the runtime overlay.
Supply each exact discovery peer explicitly. Review the complete render for
other policies that could admit clients to either port. Configure MCP bound
grant acquisition against the native Service and keep customer discovery
on the OAuth Service. On sync, verify exact issuer metadata and a browser
passkey session through the router, direct discovery from named peers, and
native token issuance with the MCP workload certificate. Recycle the
sidecar through GitOps when its leaf renews.

### Optional namespace-qualified peers

All workloads default to the sandbox namespace. For explicitly assigned
workloads elsewhere, render the router with `--mcp-namespace` and
`--customer-edge-namespace`. Set `--mcp-workload` or `--customer-edge-workload`
when a pod's app label differs from its Service name. Render Keycloak with
the same `--mcp-namespace` and the MCP pod label as `--mcp-service`.
Repeat `--backchannel-peer NAMESPACE/APP_LABEL` for exact discovery peers.
A plain label still selects a pod in the sandbox namespace.

The generated `external-policies` Kustomizations carry the other sides of
those connections. Include their policies through the respective owners'
GitOps overlays. Verify each object's namespace after rendering and keep
these policies outside the sandbox namespace transform. Existing policies
are additive, so confirm there is no broader admission on the selected
listeners. The render commands do not add these policies to the foundation.

For the router, each upstream Service's fully qualified DNS name stays its
TLS server name, and the HTTP Host remains the canonical sandbox origin.
The destination certificate must cover that Service DNS, and the router must
trust its issuing CA. An external MCP listener must also explicitly trust the
sandbox router leaf issuer and pin the sandbox router identity. Cross-namespace
policy generation does not change existing TLS trust or create missing HTTPS
listeners. Only use workloads assigned to the synthetic sandbox.


The OAuth frontchannel exposes customer realm paths by default. Supply
`--staff-realm` only for a staff realm hosted on the same isolated server
and explicitly trusted by its corresponding service peers. When using
shared sandbox SCA or Delegation, configure the authority's staff token
endpoint separately against the approved private HTTPS endpoint for those
services' existing staff issuer. Customer issuer discovery stays on the
sandbox OAuth Service.

### Customer Edge HTTPS

The `sandbox-overlays/mcp-customer-edge-tls` Kustomize component is optional and targets the
sandbox owner's Customer Edge Rollout and its stable Service. Provision the
`customer-edge-mcp-sandbox-server-tls` Secret in the workload namespace
with `tls.crt` and `tls.key`. The server leaf must include the exact DNS name
of the stable Service. The router verifies that name and the issuer CA. The
listener uses TLS 1.3 and the existing Customer Edge application JWT checks.

Include this component and the exact sandbox-router ingress policy in the
same reviewed sandbox overlay. The component keeps the stable HTTP route,
canary Service, management probes and existing ingress on their current
ports. It also scopes the legacy same-namespace rule to ports already in use.
Verify the site render for any additional policy that admits port 8443.

### Authority and service TLS

Compose the private Keycloak listener overlay with an isolated Keycloak Deployment. The
`render-keycloak-authority.py` component attaches the Keycloak outbound identity to its MCP
authority transports. Use the staff token Service of the issuer already trusted by SCA and
Delegation. The Deployment needs a dedicated Secret with `identity.p12`, `trust.p12` and
`password`. The identity must be accepted by the SCA private TLS listener. The trust store
must contain the approved server CAs for the staff token endpoint, SCA and Delegation.
Include the separately rendered ingress policies with their destination namespace owners.

Compose the Balance, Delegation and MCP service TLS component, the optional customer OIDC
discovery components for Balance and MCP, and the private Customer Edge HTTPS component with
their sandbox application owners. Use the canonical customer realm issuer for token validation
and the private OAuth endpoint for discovery. Allow the exact outbound and inbound workload
pairs, and verify actual TLS clients see a leaf valid for the destination Service DNS name.

Verify the Keycloak realm's actual discovery issuer and JWKS URI, accepted client identities,
and native listener health from the participating pods before enabling the customer flow.
Run the synthetic read and revoke journey first, then the separate pending-proposal journey
with the synthetic settlement boundary in place.

### Shared staff issuer private token relay

When sandbox SCA and Delegation use the existing staff realm, the customer
Keycloak authority needs that realm's staff token issuer. Render the
site-only staff token component with the canonical staff hostname
and the exact app label of the isolated customer Keycloak workload.

```sh
python3 openbank-infra/gitops/components/mcp-synthetic-sandbox/private-ingress/render-staff-token-tls.py \
  --relay-image "$MCP_RUNNER_IMAGE_BY_DIGEST" \
  --staff-issuer-host "$MCP_STAFF_ISSUER_HOST" \
  --staff-realm "$MCP_STAFF_REALM" \
  --caller-workload "$MCP_SANDBOX_KEYCLOAK_DEPLOYMENT" \
  "$MCP_STAFF_TOKEN_TLS_OUTPUT"
```

The generated component targets the sandbox site's `iam/keycloak` Deployment.
Its ClusterIP Service accepts only the named synthetic Keycloak workload and
only the staff token POST for the scoped Connect and read issuer clients.
The relay passes the canonical HTTPS staff origin to local Keycloak. HTTP,
management, discovery and public ingress stay with the existing deployment.
Include the generated `caller-egress` policy through the customer sandbox
owner's GitOps overlay.

Provision `mcp-sandbox-staff-token-server-tls` in the IAM namespace with
`tls.crt` and `tls.key` for the generated Service DNS name. Mount the public
issuing CA in the customer authority's scoped TLS trust. Configure the
authority's staff token URL with the relay Service DNS and exact staff
realm token path. After GitOps sync confirm the staff token's canonical
issuer and distinct narrow client/role against the existing SCA and
Delegation validators. The relay handles credentials in memory, and
Keycloak still authenticates both registered clients. Roll the relay
through GitOps after renewing its TLS leaf.

The router uses the sandbox CA for each upstream by default. Set
`--mcp-ca-secret`, `--edge-ca-secret` or `--oidc-ca-secret` to replace trust
for the corresponding hop with one approved public CA certificate. Each
Secret lives in the router namespace and provides only `ca.crt` to the pod.
Keep backend DNS names in their leaf certificates and include any necessary
intermediates in the backend's served chain.

The Keycloak TLS render gives the Keycloak pod DNS access on TCP and UDP
port 53 only to the default cluster resolver pod selector. When the site
uses a different resolver, supply the exact `--dns-namespace` and
`--dns-workload` values for its namespace and `k8s-app` pod label.

### Customer Edge private OIDC discovery

Add the optional `sandbox-overlays/mcp-customer-edge-private-oidc` component to the sandbox
Customer Edge overlay alongside the TLS component. In the Edge namespace
provision `customer-edge-mcp-oidc-origins` through the component's ExternalSecret with two
required HTTPS realm URLs. `discovery-url` is the private Keycloak OAuth
Service, and `issuer-url` is the exact canonical issuer signed into the
customer JWT. Provision `customer-edge-mcp-oidc-trust` with only the
public CA certificate under `ca.crt`. The named OIDC trust configuration
applies to inbound token discovery and JWKS retrieval.

Name the Edge workload explicitly as a Keycloak discovery peer through
`--backchannel-peer`. Verify that private discovery advertises the exact
canonical issuer and a reachable private JWKS URL before applying the Edge
component. Apply it only to a sandbox-assigned Edge workload when the
canonical issuer differs from the bank realm. The existing Edge messaging
properties and outbound staff authentication continue to use their own
configuration.

### Activate the private synthetic flow

After the site GitOps overlay has synchronized and each private TLS path has been verified,
set the following values to `true` on the sandbox-assigned workloads. Confirm that the
runner, customer Keycloak realm and MCP resource metadata all use the same HTTPS resource
and canonical customer issuer.

| Workload | Private sandbox flags |
| --- | --- |
| Keycloak | `KC_SPI_AUTHENTICATOR__OPENBANK_MCP_WITHHELD_CODE__CONNECT_V2_ENABLED`, `MCP_READ_GRANT_ENABLED`, `MCP_READ_CONNECTION_BINDING_ENABLED` |
| Delegation | `DELEGATION_MCP_CONNECT_ENABLED` |
| Customer Edge | `MCP_CONNECT_ENABLED` |
| Balance | `BALANCE_MCP_ENABLED`, `BALANCE_MCP_CONNECTION_BINDING_ENABLED` |
| MCP | `MCP_CUSTOMER_ENABLED`, `MCP_CUSTOMER_CONNECTION_BINDING_ENABLED`, `MCP_BOUND_BALANCE_ENABLED` |

Use the scoped staff client Secret references for the Keycloak SCA and read issuer clients
and for the Balance grant reader. Share only the read nonce key between the customer issuer
and MCP. Keep the issuer proof key separate. Set the issuer's exact customer issuer,
resource, staff token URL and Delegation origin, and the MCP deployment's exact native
issuer, Balance HTTPS origin and named client TLS configuration. Enable
`MCP_CUSTOMER_BOUND_PROPOSAL_ENABLED` only for the separate synthetic proposal run after
the synthetic settlement boundary has been confirmed. Record the deployed revision, both
journey outcomes and the revoked connection check.

## Enable the synthetic native passkey age policy

The Party, Customer Edge and Admin UI workloads read the optional
`mcp-sandbox-passkey-age-policy` ConfigMap in their own namespaces. The
application default stays off when the object or its `enabled` key is absent.
Provision these ConfigMaps only through the shared sandbox's site GitOps
configuration. Set `data.enabled` to the string `"true"` for each stage below.

1. Confirm the new Party, Customer Edge and Admin UI images are healthy. Confirm
   Party's Flyway V29 migration and the matching Party authorization bundle.
2. Provision the ConfigMap in `party`. Roll Party through its
   GitOps pod template and confirm the effective-policy API returns no exception
   for a synthetic party with no policy.
3. Provision it in `customer-edge`. Roll Customer Edge and verify the 72-hour
   default still applies when no valid per-party policy is available.
4. Provision it in `admin-ui`. Change the Admin UI pod template on `main` under
   `openbank-infra/gitops/components/admin-ui`. The Admin UI deployment workflow
   updates `deploy/sandbox:pins/admin-ui`, which the live Argo Application tracks.
   Wait for its pin and rollout before exercising the staff form.
5. Set an audited, short-lived policy for one active synthetic individual and
   verify the app journey. The policy applies only to the request's bound party.
   A native passkey and the separate SCA confirmation remain required.

ConfigMap values supplied through environment variables are read at pod start.
After provisioning, changing or removing a map, wait for that GitOps change
to sync before changing the workload pod template. Verify every ready replica
uses the updated template.
To restore the 72-hour floor, turn off and roll Customer Edge first, then Admin
UI and Party.
