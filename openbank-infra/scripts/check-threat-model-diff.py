#!/usr/bin/env python3
"""Diff-aware threat-model gate for money-path services (ADR-0030 D2, issue #265).

The coverage gate (check-threat-models.py) proves a threat model EXISTS; this one
proves it is UPDATED when a PR moves a money-path service's TRUST BOUNDARIES. A
trust-boundary change for service S is any changed file matching:

  - S/src/main/**/infrastructure/rest/**           (new/changed inbound endpoints)
  - S/src/main/**/infrastructure/client/**          (new/changed outbound edge)
  - S/src/main/**/adapter{,s}/**.kt|.java with an HTTP/gRPC client hint in the file
  - S/src/main/resources/application.yaml with (oidc|auth|authz|opa|http|kafka|
    security) keys in the diff hunks                 (listeners / authn / transport)
  - openbank-infra/gitops/components/**: S's network-policies.yaml (any change —
    a NetworkPolicy is reach by construction), or its Deployment/Rollout manifest
    when the DIFF HUNKS touch a boundary key (ports, serviceAccountName,
    securityContext, auth-shaped env) rather than generated churn — a
    policy-checksum restamp or an image tag is not a boundary change (#3431)
    Attribution of a gitops document to S is by what it REFERENCES, not by a name token in
    its path: a document is S's when it lives in S's namespace (and is not about another
    identified service's workload) or names S's workload, and it concerns S when one of its
    CHANGED lines names S's namespace, Service DNS/workload, or principal — all matched as
    exact identifiers, so `pension` never matches `pension-fund` or `ledger-pension-co`.
    Services with no resolvable workload keep the legacy token match (see gitops_hit).

When any of those change for a money-path service (rules.yaml:
money_path_services, parsed by check-threat-models.py's parser) and
docs/threat-models/<service>.md is NOT part of the same diff, emit a finding.

stdlib-only; shells out to `git` unless --changed-files supplies the list.

Usage:
    check-threat-model-diff.py [--base <ref>] [--changed-files <path>] [--enforce]

    --base <ref>            PR base; changed files = `git diff --name-only
                            <ref>...HEAD` (3-dot = the actual squash delta).
                            Default: origin/main.
    --changed-files <path>  newline-separated changed-file list — bypasses git
                            entirely. Hunks cannot be inspected this way, so the
                            manifest rule stays conservative and flags on the path.
    --head <ref>            head ref/sha (default HEAD); lets a measurement replay a
                            past commit with `--base <sha>^ --head <sha>`.
    --self-test             run the classifier against known-positive/negative diffs.

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""
from __future__ import annotations

import argparse
import difflib
import functools
import importlib.util
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]

REST_RE = re.compile(r"^(openbank-[^/]+)/src/main/.*/infrastructure/rest/")
CLIENT_RE = re.compile(r"^(openbank-[^/]+)/src/main/.*/infrastructure/client/")
ADAPTER_RE = re.compile(r"^(openbank-[^/]+)/src/main/.*/adapters?/.*\.(?:kt|java)$")
APP_YAML_RE = re.compile(r"^(openbank-[^/]+)/src/main/resources/application\.yaml$")
GITOPS_RE = re.compile(r"^openbank-infra/gitops/components/([^/]+)/([^/]+\.ya?ml)$")

# An adapter file is an outbound trust edge only if it actually speaks HTTP/gRPC —
# a persistence adapter is not one (same hint style as check-api-contract.py).
CLIENT_HINT = re.compile(r"@RegisterRestClient|RestClient\b|HttpClient|WebTarget|[Gg]rpc")

# application.yaml keys that move a trust boundary: authn/authz, policy engine,
# HTTP listeners, Kafka transport, security.* — matched against diff hunk lines.
SECURITY_KEY = re.compile(r"\b(oidc|authz?|opa|http|kafka|security)[\w.-]*\s*:", re.IGNORECASE)

# Only these manifest kinds define ingress/egress or the runtime env of a service.
GITOPS_KIND = re.compile(r"^kind:\s*(NetworkPolicy|Deployment|Rollout)\s*$", re.MULTILINE)

# Generated lines in a Deployment/Rollout that are never a trust-boundary change:
# the OPA pod-roll annotation (restamped fleet-wide by any rules.yaml edit) and the
# image reference (rewritten by auto-deploy on every push). Matched BEFORE the
# boundary keys below, since an image line contains ':' and digests look like config.
MANIFEST_GENERATED = re.compile(
    r"^(openbank\.tech/policy-checksum|image|imagePullPolicy)\s*:",
    re.IGNORECASE,
)

# Keys in a Deployment/Rollout/NetworkPolicy hunk that CAN move a trust boundary:
# who can reach the pod, what identity it runs as, what it may talk to.
MANIFEST_BOUNDARY_KEY = re.compile(
    r"\b("
    r"ports?|containerPort|hostPort|nodePort|targetPort|"          # listeners
    r"ingress|egress|policyTypes|podSelector|namespaceSelector|"    # NetworkPolicy reach
    r"serviceAccountName|automountServiceAccountToken|"            # workload identity
    r"securityContext|runAsUser|runAsNonRoot|privileged|"          # privilege
    r"capabilities|hostNetwork|hostPID|hostIPC|"
    r"[A-Z_]*(OIDC|AUTHZ?|OPA|TLS|MTLS|ISSUER|TOKEN|SECRET|KEYCLOAK)[A-Z_]*"  # env names
    r")\b",
    re.IGNORECASE,
)


def load_money_path_services() -> list[str]:
    """Reuse check-threat-models.py's rules.yaml parser — one parser, one truth."""
    path = pathlib.Path(__file__).resolve().parent / "check-threat-models.py"
    spec = importlib.util.spec_from_file_location("check_threat_models", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.money_path_services()


def changed_files(base: str, head: str = "HEAD") -> list[str]:
    res = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],  # 3-dot: the squash delta
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 and "no merge base" in res.stderr:
        # Shallow CI checkout (fetch-depth 1 + a depth-1 fetch of the PR base sha) has
        # no merge base. In PR CI, HEAD is the fresh refs/pull/N/merge commit, so a
        # 2-dot diff against the base sha IS the squash delta there (same reason
        # check-api-contract.py diffs 2-dot) — fall back to it.
        res = subprocess.run(
            ["git", "diff", "--name-only", base, head],
            capture_output=True, text=True, cwd=REPO,
        )
    if res.returncode != 0:
        print(f"::error::threat-model-diff gate: git diff against {base} failed: {res.stderr.strip()}")
        sys.exit(1)
    return [line for line in res.stdout.splitlines() if line.strip()]


