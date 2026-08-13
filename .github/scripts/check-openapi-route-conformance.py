#!/usr/bin/env python3
"""openapi-route-conformance gate — the published contract vs the routes actually served.

WHY THIS EXISTS: nothing in this repo compared an `openapi.yaml` to the code serving it.
ADR-0005 states the fleet is design-first and that "CI lints it with Spectral so docs cannot
drift from runtime" — there is no Spectral config and no workflow references it. The only
reader of these files, `check-api-contract.py`, does version arithmetic between two revisions
of the *document*; it never asks whether the document is true. So specs rotted in silence:
aml-service published request and response schemas whose fields exist nowhere in the service
(#2312), and copilot-service served the human-in-the-loop confirm endpoint for a money-path
action proposal without publishing it at all (#2323). This is the #2280 failure class — check
whether anything reads the artifact before assuming a green covers it.

WHAT IT CHECKS: for every `openbank-*/src/main/resources/openapi.yaml`, the set of
(HTTP method, absolute path) pairs declared under `paths:` — resolved against `servers[0].url`
— against the same set read off the JAX-RS annotations of that service's resource classes. A
resource that implements an OpenAPI-generated server interface is also an authoritative served
route declaration: compilation proves it implements the generated methods and the generator owns
the JAX-RS annotations, so no handwritten annotation copy is needed.
Drift is reported in both directions:

    served-not-published   a route the code answers and the contract omits
    published-not-served   a route the contract promises and no resource declares

WHAT IT DOES NOT PROVE. Only the route SET. It does not look inside a schema, so an invented
request field or a response property that no DTO carries passes clean (that was the whole aml
defect). It does not check status codes, so a documented response the handler cannot produce
passes clean (copilot published a 501 its streaming endpoint is structurally incapable of
returning). It does not boot Quarkus, so a route that exists as an annotation but fails at
runtime on CDI, a filter or the policy gate passes clean. Treat a green here as "the route
list agrees", nothing more — the schema half needs the generated document, see the issue
referenced in rules.yaml.

TWO SERVER CONVENTIONS, both live in this tree and both must be handled or the gate reports
confident nonsense on half the fleet:

    servers: [{url: "/api/v1"}]                         path prefix — copilot
    servers: [{url: "http://localhost:8117"}]           absolute, no prefix — aml
    servers: [{url: "https://host/customer/v1"}]        absolute WITH a prefix — customer-edge

stdlib-only, same as check-api-contract.py; shells out to `git` for nothing — it reads the
working tree, so it runs identically in CI and locally.

Usage:
    check-openapi-route-conformance.py [--enforce] [--service <name>]
                                       [--baseline <file>] [--write-baseline <file>]

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1

THE BASELINE, and why it is not a hand-kept scope list. Enforcing on day one was impossible:
104 pre-existing route-level findings across 21 services, and correcting a spec is blocked on
the unsatisfiable-bump problem (#2313), so the backlog cannot be drained in one PR. The
alternative — leaving the gate advisory — is the shape this repo has already ruled against: an
advisory check over a GENERATED comparison has no judgement left to exercise, it just makes the
drift mergeable (#2216).

So the gate is ENFORCED against a declared baseline of the exact findings that existed on the
day it flipped. Two directions, both hard failures:

    a finding NOT in the baseline   NEW drift — the thing the gate exists to stop. Fails.
    a baseline entry with NO finding  STALE — the debt was paid. Fails, telling you to delete
                                    the line, so the list cannot outlive the debt it declares.

That is deliberately the `KNOWN_UNCOVERED` shape of check-pact-provider-replay.py, and it is
NOT the failure mode of pact-drift-check.yml (#2284): the baseline does not narrow what the
gate *looks at* — every service is still compared every run — it only declares which of the
findings it produces are already-known debt. The exclusions are the thing a human justifies.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

HTTP_VERBS = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
SPEC_METHODS = tuple(v.lower() for v in HTTP_VERBS)

BASELINE_HEADER = """\
# openapi-route-conformance — DECLARED BACKLOG (see the script's module docstring).
#
# Every line is a route-level finding that already existed when the gate flipped to --enforce
# on 2026-07-26 (issue #2314). The gate still compares every service on every run; this file
# only declares which of its findings are known debt.
#
#   a finding not listed here  => NEW drift, the build fails
#   a line here with no finding => the debt was paid, the build fails until the line is deleted
#
# So: to correct a spec, delete its line(s) here in the same PR. Never add a line to silence a
# new finding — publishing the route (or deleting it) is the fix. Draining this file to empty
# closes #2314; the per-service corrections are blocked on the unsatisfiable-bump gap (#2313).
#
# Format: <service> <served-not-published|published-not-served> <VERB> <absolute path>
"""

# A JAX-RS *client* stub carries the CALLEE's routes, not this service's served contract.
CLIENT_HINT = re.compile(r"@RegisterRestClient")
GENERATED_API_IMPORT = re.compile(r"import\s+[\w.]+\.generated\.api\.([A-Za-z][A-Za-z0-9_]*Api)\b")


def normalize(path: str) -> str:
    """Collapse duplicate slashes and drop a trailing one, so /a//b/ == /a/b."""
    collapsed = re.sub(r"/+", "/", path)
    return collapsed.rstrip("/") or "/"


