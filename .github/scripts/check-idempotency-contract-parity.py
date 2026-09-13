#!/usr/bin/env python3
"""idempotency-contract-parity gate — the idempotency key a spec PROMISES vs the one a handler READS.

WHY THIS EXISTS, and why it does not count a marker. openbank-libs once published an
`@Idempotent` annotation that was a plain RUNTIME marker: no `@InterceptorBinding`, no
interceptor. Applying it compiled, reviewed as correct, and did nothing — on a payment endpoint
it would have let a duplicate POST through while the source read as protected (#4011, gate
`libs-annotations-implemented`). `IdempotencyStore`'s KDoc records the conclusion: there is
deliberately no declarative form. So a gate that counts annotations — or counts the *presence*
of an `Idempotency-Key` parameter anywhere — would certify exactly that defect. This gate keys
on the only thing that decides whether replay protection can ever engage: does the header name
the CONTRACT tells a client to send equal the header name the HANDLER binds?

It does not, because a client sends what the spec says. When the two names disagree the
`@HeaderParam` injects **null** on every request — the fleet-standard shape is

    require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }

so the endpoint either rejects every spec-conformant call or, where the key is optional, skips
the store lookup and the store save silently and processes every duplicate. Both were live when
this gate was written (see the baseline).

WHAT IT COMPARES. For every `openbank-*/src/main/resources/openapi.yaml`, each mutating
operation (post/put/patch/delete) resolved to an absolute route against `servers[0].url`, against
the JAX-RS annotations of that service's resource classes. Three directions, all reported:

    name-mismatch         spec declares header N, the handler binds M != N -> handler sees null
    published-not-bound   spec declares header N, the handler binds no idempotency header
    bound-not-published   the handler binds N, the published operation declares none, so no
                          generated client ever sends it and the protection is unreachable

An idempotency header is any parameter whose name matches `(X-)?Idempotency-Key`. BOTH spellings
are live in this tree — psd2-service publishes `X-Idempotency-Key` while its handlers bind
`Idempotency-Key` — which is precisely why the comparison is by EXACT NAME and the pattern is
only used to decide that a parameter is *about* idempotency at all. Normalising the two spellings
together would make the fleet's sharpest finding invisible.

WHAT IT DOES NOT PROVE, so a green here is not "the fleet is idempotent":

  * Only the HEADER carrier. A key carried as a request-BODY field is a real and separate idiom
    here — transaction-service's `InitiateTransactionCommand.idempotencyKey` and ledger-service
    take theirs that way, and internal callers derive one (`"workflow-${id}-ledger"`,
    `event.paymentId.toString()`). Nine mutating operations carry it in the body today. Those are
    out of scope, NOT clean: this gate reports nothing about them in either direction.
  * Not whether an endpoint SHOULD be idempotent. Deciding that a given money-path POST needs a
    client-supplied key is a judgement (an operator approve/cancel, an inbound scheme webhook and
    a batch trigger legitimately take none), and a hand-kept classification list is the failure
    mode this repo has already ruled against — a gate whose scope is a hand-kept list of the thing
    it checks reads as PASSING when the list is short. The corpus here is derived instead: every
    mutating operation in every published spec, with nothing to keep by hand.
  * Not whether a bound key reaches a durable mechanism. Attribution from an endpoint to the
    store call or the UNIQUE constraint that enforces it needs real data-flow analysis across the
    resource -> use-case -> repository layers; two carriers and three layers make a regex answer
    confident nonsense. What this gate establishes is the precondition: the handler can receive
    the value at all.

THE ROUTE EXTRACTOR is deliberately the same shape as check-openapi-route-conformance.py's
`code_routes` — the annotation cluster preceding a `fun`, a method `@Path` joined to the class
`@Path` with a separator — extended to also capture the idempotency `@HeaderParam` of that
method's signature. It is a second copy rather than an import, so the self-test pins the one
hazard that copy has to get right: the cluster MUST reset on an ordinary code line, or the
class-level `@Path` leaks into the first method and every route doubles its own base
(`/api/v1/sepa-payments/api/v1/sepa-payments`). That bug reported six clean services as findings
while the gate was being written, and it reads as a real defect, not as a crash.

An outbound `@RegisterRestClient` interface carries the CALLEE's annotations and is excluded:
the caller supplies the argument and the compiler checks it, so it is not an endpoint. Half of a
naive grep for `@HeaderParam("Idempotency-Key")` is those client stubs — six of forty-two here.

Usage:
    check-idempotency-contract-parity.py [--enforce] [--service <name>]
                                         [--baseline <file>] [--write-baseline <file>]
                                         [--self-test]

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

SPEC_METHODS = {"post", "put", "patch", "delete"}
HTTP_VERBS = ("POST", "PUT", "PATCH", "DELETE")
CLIENT_HINT = re.compile(r"@RegisterRestClient")
IDEM_NAME = re.compile(r"^(?:x-)?idempotency-key$", re.IGNORECASE)
HDR_BINDING = re.compile(
    r'@(?:field:)?HeaderParam\(\s*"((?:[Xx]-)?[Ii]dempotency-[Kk]ey)"\s*\)\s*(\w+)\s*:'
)
FUN_START = re.compile(r"^(?:(?:public|private|internal|protected|open|override|suspend)\s+)*fun\b")

BASELINE_HEADER = """\
# idempotency-contract-parity — declared, already-known findings (issue #8351).
#
# Format: <service> <name-mismatch|published-not-bound|bound-not-published> <VERB> <path>
#
# A finding NOT listed here is NEW drift and fails. A line here with no matching finding is
# STALE and also fails — the declared backlog must not outlive the debt it declares.
"""


def normalize(path: str) -> str:
    """Collapse duplicate slashes and drop a trailing one, so /a//b/ == /a/b."""
    return re.sub(r"/+", "/", path).rstrip("/") or "/"