def gitops_tokens(service: str) -> set[str]:
    """Name tokens that identify S in gitops paths (component dir or filename).

    openbank-ledger-service -> {ledger-service, ledger}; openbank-sepa-payment ->
    {sepa-payment, sepa, payment} (the shared `payments` component hosts it).
    """
    short = service.removeprefix("openbank-")
    toks = {short}
    if short.endswith("-service"):
        toks.add(short[: -len("-service")])
    toks.update(t for t in short.split("-") if t != "service" and len(t) > 2)
    return toks


def token_in(tokens: set[str], text: str) -> bool:
    return any(
        re.search(rf"(^|[^a-z0-9]){re.escape(t)}s?($|[^a-z0-9])", text) for t in tokens
    )


def changed_body_lines(diff_text: str) -> list[str]:
    """The added/removed content lines of a unified diff, comments and headers dropped."""
    out: list[str] = []
    for line in diff_text.splitlines():
        if line.startswith(("+++", "---")) or line[:1] not in "+-":
            continue
        body = line[1:].strip()
        if body and not body.startswith("#"):
            out.append(body)
    return out


def hunk_moves_boundary(diff_text: str) -> bool:
    """True if a Deployment/Rollout diff changes something that can move a trust boundary.

    The manifest FILENAME is not evidence. A single `rules.yaml` edit restamps the
    `openbank.tech/policy-checksum` pod-roll annotation on ~29 service manifests at once, and
    auto-deploy rewrites an image tag on every push — neither opens a port, grants an identity
    or changes who may reach the pod. Treating those as boundary changes lit up the whole
    money-path fleet on 7 of 60 commits (issue #3431) and is exactly the generated churn that
    teaches people to ignore a gate.

    So: drop the known-generated lines first, then require a key that actually describes the
    boundary. Mirrors what this script already does for application.yaml — the gitops rule was
    the one place trusting the path instead of the hunk.
    """
    for body in changed_body_lines(diff_text):
        if MANIFEST_GENERATED.search(body):
            continue
        if MANIFEST_BOUNDARY_KEY.search(body):
            return True
    return False


def split_yaml_documents(text: str) -> list[str]:
    """Split a multi-document gitops manifest on its `---` document separators."""
    docs: list[list[str]] = [[]]
    for line in text.splitlines():
        if line.strip() == "---":
            docs.append([])
            continue
        docs[-1].append(line)
    return ["\n".join(d) for d in docs]


# Kinds whose document is itself a trust boundary (NetworkPolicy: reach by construction;
# Deployment/Rollout: gated by hunk_moves_boundary). Matches GITOPS_KIND above.
BOUNDARY_DOC_KIND = re.compile(r"^kind:\s*(NetworkPolicy|Deployment|Rollout)\s*$", re.MULTILINE)


def boundary_docs_text(full_text: str) -> str:
    """The boundary-relevant YAML documents in a (possibly multi-document) manifest file,
    concatenated -- never the whole file.

    A component manifest here routinely bundles a Deployment with a Service, a ServiceAccount,
    and a PodMonitor/ServiceMonitor as separate `---`-separated documents in ONE file (see
    billing-service.yaml, document-service-service.yaml, statement-service.yaml). Without this,
    hunk_moves_boundary sees the WHOLE FILE'S diff text, so a deleted PodMonitor's
    `podMetricsEndpoints: - port: management` matches the `ports?` boundary key exactly as if it
    were the Deployment's own `containerPort` -- flagging a scrape-config removal as a
    trust-boundary move on the workload it shares a file with (#9071: the PodMonitor-dedup PR
    tripped this on billing-service.yaml with no Deployment change at all).
    """
    return "\n---\n".join(
        doc for doc in split_yaml_documents(full_text) if BOUNDARY_DOC_KIND.search(doc)
    )