def server_prefix(spec_text: str) -> str:
    """Path prefix implied by servers[0].url — '' when the URL carries no path component."""
    m = re.search(
        r"^servers:\s*$\n(?:\s*#.*\n|\s*\n)*\s*-\s*url:\s*[\"']?(\S+?)[\"']?\s*$",
        spec_text,
        re.M,
    )
    if not m:
        return ""
    url = m.group(1)
    path = url if url.startswith("/") else re.sub(r"^[a-z][a-z0-9+.-]*://[^/]*", "", url)
    return path.rstrip("/")


def spec_routes(spec_text: str) -> set[str]:
    """(VERB, absolute path) pairs declared under `paths:`, resolved against the server prefix."""
    prefix = server_prefix(spec_text)
    routes: set[str] = set()
    current: str | None = None
    in_paths = False
    for line in spec_text.splitlines():
        if re.match(r"^paths:\s*$", line):
            in_paths = True
            continue
        if in_paths and re.match(r"^[A-Za-z]", line):  # left the paths block
            in_paths = False
        if not in_paths:
            continue
        path_key = re.match(r"^  ([\"']?)(/\S*?)\1:\s*$", line)
        if path_key:
            current = prefix + path_key.group(2)
            continue
        method = re.match(r"^    ([a-z]+):\s*$", line)
        if method and current and method.group(1) in SPEC_METHODS:
            routes.add(f"{method.group(1).upper()} {normalize(current)}")
    return routes


def generated_api_routes(spec_text: str) -> dict[str, set[str]]:
    """Routes owned by each OpenAPI tag's generated `<Tag>Api` server interface."""
    prefix = server_prefix(spec_text)
    routes: dict[str, set[str]] = {}
    current: str | None = None
    method: str | None = None
    for line in spec_text.splitlines():
        path_key = re.match(r"^  ([\"']?)(/\S*?)\1:\s*$", line)
        if path_key:
            current = prefix + path_key.group(2)
            method = None
            continue
        operation = re.match(r"^    ([a-z]+):\s*$", line)
        if operation:
            method = operation.group(1).upper() if operation.group(1) in SPEC_METHODS else None
            continue
        tags = re.match(r"^      tags:\s*\[([^]]+)]\s*$", line)
        if not (tags and current and method):
            continue
        for raw_tag in tags.group(1).split(","):
            words = re.findall(r"[A-Za-z0-9]+", raw_tag)
            if not words:
                continue
            api = "".join(word[:1].upper() + word[1:] for word in words) + "Api"
            routes.setdefault(api, set()).add(f"{method} {normalize(current)}")
    return routes