def server_prefix(spec_text: str) -> str:
    """Path prefix implied by servers[0].url — '' when the URL carries no path component."""
    m = re.search(
        r"^servers:\s*$\n(?:\s*#.*\n|\s*\n)*\s*-\s*url:\s*[\"']?(\S+?)[\"']?\s*$",
        spec_text,
        re.MULTILINE,
    )
    if not m:
        return ""
    url = m.group(1)
    path = url if url.startswith("/") else re.sub(r"^[a-z][a-z0-9+.-]*://[^/]*", "", url)
    return path.rstrip("/")


def _deref(doc, node, budget: int = 20):
    """Follow local $ref chains. A parameter is very often `$ref: '#/components/parameters/X'`;
    treating an unresolved ref as 'not a header' silently drops it from the corpus."""
    while isinstance(node, dict) and "$ref" in node and budget > 0:
        budget -= 1
        ref = node["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/"):
            return None
        cur = doc
        for seg in ref[2:].split("/"):
            seg = seg.replace("~1", "/").replace("~0", "~")
            if not isinstance(cur, dict) or seg not in cur:
                return None
            cur = cur[seg]
        node = cur
    return node


def spec_idempotency(spec_text: str):
    """(all mutating routes, {route: {declared idempotency header names}}) for one spec."""
    doc = yaml.safe_load(spec_text) or {}
    prefix = server_prefix(spec_text)
    every: set[str] = set()
    declared: dict[str, set[str]] = {}
    for raw_path, item in (doc.get("paths") or {}).items():
        if not isinstance(item, dict):
            continue
        shared = item.get("parameters") or []
        for method, op in item.items():
            if str(method).lower() not in SPEC_METHODS or not isinstance(op, dict):
                continue
            route = f"{str(method).upper()} {normalize(prefix + str(raw_path))}"
            every.add(route)
            for raw in list(op.get("parameters") or []) + list(shared):
                prm = _deref(doc, raw)
                if not isinstance(prm, dict) or prm.get("in") != "header":
                    continue
                name = str(prm.get("name", ""))
                if IDEM_NAME.match(name):
                    declared.setdefault(route, set()).add(name)
    return every, declared