def documents_naming(service: str, full_text: str) -> str:
    """The YAML documents in a manifest whose own `metadata.name` names this service.

    One `network-policies.yaml` holds a policy per service in the namespace, and the generator
    rewrites the whole file whenever a service is added — so adding card-processing's policy made
    the caller demand a threat-model update from sepa-payment and domestic-payment, neither of
    whose documents changed by a byte (#8809).

    The match is `gitops_tokens` + `token_in`, the same loose pair the rest of this script uses,
    applied to `metadata.name` only. Loose on purpose and NOT made exact here: it over-reports to
    same-token neighbours (openbank-domestic-payment carries the token `payment`, which matches a
    document named `sepa-payment-...`), which is the safe direction. What it removes is the case
    where NO document naming the service changed at all.

    Returns "" when nothing names the service, which the caller reads as "not this service's
    change". A file with no `metadata.name` at all yields "" too, and the caller falls back rather
    than treating that as a clean bill — see own_document_diff.
    """
    tokens = gitops_tokens(service)
    named = []
    for doc in split_yaml_documents(full_text):
        m = re.search(r"^\s*name:\s*(\S+)", doc, re.MULTILINE)
        if m and token_in(tokens, m.group(1)):
            named.append(doc)
    return "\n---\n".join(named)


def own_document_diff(service: str, rel: str, base: str | None, head: str) -> str | None:
    """A synthetic diff of only the documents in `rel` that name `service`.

    Built by differencing the service's own documents at base and at head rather than by filtering
    the file's real diff: a hunk in a shared manifest carries no marker saying which document it
    belongs to, so filtering hunk text cannot attribute them.

    Returns None — "cannot tell, do not narrow" — when either side is unreadable, which keeps the
    caller on its existing, more conservative path. Returns "" when the service's own documents are
    byte-identical across the change, which is the whole point: a neighbour's rewrite stops being
    this service's finding.
    """
    if base is None:
        return None
    before = read_at_ref(base, rel)
    after = read_at_ref(head, rel)
    if before is None or after is None:
        return None
    own_before = documents_naming(service, before)
    own_after = documents_naming(service, after)
    if not own_before and not own_after:
        # Nothing in this file names the service at either end. That is not evidence the change is
        # innocent — it is evidence this narrowing does not apply — so hand back None.
        return None
    if own_before == own_after:
        return ""
    return "\n".join(
        difflib.unified_diff(
            own_before.splitlines(), own_after.splitlines(),
            fromfile=f"a/{rel}", tofile=f"b/{rel}", lineterm="",
        )
    )


