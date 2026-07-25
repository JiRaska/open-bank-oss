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
— against the same set read off the JAX-RS annotations of that service's resource classes.
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

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

HTTP_VERBS = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
SPEC_METHODS = tuple(v.lower() for v in HTTP_VERBS)

# A JAX-RS *client* stub carries the CALLEE's routes, not this service's served contract.
CLIENT_HINT = re.compile(r"@RegisterRestClient")


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


def code_routes(service_dir: Path) -> set[str]:
    """(VERB, absolute path) pairs read off the JAX-RS annotations of the service's resources.

    Annotations are collected as the contiguous cluster preceding a `fun`, so the gate does not
    care whether @Path sits before or after @GET — only that both decorate the same method.
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
                        routes.add(f"{verb} {normalize(base + sub)}")
            cluster = []
    return routes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--service", help="check a single openbank-* module (default: the whole fleet)")
    args = ap.parse_args()

    level = "error" if args.enforce else "warning"
    specs = sorted(Path(".").glob("openbank-*/src/main/resources/openapi.yaml"))
    if args.service:
        specs = [s for s in specs if s.parts[0] == args.service]
        if not specs:
            print(f"::error::openapi-route-conformance: no openapi.yaml for service {args.service!r}")
            return 1

    findings = 0
    checked = 0
    no_resources: list[str] = []

    for spec_path in specs:
        service_dir = Path(spec_path.parts[0])
        service = service_dir.name
        declared = spec_routes(spec_path.read_text(encoding="utf-8", errors="replace"))
        served = code_routes(service_dir)

        # A service with no annotated resource class at all is not evidence of drift — it is a
        # service this heuristic cannot see (generated resources, a non-JAX-RS surface). Report
        # the skip explicitly rather than scoring it clean.
        if not served:
            no_resources.append(service)
            continue

        checked += 1
        for route in sorted(served - declared):
            findings += 1
            print(f"::{level}::openapi-route-conformance: {service}: served-not-published — "
                  f"{route} is answered by a resource class and absent from openapi.yaml")
        for route in sorted(declared - served):
            findings += 1
            print(f"::{level}::openapi-route-conformance: {service}: published-not-served — "
                  f"{route} is promised by openapi.yaml and no resource class declares it")

    print(
        f"check-openapi-route-conformance: {checked} service(s) compared, "
        f"{len(no_resources)} skipped with no annotated resource class"
        + (f" ({', '.join(no_resources)})" if no_resources else "")
        + f", {findings} route-level finding(s)."
    )
    if findings and not args.enforce:
        print("check-openapi-route-conformance: advisory — will become a hard gate "
              "once the backlog is cleared (see rules.yaml: openapi_route_conformance).")
    return 1 if (findings and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