def code_routes(service_dir: Path) -> set[str]:
    """(VERB, absolute path) pairs read off the JAX-RS annotations of the service's resources.

    Annotations are collected as the contiguous cluster preceding a `fun`, so the gate does not
    care whether @Path sits before or after @GET — only that both decorate the same method.

    A method-level @Path may be written WITHOUT a leading slash — JAX-RS inserts the separator
    (JSR-370 §3.4: the class and method templates are concatenated "with a '/' between them if
    necessary"), so `@Path("/api/v1/parties/eudi")` + `@Path("credential")` serves
    `/api/v1/parties/eudi/credential`. Concatenating the two strings raw invents
    `/api/v1/parties/eudicredential` — a route no client can call and no spec can publish, so
    the SAME endpoint is reported twice, once in each direction. That was 10 of the 104 declared
    baseline entries, all of pid-service's EUDI issuance surface (#2314).
    """
    routes: set[str] = set()
    src = service_dir / "src/main/kotlin"
    if not src.is_dir():
        return routes
    for kt in sorted(src.rglob("*.kt")):
        if "/client/" in kt.as_posix().lower():
            continue
        try:
            text = kt.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if CLIENT_HINT.search(text):
            continue
        class_path = re.search(r"^@Path\(\s*\"([^\"]*)\"\s*\)", text, re.M)
        if not class_path:
            continue
        base = class_path.group(1).rstrip("/")

        cluster: list[str] = []
        pending = ""      # an annotation whose argument list spans several lines
        depth = 0
        for line in text.splitlines():
            stripped = line.strip()

            # Continuation of a multi-line annotation, e.g. @Operation(\n  summary = "...",\n).
            # Without this the closing lines look like ordinary code and would reset the cluster,
            # dropping the @POST/@Path that preceded them (measured: it hid copilot's confirm route).
            if pending:
                pending += " " + stripped
                depth += stripped.count("(") - stripped.count(")")
                if depth <= 0:
                    cluster.append(pending)
                    pending, depth = "", 0
                continue

            if stripped.startswith("@"):
                balance = stripped.count("(") - stripped.count(")")
                if balance > 0:
                    pending, depth = stripped, balance
                else:
                    cluster.append(stripped)
                continue

            if not stripped or stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue

            if re.match(r"^(?:(?:public|private|internal|protected|open|override|suspend)\s+)*fun\b", stripped):
                verbs = [v for v in HTTP_VERBS if any(re.fullmatch(rf"@{v}(\(\))?", a) for a in cluster)]
                if verbs:
                    sub = ""
                    for a in cluster:
                        m = re.match(r"^@Path\(\s*\"([^\"]*)\"\s*\)$", a)
                        if m:
                            sub = m.group(1)
                            break
                    for verb in verbs:
                        routes.add(f"{verb} {normalize(base + '/' + sub)}")
            cluster = []
    return routes


def generated_server_routes(service_dir: Path, spec_text: str) -> set[str]:
    """Routes declared by generated server interfaces implemented in this service."""
    src = service_dir / "src/main/kotlin"
    if not src.is_dir():
        return set()
    implemented: set[str] = set()
    for kt in sorted(src.rglob("*.kt")):
        try:
            text = kt.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for api in set(GENERATED_API_IMPORT.findall(text)):
            if re.search(rf":\s*{re.escape(api)}\b", text):
                implemented.add(api)
    by_api = generated_api_routes(spec_text)
    return set().union(*(by_api.get(api, set()) for api in implemented)) if implemented else set()


SHARED_RUNTIME = Path("openbank-libs-runtime")

# Quarkus serves its management surface (health, metrics, the generated spec) from extensions,
# never from a resource class in the service tree — `/q/**` is structurally invisible here.
QUARKUS_MANAGEMENT = re.compile(r"^/q(/|$)")


def externally_served(route: str, shared: set[str]) -> bool:
    """True when a PUBLISHED route is served by something outside the service's own resources.

    Two such servers exist and the JAX-RS scan can see neither:

      * Quarkus extensions — `/q/health/live` etc. are answered by quarkus-smallrye-health.
      * openbank-libs-runtime — `ServiceInfoResource` declares `@Path("/api/v1/info")` and is on
        every service's classpath, so a service that publishes it is telling the truth.

    Only the published-not-served direction consults this. It deliberately does NOT add these
    routes to the served set: `/api/v1/info` is answered by all 44 services and published by one,
    and scoring the other 43 as served-not-published would be reporting a documentation choice
    as drift — the confident-nonsense failure this gate's docstring warns about (#2314).
    """
    path = route.split(" ", 1)[1]
    return bool(QUARKUS_MANAGEMENT.match(path)) or route in shared