def file_diff(base: str | None, head: str, rel: str) -> str | None:
    """Unified diff of one path, or None when it cannot be inspected."""
    if base is None:
        return None
    res = subprocess.run(
        ["git", "diff", f"{base}...{head}", "--", rel],
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 or not res.stdout:
        return None
    return res.stdout


def yaml_security_keys_changed(base: str | None, head: str, rel: str) -> bool:
    """True if the application.yaml diff touches a trust-boundary key.

    Without git (--changed-files synthetic list) the hunks cannot be inspected —
    be conservative and treat the touch as boundary-relevant (advisory gate).
    """
    diff_text = file_diff(base, head, rel)
    if diff_text is None:
        return True  # can't inspect — stay conservative
    return any(SECURITY_KEY.search(b) for b in changed_body_lines(diff_text))


def adapter_is_client(rel: str) -> bool:
    path = REPO / rel
    if not path.is_file():
        return False  # deleted / synthetic — shrinking an edge is reviewed elsewhere
    try:
        return bool(CLIENT_HINT.search(path.read_text(encoding="utf-8", errors="replace")))
    except OSError:
        return False


_PREFETCHED: dict[tuple[str, str], str | None] = {}


def prefetch(ref: str, rels: list[str]) -> None:
    """Load many paths at `ref` with one git process; read_at_ref answers from it."""
    for rel, text in zip(rels, _cat_files_raw(ref, rels), strict=True):
        _PREFETCHED[(ref, rel)] = text


@functools.cache
def read_at_ref(ref: str, rel: str) -> str | None:
    """Content of `rel` at `ref`, or None when it cannot be read (path did not exist there,
    or `ref` cannot be resolved)."""
    if (ref, rel) in _PREFETCHED:
        return _PREFETCHED[(ref, rel)]
    res = subprocess.run(
        ["git", "show", f"{ref}:{rel}"], capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0:
        return None
    return res.stdout


def legacy_gitops_hit(
    service: str, comp: str, fname: str, base: str | None = None, head: str = "HEAD",
) -> str | None:
    """NAME-TOKEN attribution — the fallback when S has no resolvable workload identity.

    Reason string if this gitops file is S's NetworkPolicy or Deployment/Rollout.

    A NetworkPolicy is a boundary by construction — its entire content is reach — so any
    change to one counts. A Deployment/Rollout is not: see hunk_moves_boundary.
    """
    tokens = gitops_tokens(service)
    if not (token_in(tokens, comp) or token_in(tokens, fname)):
        return None
    rel = f"openbank-infra/gitops/components/{comp}/{fname}"
    if fname == "network-policies.yaml":
        # A NetworkPolicy is a boundary by construction, so ANY change to this service's own
        # policy counts — there is no hunk filter here, unlike the Deployment branch below.
        #
        # What there IS, is the shared-file problem: one `network-policies.yaml` holds a policy per
        # service in the namespace, and the generator rewrites it whenever a service is added. So
        # adding card-processing's policy made this gate demand a threat-model update from
        # sepa-payment and domestic-payment, neither of whose documents changed by a byte
        # (#8809). Narrow to the documents whose own `metadata.name` names this service and ask
        # whether THOSE changed.
        #
        # This stays correct in the direction that matters: a rule added to sepa-payment's own
        # policy to admit a new caller changes sepa-payment's document, so it is still a finding
        # for sepa-payment. Only a neighbour's untouched policy stops being one.
        #
        # It does NOT make the attribution exact, and must not be read as if it did. `gitops_tokens`
        # is deliberately loose — openbank-domestic-payment carries the token `payment`, which
        # matches a document named `sepa-payment-...` — so a change to one payments service's policy
        # still reports for its same-token neighbours. Measured: sabotaging a port in sepa-payment's
        # own policy flags both sepa-payment and domestic-payment. That over-reporting is the safe
        # direction and is left alone here; what this narrowing removes is the case where NO document
        # naming the service changed at all.
        own_diff = own_document_diff(service, rel, base, head)
        if own_diff is not None and not own_diff.strip():
            return None
        return f"{rel} (ingress/egress)"
    path = REPO / rel
    if path.is_file():
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return None
        if GITOPS_KIND.search(text) and token_in(tokens, text):
            if base is None:
                # Cannot inspect hunks (synthetic --changed-files list): stay conservative,
                # same contract as yaml_security_keys_changed.
                return f"{rel} (Deployment/Rollout)"
            base_text = read_at_ref(base, rel)
            if base_text is None:
                # New file, or base ref cannot be read -- nothing to scope the diff against,
                # stay conservative rather than assume it is boundary-irrelevant.
                return f"{rel} (Deployment/Rollout)"
            # Scope the diff to just the Deployment/Rollout/NetworkPolicy documents, not the
            # whole file -- see boundary_docs_text. A component manifest here routinely bundles
            # those with a Service/PodMonitor/ServiceMonitor as separate `---` documents, and a
            # hunk in one of THOSE must never be read as a change to another.
            base_scoped = boundary_docs_text(base_text)
            head_scoped = boundary_docs_text(text)
            if base_scoped == head_scoped:
                return None
            scoped_diff = "\n".join(
                difflib.unified_diff(base_scoped.splitlines(), head_scoped.splitlines(), lineterm=""),
            )
            if not hunk_moves_boundary(scoped_diff):
                return None
            # A shared manifest carries many services. Narrow to this one's own documents and read
            # the hunks again — see documents_naming for what that prevents.
            own_diff = own_document_diff(service, rel, base, head)
            if own_diff is not None and not hunk_moves_boundary(own_diff):
                return None
            return f"{rel} (Deployment/Rollout)"
    return None


# ---------------------------------------------------------------------------------------------
# Reference-based attribution (#12445 follow-up).
#
# The name-token match above is a PREFIX match in practice: `pension` (a token of
# openbank-pension-service) matches the component `pension-fund` and `ledger-pension-co`, so
# every pension-fund or pension-company-ledger manifest demanded pension-service's threat model.
# #12445 fixed that by EXCLUDING directories, which was rightly closed as a gate bypass: a
# pension-fund NetworkPolicy that starts admitting the `pension` namespace IS a pension boundary,
# and an exclusion list cannot see that.
#
# So attribution is by what the changed DOCUMENT says, not by what the path is called:
#   - OWNED: the document lives in a namespace S's workload runs in, and does not name a
#     different identified service's workload as its subject; or it names S's workload exactly
#     (metadata.name `<workload>` / `<workload>-…`, `app.kubernetes.io/name: <workload>`);
#   - REFERENCED: the document names S's namespace (`namespace:`, `kubernetes.io/metadata.name:`,
#     `<x>.<ns>.svc`), S's Service DNS / workload name as an exact identifier, or S's principal
#     (`service-account-openbank-<s>`). Identifiers are matched exactly — `pension` never
#     matches `pension-fund`, `ledger-pension-co`, or `service-account-openbank-pension-fund`.
# A NetworkPolicy document that is owned by or references S counts on any change (reach by
# construction). A Deployment/Rollout counts when owned and its hunks move a boundary, or when a
# CHANGED line references S (a new edge into S).
# Services with no resolvable workload fall back to legacy_gitops_hit, never to silence.
# ---------------------------------------------------------------------------------------------

COMPONENTS_REL = "openbank-infra/gitops/components"
_IDENT = r"[a-z0-9-]"


def _git(*args: str) -> str | None:
    res = subprocess.run(["git", *args], capture_output=True, text=True, cwd=REPO, check=False)
    return res.stdout if res.returncode == 0 else None


def doc_meta(doc: str) -> tuple[str | None, str | None, str | None]:
    """(kind, metadata.name, metadata.namespace) of one YAML document, stdlib-only."""
    kind = re.search(r"^kind:\s*(\S+)", doc, re.MULTILINE)
    name = ns = None
    in_md = False
    indent = None
    for line in doc.splitlines():
        if not in_md:
            in_md = line.rstrip() == "metadata:"
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cur = len(line) - len(line.lstrip())
        if cur == 0:
            break
        if indent is None:
            indent = cur
        if cur != indent:
            continue
        m = re.match(r"\s*(name|namespace):\s*['\"]?([^'\"\s]+)", line)
        if m and m.group(1) == "name":
            name = m.group(2)
        elif m:
            ns = m.group(2)
    return (kind.group(1) if kind else None), name, ns


_WORKLOADS_CACHE: dict[str, list[tuple[str, str, str]]] = {}


def _cat_files(ref: str, rels: list[str]) -> list[str]:
    return [t or "" for t in _cat_files_raw(ref, rels)]


def _cat_files_raw(ref: str, rels: list[str]) -> list[str | None]:
    """Contents of many paths at `ref` in ONE `git cat-file --batch` (one process, not N);
    None for a path missing at `ref`."""
    if not rels:
        return []
    req = "".join(f"{ref}:{r}\n" for r in rels).encode()
    res = subprocess.run(["git", "cat-file", "--batch"], input=req, capture_output=True, cwd=REPO, check=False)
    data, out, i = res.stdout, [], 0
    for _ in rels:
        nl = data.index(b"\n", i)
        header = data[i:nl].split()
        i = nl + 1
        if len(header) < 3 or header[1] == b"missing":
            out.append(None)
            continue
        size = int(header[2])
        out.append(data[i:i + size].decode("utf-8", errors="replace"))
        i += size + 1
    return out


def workloads_at(ref: str | None) -> list[tuple[str, str, str]]:
    """Every Deployment/Rollout (name, namespace, component dir) under gitops at `ref`
    (working tree when ref is None)."""
    key = ref or "<worktree>"
    if key in _WORKLOADS_CACHE:
        return _WORKLOADS_CACHE[key]
    out: list[tuple[str, str, str]] = []
    if ref is None:
        files = sorted((REPO / COMPONENTS_REL).glob("*/*.y*ml"))
        items = [(f"{COMPONENTS_REL}/{f.parent.name}/{f.name}",
                  f.read_text(encoding="utf-8", errors="replace")) for f in files]
    else:
        listing = _git("grep", "-l", "-E", r"^kind:[[:space:]]*(Deployment|Rollout)[[:space:]]*$", ref, "--",
                       f"{COMPONENTS_REL}/*/*.yaml", f"{COMPONENTS_REL}/*/*.yml") or ""
        rels = [line.split(":", 1)[1] if line.startswith(f"{ref}:") else line
                for line in listing.splitlines()]
        items = list(zip(rels, _cat_files(ref, rels), strict=True))
    for rel, text in items:
        comp = rel.split("/")[3]
        for doc in split_yaml_documents(text):
            kind, name, ns = doc_meta(doc)
            if kind in ("Deployment", "Rollout") and name:
                out.append((name, ns or "", comp))
    _WORKLOADS_CACHE[key] = out
    return out


def service_identity(service: str, refs: list[str | None]) -> dict | None:
    """S's deployed identity: workload names, namespaces, components, principals.

    Resolved from the manifests themselves at base and head (union), so a service deployed or
    moved by the PR under review is still recognised. None when no workload matches — the
    caller then falls back to the legacy token match rather than attributing nothing.
    """
    short = service.removeprefix("openbank-")
    stem = short.removesuffix("-service")
    cands = {short, stem, f"{stem}-service"}
    names: set[str] = set()
    nss: set[str] = set()
    comps: set[str] = set()
    for ref in refs:
        for name, ns, comp in workloads_at(ref):
            if name in cands:
                names.add(name)
                if ns:
                    nss.add(ns)
                comps.add(comp)
    if not names:
        return None
    principals = {f"service-account-{service}", f"service-account-openbank-{short}",
                  f"service-account-openbank-{stem}"}
    return {"names": names, "namespaces": nss, "components": comps, "principals": principals}


def _exact(ident: str) -> re.Pattern[str]:
    return re.compile(rf"(?<!{_IDENT}){re.escape(ident)}(?!{_IDENT})")


def _strip_comments(text: str) -> str:
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))