def code_idempotency(service_dir: Path) -> dict[str, tuple[str, str, str]]:
    """{route: (header name bound, parameter name, file:line)} off the JAX-RS annotations.

    Same annotation-cluster walk as check-openapi-route-conformance.py's code_routes, plus the
    method signature so the bound header NAME is available. See the module docstring on why the
    cluster reset is the load-bearing line.
    """
    out: dict[str, tuple[str, str, str]] = {}
    src = service_dir / "src/main/kotlin"
    if not src.is_dir():
        return out
    for kt in sorted(src.rglob("*.kt")):
        if "/client/" in kt.as_posix().lower():
            continue
        try:
            text = kt.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if CLIENT_HINT.search(text):        # outbound stub: the CALLEE's contract, not ours
            continue
        class_path = re.search(r"^@Path\(\s*\"([^\"]*)\"\s*\)", text, re.MULTILINE)
        if not class_path:
            continue
        base = class_path.group(1).rstrip("/")
        lines = text.splitlines()
        cluster: list[str] = []
        pending, depth = "", 0
        for i, line in enumerate(lines):
            stripped = line.strip()
            if pending:                      # continuation of a multi-line annotation
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
            if not stripped or stripped.startswith(("//", "*", "/*")):
                continue
            if FUN_START.match(stripped):
                verbs = [v for v in HTTP_VERBS
                         if any(re.fullmatch(rf"@{v}(\(\))?", a) for a in cluster)]
                if verbs:
                    sub = ""
                    for a in cluster:
                        m = re.match(r"^@Path\(\s*\"([^\"]*)\"\s*\)$", a)
                        if m:
                            sub = m.group(1)
                            break
                    signature, balance, j = "", 0, i
                    while j < len(lines):
                        signature += lines[j] + "\n"
                        balance += lines[j].count("(") - lines[j].count(")")
                        if balance <= 0 and "(" in signature:
                            break
                        j += 1
                    hit = HDR_BINDING.search(signature)
                    if hit:
                        for verb in verbs:
                            route = f"{verb} {normalize(base + '/' + sub)}"
                            out[route] = (hit.group(1), hit.group(2), f"{kt.as_posix()}:{i + 1}")
            # THE LOAD-BEARING RESET: an ordinary code line ends the annotation cluster. Without
            # it the class-level @Path leaks into the first method and every route doubles its
            # own base. Pinned by the self-test.
            cluster = []
    return out


def findings_for(service: str, spec_text: str, service_dir: Path):
    """{key: sentence} for one service, plus the number of mutating operations compared."""
    every, declared = spec_idempotency(spec_text)
    bound = code_idempotency(service_dir)
    found: dict[str, str] = {}
    for route in sorted(declared):
        names = declared[route]
        shown = "/".join(sorted(names))
        if route not in bound:
            found[f"{service} published-not-bound {route}"] = (
                f"{service}: published-not-bound — openapi.yaml declares {shown} on {route} and "
                f"no resource method binds an idempotency header, so the value is discarded")
            continue
        got, param, where = bound[route]
        if got.lower() not in {n.lower() for n in names}:
            found[f"{service} name-mismatch {route}"] = (
                f"{service}: name-mismatch — openapi.yaml declares {shown} on {route} but the "
                f"handler binds @HeaderParam(\"{got}\") as `{param}` ({where}). A spec-conformant "
                f"client sends {shown}; the handler is injected null on every request")
    for route in sorted(bound):
        if route in every and route not in declared:
            got, param, where = bound[route]
            found[f"{service} bound-not-published {route}"] = (
                f"{service}: bound-not-published — the handler binds @HeaderParam(\"{got}\") on "
                f"{route} ({where}) and openapi.yaml declares no idempotency header, so no client "
                f"generated from the contract ever sends one")
    return found, len(every)


def load_baseline(path: Path) -> set[str]:
    entries: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            entries.add(" ".join(line.split()))
    return entries