def load_baseline(path: Path) -> set[str]:
    """Declared, already-known findings — one `<service> <direction> <VERB> <path>` per line."""
    entries: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            entries.add(" ".join(line.split()))
    return entries


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--service", help="check a single openbank-* module (default: the whole fleet)")
    ap.add_argument("--baseline", help="file of declared, already-known findings (see module docstring)")
    ap.add_argument("--write-baseline", help="rewrite the baseline file from the current findings")
    args = ap.parse_args()

    level = "error" if args.enforce else "warning"
    specs = sorted(Path(".").glob("openbank-*/src/main/resources/openapi.yaml"))
    if args.service:
        specs = [s for s in specs if s.parts[0] == args.service]
        if not specs:
            print(f"::error::openapi-route-conformance: no openapi.yaml for service {args.service!r}")
            return 1

    shared = code_routes(SHARED_RUNTIME)

    found: dict[str, str] = {}   # "<service> <direction> <VERB> <path>" -> human sentence
    checked = 0
    no_resources: list[str] = []

    for spec_path in specs:
        service_dir = Path(spec_path.parts[0])
        service = service_dir.name
        spec_text = spec_path.read_text(encoding="utf-8", errors="replace")
        declared = spec_routes(spec_text)
        served = code_routes(service_dir) | generated_server_routes(service_dir, spec_text)

        # A service with no annotated resource class at all is not evidence of drift — it is a
        # service this heuristic cannot see (generated resources, a non-JAX-RS surface). Report
        # the skip explicitly rather than scoring it clean.
        if not served:
            no_resources.append(service)
            continue

        checked += 1
        for route in sorted(served - declared):
            found[f"{service} served-not-published {route}"] = (
                f"{service}: served-not-published — {route} is answered by a resource class "
                f"and absent from openapi.yaml")
        for route in sorted(declared - served):
            if externally_served(route, shared):
                continue
            found[f"{service} published-not-served {route}"] = (
                f"{service}: published-not-served — {route} is promised by openapi.yaml "
                f"and no resource class declares it")

    if args.write_baseline:
        Path(args.write_baseline).write_text(
            BASELINE_HEADER + "".join(f"{k}\n" for k in sorted(found)), encoding="utf-8")
        print(f"check-openapi-route-conformance: wrote {len(found)} entr(ies) to {args.write_baseline}")
        return 0

    baseline = load_baseline(Path(args.baseline)) if args.baseline else set()
    # `--service` compares one module, so the rest of the baseline is legitimately unmatched.
    scoped = {e for e in baseline if not args.service or e.split(" ", 1)[0] == args.service}

    new = sorted(k for k in found if k not in baseline)
    stale = sorted(scoped - set(found))

    for key in new:
        print(f"::{level}::openapi-route-conformance: {found[key]}")
    for key in sorted(k for k in found if k in baseline):
        print(f"::notice::openapi-route-conformance: declared backlog — {found[key]}")
    for key in stale:
        print(f"::{level}::openapi-route-conformance: STALE baseline entry — {key!r} is no longer "
              f"a finding. Delete the line from {args.baseline}; the declared backlog must not "
              f"outlive the debt.")

    print(
        f"check-openapi-route-conformance: {checked} service(s) compared, "
        f"{len(no_resources)} skipped with no annotated resource class"
        + (f" ({', '.join(no_resources)})" if no_resources else "")
        + f", {len(found)} route-level finding(s), {len(baseline)} declared in the baseline, "
        f"{len(new)} NEW, {len(stale)} stale."
    )
    if (new or stale) and not args.enforce:
        print("check-openapi-route-conformance: advisory — no --enforce, so this is a warning.")
    return 1 if ((new or stale) and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