def references(ident: dict, text: str) -> list[str]:
    """What in `text` points at S — exact identifiers only, comments ignored."""
    body = _strip_comments(text)
    hits: list[str] = []
    for ns in ident["namespaces"]:
        pat = re.compile(
            rf"(?:namespace:\s*['\"]?|kubernetes\.io/metadata\.name:\s*['\"]?|\.){re.escape(ns)}"
            rf"(?!{_IDENT})(?:['\"]|\.svc|\s|$)", re.MULTILINE)
        if pat.search(body):
            hits.append(f"namespace {ns}")
    for name in ident["names"]:
        if _exact(name).search(body):
            hits.append(f"workload {name}")
    for p in ident["principals"]:
        if _exact(p).search(body):
            hits.append(f"principal {p}")
    return hits


def owns(ident: dict, doc: str, other_names: set[str]) -> bool:
    """S owns a document in its own namespace unless the document is about another
    identified service's workload; or anywhere when it names S's workload as its subject."""
    _kind, name, ns = doc_meta(doc)
    subj = re.findall(r"app\.kubernetes\.io/name:\s*['\"]?([a-z0-9-]+)", doc)
    named = {n for n in ([name] if name else []) + subj}

    def is_of(n: str, wl: str) -> bool:
        return n == wl or n.startswith(f"{wl}-")

    if any(is_of(n, wl) for n in named for wl in ident["names"]):
        return True
    if ns and ns in ident["namespaces"]:
        # longest-match: a doc named for ANOTHER identified workload is that one's, not S's
        others = {wl for wl in other_names if wl not in ident["names"]}
        return not any(is_of(n, wl) for n in named for wl in others)
    return False


@functools.cache
def _docs_by_key(text: str | None) -> dict[tuple, str]:
    out: dict[tuple, str] = {}
    if not text:
        return out
    for i, doc in enumerate(split_yaml_documents(text)):
        kind, name, ns = doc_meta(doc)
        if not kind:
            continue
        out[(kind, name or f"#{i}", ns)] = doc
    return out