def self_test() -> int:
    """Falsify the gate against a known-POSITIVE and a known-NEGATIVE tree.

    A parity gate fails by reporting clean, and the two ways it can do so are opposite: an
    extractor that finds no bindings reports every service as agreeing with itself, and a
    comparison that folds `Idempotency-Key` and `X-Idempotency-Key` together reports the fleet's
    actual defect as a match. Both are checked below against fixtures written to be wrong.
    """
    import tempfile

    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want!r}, got {got!r}")

    # --- pure helpers ------------------------------------------------------------------
    case("duplicate slashes collapse", normalize("/a//b"), "/a/b")
    case("a trailing slash is dropped", normalize("/a/b/"), "/a/b")
    case("path parameters are preserved", normalize("/a/{id}/b"), "/a/{id}/b")
    case("a host-only server url yields no prefix",
         server_prefix("servers:\n  - url: http://localhost:8115\n"), "")
    case("a prefix server url yields its path",
         server_prefix("servers:\n  - url: /api/v1\n"), "/api/v1")
    # The two spellings must remain DISTINCT strings while both being recognised as idempotency
    # parameters. Folding them is the one change that would hide the psd2 finding.
    case("plain spelling is an idempotency header", bool(IDEM_NAME.match("Idempotency-Key")), True)
    case("X- spelling is an idempotency header", bool(IDEM_NAME.match("X-Idempotency-Key")), True)
    plain, prefixed = "Idempotency-Key", "X-Idempotency-Key"
    case("the two spellings stay distinct strings", plain == prefixed, False)
    case("an unrelated header is not idempotency", bool(IDEM_NAME.match("X-Request-ID")), False)
    case("a near-miss name is not idempotency", bool(IDEM_NAME.match("Idempotency-Keys")), False)

    spec_matched = (
        "servers:\n  - url: http://localhost:8115\n"
        "paths:\n"
        "  /api/v1/pay:\n"
        "    post:\n"
        "      parameters:\n"
        "        - name: Idempotency-Key\n          in: header\n          required: true\n"
        "          schema: {type: string}\n"
        "  /api/v1/pay/{id}:\n"
        "    get:\n      summary: read\n"
    )
    every, declared = spec_idempotency(spec_matched)
    case("only mutating operations enter the corpus", every, {"POST /api/v1/pay"})
    case("the declared header name is captured exactly",
         declared, {"POST /api/v1/pay": {"Idempotency-Key"}})
    # A $ref'd parameter must resolve — 240 parameters in this tree are written that way, and
    # treating one as 'not a header' drops the operation out of the corpus silently.
    spec_ref = (
        "servers:\n  - url: /api/v1\n"
        "paths:\n"
        "  /pay:\n"
        "    post:\n"
        "      parameters:\n        - $ref: '#/components/parameters/Idem'\n"
        "components:\n"
        "  parameters:\n"
        "    Idem:\n      name: X-Idempotency-Key\n      in: header\n      required: true\n"
        "      schema: {type: string}\n"
    )
    case("a $ref'd parameter is resolved and captured",
         spec_idempotency(spec_ref)[1], {"POST /api/v1/pay": {"X-Idempotency-Key"}})

    resource = """\
package x
@Path("/api/v1/pay")
class PayResource(private val store: IdempotencyStore) {
    @POST
    @Operation(summary = "Create")
    suspend fun create(
        request: CreateRequest,
        @HeaderParam("%s") idempotencyKey: String?,
    ): Response {
        return Response.ok().build()
    }

    @GET
    @Path("/{id}")
    suspend fun read(@PathParam("id") id: UUID): Response = Response.ok().build()
}
"""
    client = """\
package x
@Path("/api/v1/aml/cases")
@RegisterRestClient(configKey = "aml")
interface AmlServiceClient {
    @POST
    fun createCase(@HeaderParam("Idempotency-Key") idempotencyKey: String): Uni<Response>
}
"""

    def tree(tmp: Path, kotlin: dict[str, str]) -> Path:
        root = tmp / "openbank-fixture"
        pkg = root / "src/main/kotlin/com/openbank/fixture"
        pkg.mkdir(parents=True, exist_ok=True)
        for name, body in kotlin.items():
            (pkg / name).write_text(body, encoding="utf-8")
        return root

    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)

        # --- KNOWN-NEGATIVE: names agree -> the gate must find NOTHING ------------------
        good = tree(tmp / "good", {"PayResource.kt": resource % "Idempotency-Key"})
        bound = code_idempotency(good)
        case("the handler binding is found at all", sorted(bound), ["POST /api/v1/pay"])
        # THE HAZARD THIS COPY MUST GET RIGHT: without the cluster reset the class @Path leaks
        # into the first method and this route reads /api/v1/pay/api/v1/pay.
        case("the class @Path is not concatenated onto itself",
             bound.get("POST /api/v1/pay/api/v1/pay"), None)
        case("the bound header name is captured exactly",
             bound["POST /api/v1/pay"][0], "Idempotency-Key")
        found, subjects = findings_for("openbank-fixture", spec_matched, good)
        case("a matched contract and handler is clean", found, {})
        case("the corpus counts the mutating operation", subjects, 1)

        # --- KNOWN-POSITIVE 1: the two spellings disagree -> name-mismatch --------------
        bad = tree(tmp / "mismatch", {"PayResource.kt": resource % "X-Idempotency-Key"})
        found, _ = findings_for("openbank-fixture", spec_matched, bad)
        case("a header-name disagreement is a finding",
             sorted(found), ["openbank-fixture name-mismatch POST /api/v1/pay"])

        # --- KNOWN-POSITIVE 2: spec declares, handler binds nothing ---------------------
        naked = tree(tmp / "naked", {"PayResource.kt": resource.replace(
            '        @HeaderParam("%s") idempotencyKey: String?,\n', "")})
        found, _ = findings_for("openbank-fixture", spec_matched, naked)
        case("a declared header no handler binds is a finding",
             sorted(found), ["openbank-fixture published-not-bound POST /api/v1/pay"])

        # --- KNOWN-POSITIVE 3: handler binds, spec silent ------------------------------
        silent_spec = (
            "servers:\n  - url: http://localhost:8115\n"
            "paths:\n  /api/v1/pay:\n    post:\n      summary: create\n"
        )
        found, _ = findings_for("openbank-fixture", silent_spec, good)
        case("a bound header the contract omits is a finding",
             sorted(found), ["openbank-fixture bound-not-published POST /api/v1/pay"])

        # --- the exclusion that halves a naive grep ------------------------------------
        stub = tree(tmp / "client", {"AmlServiceClient.kt": client})
        case("an outbound @RegisterRestClient stub is not an endpoint",
             code_idempotency(stub), {})
        # ...and it must not be excluded by being a client-shaped FILE NAME alone: the real
        # resource above still registers when both live in one module.
        both = tree(tmp / "both", {"PayResource.kt": resource % "Idempotency-Key",
                                   "AmlServiceClient.kt": client})
        case("a real resource still registers alongside a client stub",
             sorted(code_idempotency(both)), ["POST /api/v1/pay"])

        # --- the baseline must fail in BOTH directions ---------------------------------
        bl = tmp / "baseline.txt"
        bl.write_text(BASELINE_HEADER + "openbank-fixture name-mismatch POST /api/v1/pay\n")
        entries = load_baseline(bl)
        case("a comment-only line is not an entry",
             entries, {"openbank-fixture name-mismatch POST /api/v1/pay"})
        found, _ = findings_for("openbank-fixture", spec_matched, bad)
        case("a baselined finding is not NEW", sorted(k for k in found if k not in entries), [])
        found_clean, _ = findings_for("openbank-fixture", spec_matched, good)
        case("a baseline entry with no finding is STALE",
             sorted(entries - set(found_clean)),
             ["openbank-fixture name-mismatch POST /api/v1/pay"])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: idempotency contract parity is falsifiable "
          "(24 cases: 4 known-positive trees, 1 known-negative, both baseline directions)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--service", help="check a single openbank-* module (default: the whole fleet)")
    ap.add_argument("--baseline", help="file of declared, already-known findings")
    ap.add_argument("--write-baseline", help="rewrite the baseline file from the current findings")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    level = "error" if args.enforce else "warning"
    specs = sorted(Path(".").glob("openbank-*/src/main/resources/openapi.yaml"))
    if args.service:
        specs = [s for s in specs if s.parts[0] == args.service]
        if not specs:
            print(f"::error::idempotency-contract-parity: no openapi.yaml for {args.service!r}")
            return 1

    found: dict[str, str] = {}
    subjects = 0
    compared = 0
    for spec_path in specs:
        service_dir = Path(spec_path.parts[0])
        try:
            spec_text = spec_path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            print(f"::error::idempotency-contract-parity: cannot read {spec_path}: {exc}")
            return 1
        service_found, mutating = findings_for(service_dir.name, spec_text, service_dir)
        found.update(service_found)
        subjects += mutating
        compared += 1

    if args.write_baseline:
        Path(args.write_baseline).write_text(
            BASELINE_HEADER + "".join(f"{k}\n" for k in sorted(found)), encoding="utf-8")
        print(f"check-idempotency-contract-parity: wrote {len(found)} entr(ies) "
              f"to {args.write_baseline}")
        return 0

    baseline = load_baseline(Path(args.baseline)) if args.baseline else set()
    scoped = {e for e in baseline if not args.service or e.split(" ", 1)[0] == args.service}
    new = sorted(k for k in found if k not in baseline)
    stale = sorted(scoped - set(found))

    for key in new:
        print(f"::{level}::idempotency-contract-parity: {found[key]}")
    for key in sorted(k for k in found if k in baseline):
        print(f"::notice::idempotency-contract-parity: declared backlog — {found[key]}")
    for key in stale:
        print(f"::{level}::idempotency-contract-parity: STALE baseline entry — {key!r} is no "
              f"longer a finding. Delete the line from {args.baseline}; the declared backlog "
              f"must not outlive the debt.")

    # The corpus is DERIVED (every mutating operation in every published spec), never a literal —
    # a hard-coded number keeps reporting a full fleet after half the specs are deleted (#4339).
    print(f"SUBJECTS={subjects}")
    print(f"check-idempotency-contract-parity: {compared} spec(s) compared, {subjects} mutating "
          f"operation(s) examined, {len(found)} finding(s), {len(baseline)} declared in the "
          f"baseline, {len(new)} NEW, {len(stale)} stale.")
    if (new or stale) and not args.enforce:
        print("check-idempotency-contract-parity: advisory — no --enforce, so this is a warning.")
    return 1 if ((new or stale) and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