@functools.cache
def changed_boundary_docs(base: str, head: str, rel: str) -> tuple:
    """(key, old, new, diff, changed-lines) for every boundary document of `rel` that differs
    between base and head. Computed once per file, shared by every service's check."""
    b, a = _docs_by_key(read_at_ref(base, rel)), _docs_by_key(read_at_ref(head, rel))
    out = []
    for key in sorted(set(b) | set(a), key=str):
        if key[0] not in ("NetworkPolicy", "Deployment", "Rollout"):
            continue
        old, new = b.get(key, ""), a.get(key, "")
        if old == new:
            continue
        diff = "\n".join(difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm=""))
        out.append((key, old, new, diff, "\n".join(changed_body_lines(diff))))
    return tuple(out)


def reference_hit(
    ident: dict, other_names: set[str], rel: str, docs: tuple,
) -> str | None:
    """Reason string if a changed boundary document in `rel` is owned by S, or one of its
    CHANGED lines references S (an edge into or out of S added or removed).

    Only the changed lines count for a reference: the kafka/keycloak policies list every
    namespace that talks to them, and a whole-document match would hand every service a finding
    whenever one caller is added."""
    for key, old, new, diff, changed in docs:
        kind = key[0]
        owned = owns(ident, old or new, other_names)
        if kind == "NetworkPolicy" and owned:
            return f"{rel} (ingress/egress: {key[1]})"
        if kind != "NetworkPolicy" and owned and hunk_moves_boundary(diff):
            return f"{rel} (Deployment/Rollout: {key[1]})"
        refs = references(ident, changed)
        if refs:
            what = "ingress/egress" if kind == "NetworkPolicy" else "Deployment/Rollout"
            return f"{rel} ({what}: {key[1]} changes a line naming {refs[0]})"
    return None


_IDENTITY_CACHE: dict[tuple, dict | None] = {}
_OTHERS_CACHE: dict[tuple, set[str]] = {}


def identified_workload_names(refs: list[str | None]) -> set[str]:
    """Workload names of every money-path service that resolves to an identity. A document in a
    shared namespace that names one of THESE belongs to that service; one that names an
    unidentified workload (redis, a sidecar job) stays with every owner of the namespace."""
    key = tuple(refs)
    if key not in _OTHERS_CACHE:
        names: set[str] = set()
        for svc in load_money_path_services():
            ident = service_identity(svc, refs)
            if ident:
                names |= ident["names"]
        _OTHERS_CACHE[key] = names
    return _OTHERS_CACHE[key]


def gitops_hit(
    service: str, comp: str, fname: str, base: str | None = None, head: str = "HEAD",
) -> str | None:
    """Reason string if a changed gitops file moves S's trust boundary — see the block above."""
    rel = f"{COMPONENTS_REL}/{comp}/{fname}"
    refs = [base, head] if base is not None else [None]
    ck = (service, tuple(refs))
    if ck not in _IDENTITY_CACHE:
        _IDENTITY_CACHE[ck] = service_identity(service, refs)
    ident = _IDENTITY_CACHE[ck]
    if ident is None or base is None:
        # No resolvable workload, or no git to read documents from: the legacy name match,
        # which over-reports — the safe direction.
        return legacy_gitops_hit(service, comp, fname, base, head)
    others = identified_workload_names(refs)
    return reference_hit(ident, others, rel, changed_boundary_docs(base, head, rel))


def boundary_reasons(service: str, changed: list[str], base: str | None, head: str = "HEAD") -> list[str]:
    reasons: list[str] = []
    for rel in changed:
        m = REST_RE.match(rel)
        if m and m.group(1) == service:
            reasons.append(f"{rel} (inbound REST surface)")
            continue
        m = CLIENT_RE.match(rel)
        if m and m.group(1) == service:
            reasons.append(f"{rel} (outbound client edge)")
            continue
        m = ADAPTER_RE.match(rel)
        if m and m.group(1) == service and adapter_is_client(rel):
            reasons.append(f"{rel} (HTTP/gRPC adapter)")
            continue
        m = APP_YAML_RE.match(rel)
        if m and m.group(1) == service and yaml_security_keys_changed(base, head, rel):
            reasons.append(f"{rel} (authn/listener/transport keys)")
            continue
        m = GITOPS_RE.match(rel)
        if m:
            hit = gitops_hit(service, m.group(1), m.group(2), base, head)
            if hit:
                reasons.append(hit)
    return reasons


SELF_TEST_CASES: list[tuple[str, str, bool]] = [
    (
        "pod-roll annotation restamp only (a rules.yaml edit does this fleet-wide)",
        "@@\n-        openbank.tech/policy-checksum: \"8f455914ec6024b2\"\n"
        "+        openbank.tech/policy-checksum: \"6ef160b0682ff4d9\"\n",
        False,
    ),
    (
        "image tag bump only (auto-deploy rewrites this on every push)",
        "@@\n-        image: ghcr.io/jiraska/openbank-ledger-service:sandbox-8992de5\n"
        "+        image: ghcr.io/jiraska/openbank-ledger-service:sandbox-2b8a7cf\n",
        False,
    ),
    (
        "a new container port IS a boundary change",
        "@@\n         ports:\n+          - containerPort: 9443\n",
        True,
    ),
    (
        "a changed service account IS a boundary change",
        "@@\n-      serviceAccountName: ledger\n+      serviceAccountName: ledger-privileged\n",
        True,
    ),
    (
        "a new OIDC issuer env var IS a boundary change",
        "@@\n+            - name: QUARKUS_OIDC_AUTH_SERVER_URL\n"
        "+              value: https://keycloak.example/realms/openbank\n",
        True,
    ),
    (
        "a replica count is not a boundary change",
        "@@\n-  replicas: 2\n+  replicas: 3\n",
        False,
    ),
    (
        "an annotation restamp AND a port change still flags",
        "@@\n-        openbank.tech/policy-checksum: \"aaaa\"\n"
        "+        openbank.tech/policy-checksum: \"bbbb\"\n+          - containerPort: 8443\n",
        True,
    ),
]


# Multi-document gitops manifest cases (#9071): boundary_docs_text must scope the diff to
# just the Deployment/Rollout/NetworkPolicy documents in the file, so a hunk in a sibling
# PodMonitor/Service document sharing the same file is never read as a change to the
# workload's own boundary. These exercise the actual gitops_hit code path (document split +
# difflib), not just hunk_moves_boundary on a hand-written diff -- the earlier SELF_TEST_CASES
# above would all still pass even if the document-scoping were missing entirely.
DOC_SELF_TEST_CASES: list[tuple[str, str, str, bool]] = [
    (
        "a removed PodMonitor's scrape port must NOT flag the Deployment sharing its file",
        "apiVersion: apps/v1\nkind: Deployment\nspec:\n  ports:\n    - port: 8085\n"
        "---\n"
        "apiVersion: monitoring.coreos.com/v1\nkind: PodMonitor\nspec:\n"
        "  podMetricsEndpoints:\n    - port: management\n",
        "apiVersion: apps/v1\nkind: Deployment\nspec:\n  ports:\n    - port: 8085\n",
        False,
    ),
    (
        "a real Deployment containerPort change in the SAME multi-doc file must still flag",
        "apiVersion: apps/v1\nkind: Deployment\nspec:\n  ports:\n    - port: 8085\n"
        "---\n"
        "apiVersion: monitoring.coreos.com/v1\nkind: PodMonitor\nspec:\n"
        "  podMetricsEndpoints:\n    - port: management\n",
        "apiVersion: apps/v1\nkind: Deployment\nspec:\n  ports:\n    - port: 8085\n"
        "    - port: 9443\n"
        "---\n"
        "apiVersion: monitoring.coreos.com/v1\nkind: PodMonitor\nspec:\n"
        "  podMetricsEndpoints:\n    - port: management\n",
        True,
    ),
]


def doc_scoped_flags(before: str, after: str) -> bool:
    """Same comparison gitops_hit makes: scope both sides to boundary docs, diff, classify."""
    base_scoped = boundary_docs_text(before)
    head_scoped = boundary_docs_text(after)
    if base_scoped == head_scoped:
        return False
    diff_text = "\n".join(
        difflib.unified_diff(base_scoped.splitlines(), head_scoped.splitlines(), lineterm=""),
    )
    return hunk_moves_boundary(diff_text)


# Reference-attribution cases (#12445 follow-up). Each runs the real changed_boundary_docs ->
# reference_hit path over synthetic before/after manifests against pension-service's identity.
PENSION = {
    "names": {"pension-service"}, "namespaces": {"pension"}, "components": {"pension"},
    "principals": {"service-account-openbank-pension", "service-account-openbank-pension-service"},
}
OTHER_WORKLOADS = {"pension-service", "pension-fund-service", "ledger-service"}


def _np(name: str, ns: str, app: str, froms: list[str]) -> str:
    rules = "".join(
        f"    - from:\n        - namespaceSelector:\n            matchLabels:\n"
        f"              kubernetes.io/metadata.name: {f}\n" for f in froms)
    return (f"apiVersion: networking.k8s.io/v1\nkind: NetworkPolicy\nmetadata:\n  name: {name}\n"
            f"  namespace: {ns}\nspec:\n  podSelector:\n    matchLabels:\n"
            f"      app.kubernetes.io/name: {app}\n  ingress:\n{rules}")


def _dep(name: str, ns: str, env: dict[str, str]) -> str:
    e = "".join(f"            - name: {k}\n              value: {v}\n" for k, v in env.items())
    return (f"apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: {name}\n  namespace: {ns}\n"
            f"spec:\n  template:\n    spec:\n      containers:\n        - name: app\n          env:\n{e}")


REF_SELF_TEST_CASES: list[tuple[str, str, str, bool]] = [
    ("KNOWN-POSITIVE: a pension-fund NetworkPolicy that starts admitting `pension` needs pension's TM",
     _np("pension-fund-service-ingress-allow-list", "pension-fund", "pension-fund-service", ["admin-ui"]),
     _np("pension-fund-service-ingress-allow-list", "pension-fund", "pension-fund-service", ["admin-ui", "pension"]),
     True),
    ("KNOWN-NEGATIVE: a ledger-pension-co policy naming nothing of pension-service does not",
     _np("ledger-service-ingress-allow-list", "ledger-pension-co", "ledger-service", ["admin-ui"]),
     _np("ledger-service-ingress-allow-list", "ledger-pension-co", "ledger-service", ["admin-ui", "tax-reporting"]),
     False),
    ("KNOWN-NEGATIVE: a pension-fund policy admitting `pension-fund-ops` is not `pension`",
     _np("pension-fund-service-ingress-allow-list", "pension-fund", "pension-fund-service", ["admin-ui"]),
     _np("pension-fund-service-ingress-allow-list", "pension-fund", "pension-fund-service", ["admin-ui", "pension-fund-ops"]),
     False),
    ("a ledger-pension-co Deployment env naming the pension-fund principal is not pension's",
     _dep("ledger-service", "ledger-pension-co", {"X": "a"}),
     _dep("ledger-service", "ledger-pension-co", {"X": "a", "CALLER": "service-account-openbank-pension-fund"}),
     False),
    ("a caller Deployment that adds a URL to pension-service.pension.svc needs pension's TM",
     _dep("tax-reporting-service", "tax-reporting", {"X": "a"}),
     _dep("tax-reporting-service", "tax-reporting", {"X": "a", "PENSION_SERVICE_URL": "https://pension-service.pension.svc:8443"}),
     True),
    ("a caller Deployment that adds the pension principal needs pension's TM",
     _dep("tax-reporting-service", "tax-reporting", {"X": "a"}),
     _dep("tax-reporting-service", "tax-reporting", {"X": "a", "ALLOWED": "service-account-openbank-pension"}),
     True),
    ("a shared broker policy that already lists `pension` and adds another namespace does not",
     _np("kafka-broker-ingress-allow-list", "kafka", "kafka", ["pension", "ledger"]),
     _np("kafka-broker-ingress-allow-list", "kafka", "kafka", ["pension", "ledger", "tax-reporting"]),
     False),
    ("pension's OWN policy changing counts even when it names nothing new",
     _np("pension-service-ingress-allow-list", "pension", "pension-service", ["admin-ui"]),
     _np("pension-service-ingress-allow-list", "pension", "pension-service", ["admin-ui", "tax-reporting"]),
     True),
    ("a comment mentioning `namespace: pension` is not a reference",
     _dep("ledger-service", "ledger-pension-co", {"X": "a"}),
     _dep("ledger-service", "ledger-pension-co", {"X": "a"}) + "# namespace: pension\n",
     False),
]


def ref_flags(before: str, after: str) -> bool:
    changed_boundary_docs.cache_clear()
    rel = "openbank-infra/gitops/components/x/test.yaml"
    _PREFETCHED[("BASE", rel)] = before
    _PREFETCHED[("HEAD", rel)] = after
    read_at_ref.cache_clear()
    return reference_hit(PENSION, OTHER_WORKLOADS, rel, changed_boundary_docs("BASE", "HEAD", rel)) is not None


def self_test() -> int:
    """Feed the classifier diffs it MUST flag and diffs it MUST NOT.

    This gate shipped advisory with no self-test, so its failure path had never run —
    the 7-of-60 false-positive rate in #3431 was found by hand, not by CI. A gate whose
    only demonstrated behaviour is passing cannot be graduated to enforced.
    """
    ok = True
    for name, diff_text, expected in SELF_TEST_CASES:
        got = hunk_moves_boundary(diff_text)
        mark = "ok" if got == expected else "FAIL"
        if got != expected:
            ok = False
        print(f"  [{mark}] {name}: flagged={got} expected={expected}")
    for name, before, after, expected in DOC_SELF_TEST_CASES:
        got = doc_scoped_flags(before, after)
        mark = "ok" if got == expected else "FAIL"
        if got != expected:
            ok = False
        print(f"  [{mark}] {name}: flagged={got} expected={expected}")
    for name, before, after, expected in REF_SELF_TEST_CASES:
        got = ref_flags(before, after)
        mark = "ok" if got == expected else "FAIL"
        if got != expected:
            ok = False
        print(f"  [{mark}] {name}: flagged={got} expected={expected}")
    print(f"self-test: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="origin/main", help="PR base ref/sha (3-dot diff)")
    ap.add_argument("--head", default="HEAD", help="head ref/sha; lets a measurement replay a past commit")
    ap.add_argument("--changed-files", help="file with a newline-separated changed-file list (skips git)")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", help="run the classifier's known-positive/negative cases")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.changed_files:
        changed = [
            line.strip()
            for line in pathlib.Path(args.changed_files).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        base = None
    else:
        changed = changed_files(args.base, args.head)
        base = args.base

    if base is not None:
        gitops = [r for r in changed if GITOPS_RE.match(r)]
        prefetch(base, gitops)
        prefetch(args.head, gitops)

    level = "error" if args.enforce else "warning"
    services = load_money_path_services()
    changed_set = set(changed)
    findings: list[tuple[str, list[str]]] = []

    for service in services:
        reasons = boundary_reasons(service, changed, base, args.head)
        if not reasons:
            continue
        tm_rel = f"docs/threat-models/{service}.md"
        if tm_rel in changed_set:
            print(f"threat-model-diff gate: {service}: trust-boundary change WITH a {tm_rel} update — OK.")
        else:
            findings.append((service, reasons))

    for service, reasons in findings:
        print(
            f"::{level}::threat-model-diff gate: {service}: trust-boundary change without a "
            f"docs/threat-models/{service}.md update — {'; '.join(reasons)} (ADR-0030 D2)"
        )

    if findings and args.enforce:
        return 1
    if findings:
        print(
            f"threat-model-diff gate: {len(findings)} finding(s) — advisory until the ADR-0144 "
            "target_enforce_date; will become a hard gate."
        )
    else:
        print("threat-model-diff gate: no money-path trust-boundary change without a threat-model update.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
