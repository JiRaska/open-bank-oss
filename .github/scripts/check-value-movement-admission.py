#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every module that can move value on an instruction binds the payment admission chain.

WHY THIS EXISTS (ADR-0377 D2, building on ADR-0374)
---------------------------------------------------
The admission chain (`PaymentAdmissionChain`, openbank-libs-fincrime) is where a payment is
screened before it books: authority, limits, sanctions, FRAUD. A module that books value without
binding it is a path around every one of those steps, and nothing made such a path visible: it
compiles, its tests pass, and it does not appear in any list of "payment rails" because it was
never thought of as one. The decision is that every module which can move value on a customer's
or third party's instruction binds the chain, with its FRAUD step bound to a real check.

THE SCOPE IS DERIVED, NEVER LISTED
----------------------------------
A module (top-level `openbank-*` directory) is value-moving when its `src/main/kotlin`, with
comments stripped, reaches a value SINK by any of three signatures:

  S-TXN-API  declares `interface X : TransactionsApi` AND calls a booking method
             (`.initiate(` / `.reverse(` / `.initiateTransaction(` / `.reverseTransaction(`) in a
             file that names that interface, `TransactionsApi` or the `TransactionClient`
             wrapper. An inheritor that only reads (`.list(`) is not selected, and neither is an
             unrelated `.reverse(` (a StringBuilder, a domain aggregate) elsewhere in the module.
  S-REST     declares an outbound client `interface` with a `@POST` method whose class-level
             `@Path` joined with its method-level `@Path` is one of SINK_PATHS. A `class` with the
             same annotations is the provider's resource, not a client.
  S-URL      passes a string literal `"$<base>/api/v1/..."` that ends exactly in a sink path to a
             call other than `get(`/`head(` (the hand-rolled HTTP idiom).

The sinks themselves (PROVIDERS) and shared libraries / generated clients are excluded: they are
what is being reached, not what reaches.

THE REGISTRY CLASSIFIES; IT DOES NOT SCOPE
------------------------------------------
`value-movement-admission-registry.txt` says, for each DERIVED module, whether value moves on an
instruction (`instructed`: must bind the chain) or is the bank's own bookkeeping with no
instructing party (`bank-own`: exempt, reason required). It is checked in both directions: a
derived module with no line fails, and a line for a module that is no longer derived fails. So a
short registry cannot read as full coverage, and an exemption cannot outlive its subject.

An `instructed` module that is not bound yet carries a pending state, and those are shrink-only:
`pending-chain` fails the day the module binds the chain, `pending-fraud` fails the day its FRAUD
step becomes a real check, and a new instructed module cannot be added as "fully bound" unless it
is. Bound means: references `PaymentAdmissionChain.Builder` (or `.resolve(`), has
`.check(AdmissionStep.FRAUD`, and has no `.unbound(AdmissionStep.FRAUD`.

WHAT IT DOES NOT COVER (say so rather than read as covered)
-----------------------------------------------------------
* A `@Path` whose value is a constant reference rather than a string literal is not resolved.
* An S-URL literal that carries a query string or is assembled by concatenation is not matched.
* Binding is detected per MODULE, not per call site: a module with two booking paths, one bound,
  reads as bound. The chain's own tests and the provider-side enforcement are the other layers.
* Value moved by an event another service consumes (no REST call to a sink) is out of scope.

EXIT CODES
----------
0 clean; 1 a finding; 2 could not evaluate (malformed registry, or nothing derived at all — an
empty scope prints SUBJECTS=UNRESOLVED and is never a pass).

Usage: check-value-movement-admission.py [--root .] [--list] [--print-registry] [--self-test]
"""
from __future__ import annotations

import argparse
import contextlib
import io
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

REPO = Path(__file__).resolve().parents[2]
REGISTRY = Path(__file__).resolve().with_name("value-movement-admission-registry.txt")

# The modules that ARE the sinks. Reaching them is the signature; being them is not.
PROVIDERS = {"openbank-transaction-service", "openbank-ledger-service", "openbank-balance-service"}
# Shared libraries and generated clients carry the sink types on behalf of their consumers.
EXCLUDED_PREFIXES = ("openbank-libs", "openbank-client-")

# `{}` is any single path parameter.
SINK_PATHS = (
    "/api/v1/transactions",
    "/api/v1/transactions/{}/reverse",
    "/api/v1/transactions/merge-sweep",
    "/api/v1/journals",
    "/api/v1/journals/synthetic",
    "/api/v1/journals/{}/reverse",
    "/api/v1/ledger/pension-accounting/management-fees",
    "/api/v1/balances/{}/debit",
    "/api/v1/balances/{}/credit",
    "/api/v1/clearing/credit-transfers",
    "/api/v1/clearing/fi-credit-transfers/{}",
)

CLASSES = ("instructed", "bank-own")
STATES = ("pending-chain", "pending-fraud")
MIN_REASON = 20

_REST_PARAM = r"\{[^{}/]+\}"
_URL_PARAM = r"(?:\$\{[^{}]*\}|\$[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*|\{[^{}/]+\})"


def _sink_regex(param: str) -> str:
    return "(?:" + "|".join(re.escape(p).replace(r"\{\}", param) for p in SINK_PATHS) + ")"


SINK_REST = re.compile(_sink_regex(_REST_PARAM) + r"\Z")
SINK_URL = re.compile(
    r"\A(?:\$\{[^{}]*\}|\$[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)" + _sink_regex(_URL_PARAM) + r"/?\Z",
)

_NOT_DECL = r"(?:(?!\b(?:class|interface|object|fun)\b)[^{}])*?"
TXN_API_IFACE = re.compile(r"\binterface\s+(\w+)\s*(?:<[^<>{}]*>)?\s*:" + _NOT_DECL + r"\bTransactionsApi\b")
BOOKING_CALL = re.compile(r"\.(?:initiate|reverse|initiateTransaction|reverseTransaction)\s*\(")
IFACE_DECL = re.compile(r"\binterface\s+(\w+)")
DECL_KEYWORD = re.compile(r"\b(?:class|interface|object|fun)\b")
FUN_KEYWORD = re.compile(r"\bfun\b")
PATH_ANNOTATION = re.compile(r'@Path\s*\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)')
POST_ANNOTATION = re.compile(r"@POST\b")
HEAD_BOUNDARY = re.compile(r"[{};]|^\s*(?:import|package)\b[^\n]*$", re.MULTILINE)
READ_CALLEE = re.compile(r"\b(?:get|head)\s*\(\s*\Z", re.IGNORECASE)

CHAIN_BOUND = re.compile(r"\bPaymentAdmissionChain\s*\.\s*(?:Builder\b|resolve\s*\()")
FRAUD_CHECK = re.compile(r"\.\s*check\s*\(\s*(?:AdmissionStep\s*\.\s*)?FRAUD\b")
FRAUD_UNBOUND = re.compile(r"\.\s*unbound\s*\(\s*(?:AdmissionStep\s*\.\s*)?FRAUD\b")

# A file without any of these cannot match after stripping (stripping only removes text).
PREFILTER = ("TransactionsApi", "@Path", "/api/v1/", "initiate", "reverse", "PaymentAdmissionChain", "FRAUD")

_TOKEN = re.compile(r'//|/\*|"""|"|\'')
_BLOCK_EDGE = re.compile(r"/\*|\*/")
_CHAR_LITERAL = re.compile(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'")
_NON_NEWLINE = re.compile(r"[^\n]")


def _blank(text: str) -> str:
    return _NON_NEWLINE.sub(" ", text)


def _string_end(src: str, start: int) -> int:
    """Index just past the closing quote of the regular string opening at `start`.

    Follows `${ ... }` templates, which may contain nested string literals. A string cannot span
    lines in Kotlin, so an unterminated one ends at the newline rather than swallowing the file.
    """
    i, n = start + 1, len(src)
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
        elif c == '"':
            return i + 1
        elif c == "\n":
            return i
        elif c == "$" and src.startswith("{", i + 1):
            depth, i = 1, i + 2
            while i < n and depth:
                c = src[i]
                if c == '"':
                    i = _string_end(src, i)
                    continue
                if c == "\n":
                    return i
                depth += (c == "{") - (c == "}")
                i += 1
        else:
            i += 1
    return n


def strip_kotlin(src: str) -> tuple[str, str, list[tuple[int, str]]]:
    """(code, mask, literals) — all the same length as `src`, so offsets and lines agree.

    `code` has comments blanked and strings intact; `mask` has string contents blanked too, for
    structural matching (a `}` inside `@Path("/x/{id}")` is not a closing brace). `literals` are
    (offset, content) of every string literal outside a comment. Block comments NEST in Kotlin,
    and `//` inside a string literal (every URL) is not a comment.
    """
    code: list[str] = []
    mask: list[str] = []
    literals: list[tuple[int, str]] = []
    i, n = 0, len(src)
    while i < n:
        m = _TOKEN.search(src, i)
        if m is None:
            code.append(src[i:])
            mask.append(src[i:])
            break
        s = m.start()
        code.append(src[i:s])
        mask.append(src[i:s])
        tok = m.group()
        if tok == "//":
            e = src.find("\n", s)
            e = n if e == -1 else e
            code.append(" " * (e - s))
            mask.append(" " * (e - s))
        elif tok == "/*":
            depth, e = 1, s + 2
            while depth:
                edge = _BLOCK_EDGE.search(src, e)
                if edge is None:
                    e = n
                    break
                depth += 1 if edge.group() == "/*" else -1
                e = edge.end()
            code.append(_blank(src[s:e]))
            mask.append(_blank(src[s:e]))
        elif tok == '"""':
            close = src.find('"""', s + 3)
            if close == -1:
                e, content = n, src[s + 3:]
            else:
                e = close + 3
                while e < n and src[e] == '"':  # `""""` closes with the LAST three quotes
                    e += 1
                content = src[s + 3:e - 3]
            literals.append((s, content))
            code.append(src[s:e])
            mask.append(_blank(src[s:e]))
        elif tok == '"':
            e = _string_end(src, s)
            closed = e > s + 1 and src[e - 1] == '"'
            literals.append((s, src[s + 1:e - 1] if closed else src[s + 1:e]))
            code.append(src[s:e])
            mask.append('"' + _blank(src[s + 1:e - 1]) + '"' if closed else _blank(src[s:e]))
        else:  # a char literal, or a stray quote
            ch = _CHAR_LITERAL.match(src, s)
            e = ch.end() if ch else s + 1
            code.append(src[s:e])
            mask.append(_blank(src[s:e]) if ch else src[s:e])
        i = e
    return "".join(code), "".join(mask), literals


def _match_close(mask: str, open_at: int, opener: str, closer: str) -> int:
    """Index of the bracket closing the one at `open_at`, or len(mask) when unbalanced."""
    depth = 0
    for i in range(open_at, len(mask)):
        c = mask[i]
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i
    return len(mask)


def _join_path(*parts: str) -> str:
    segments = [seg for part in parts for seg in part.split("/") if seg]
    return "/" + "/".join(segments)


def rest_sinks(code: str, mask: str) -> list[tuple[int, str]]:
    """(offset, resolved path) of every @POST method on an `interface` that resolves to a sink."""
    hits: list[tuple[int, str]] = []
    for decl in IFACE_DECL.finditer(mask):
        # Annotations of the interface: back to the previous declaration's end, or the imports.
        head_start = 0
        for b in HEAD_BOUNDARY.finditer(mask, 0, decl.start()):
            head_start = b.end()
        class_path = PATH_ANNOTATION.search(code, head_start, decl.start())
        brace = mask.find("{", decl.end())
        if brace == -1 or DECL_KEYWORD.search(mask, decl.end(), brace):
            continue  # no body: the next `{` belongs to another declaration
        body_end = _match_close(mask, brace, "{", "}")
        # Depth-1 `fun` keywords of the body; the annotations of each sit between the previous
        # method's parameter list and its own keyword.
        depth, funs = 0, []
        nested = [(m.start(), m.group()) for m in re.finditer(r"[{}]", mask[brace:body_end + 1])]
        cuts = iter(nested)
        nxt = next(cuts, None)
        for f in FUN_KEYWORD.finditer(mask, brace, body_end):
            while nxt is not None and brace + nxt[0] < f.start():
                depth += 1 if nxt[1] == "{" else -1
                nxt = next(cuts, None)
            if depth == 1:
                funs.append(f.start())
        region_start = brace + 1
        for f_at in funs:
            if POST_ANNOTATION.search(mask, region_start, f_at):
                method_path = PATH_ANNOTATION.search(code, region_start, f_at)
                resolved = _join_path(class_path.group(1) if class_path else "", method_path.group(1) if method_path else "")
                if SINK_REST.match(resolved):
                    hits.append((f_at, resolved))
            params = mask.find("(", f_at, body_end)
            region_start = _match_close(mask, params, "(", ")") + 1 if params != -1 else f_at + 3
    return hits


def url_sinks(mask: str, literals: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """(offset, literal) of every `"$base/api/v1/<sink>"` literal not passed to a get(/head( call."""
    hits = []
    for offset, content in literals:
        if SINK_URL.match(content) and not READ_CALLEE.search(mask, max(0, offset - 80), offset):
            hits.append((offset, content))
    return hits


@dataclass
class Module:
    name: str
    signatures: dict[str, str] = field(default_factory=dict)  # signature -> "file:line  detail"
    chain_bound: bool = False
    fraud_check: bool = False
    fraud_unbound: bool = False
    files: int = 0

    @property
    def fraud_bound(self) -> bool:
        return self.chain_bound and self.fraud_check and not self.fraud_unbound

    def evidence(self) -> str:
        return "; ".join(f"{sig} {where}" for sig, where in sorted(self.signatures.items()))


def _line(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def scan_module(root: Path, module_dir: Path) -> Module:
    mod = Module(module_dir.name)
    inheritors: list[tuple[str, str]] = []           # (interface name, file:line)
    booking: list[tuple[str, int, str]] = []         # (file, line, code of the file)
    for kt in gatelib.rglob(module_dir / "src" / "main" / "kotlin", "*.kt"):
        raw = gatelib.read_text(kt, errors="replace")
        mod.files += 1
        if not any(word in raw for word in PREFILTER):
            continue
        code, mask, literals = strip_kotlin(raw)
        rel = kt.relative_to(root).as_posix()
        for m in TXN_API_IFACE.finditer(mask):
            inheritors.append((m.group(1), f"{rel}:{_line(mask, m.start())}"))
        call = BOOKING_CALL.search(mask)
        if call:
            booking.append((rel, _line(mask, call.start()), mask))
        if "S-REST" not in mod.signatures:
            for offset, path in rest_sinks(code, mask)[:1]:
                mod.signatures["S-REST"] = f"{rel}:{_line(mask, offset)} (@POST {path})"
        if "S-URL" not in mod.signatures:
            for offset, content in url_sinks(mask, literals)[:1]:
                mod.signatures["S-URL"] = f'{rel}:{_line(mask, offset)} ("{content}")'
        mod.chain_bound = mod.chain_bound or bool(CHAIN_BOUND.search(mask))
        mod.fraud_check = mod.fraud_check or bool(FRAUD_CHECK.search(mask))
        mod.fraud_unbound = mod.fraud_unbound or bool(FRAUD_UNBOUND.search(mask))
    if inheritors:
        names = "|".join(re.escape(n) for n, _ in inheritors)
        receiver = re.compile(rf"\b(?:TransactionsApi|TransactionClient|{names})\b")
        for rel, line, mask in booking:
            if receiver.search(mask):
                mod.signatures["S-TXN-API"] = f"{rel}:{line} (booking call; {inheritors[0][0]} at {inheritors[0][1]})"
                break
    return mod


def derive(root: Path) -> tuple[dict[str, Module], int]:
    """Value-moving modules by name, and how many .kt files were read to find them."""
    derived: dict[str, Module] = {}
    files = 0
    if not root.is_dir():
        return derived, files
    for module_dir in sorted(p for p in root.iterdir() if p.is_dir() and p.name.startswith("openbank-")):
        if module_dir.name in PROVIDERS or module_dir.name.startswith(EXCLUDED_PREFIXES):
            continue
        if not (module_dir / "src" / "main" / "kotlin").is_dir():
            continue
        mod = scan_module(root, module_dir)
        files += mod.files
        if mod.signatures:
            derived[mod.name] = mod
    return derived, files


@dataclass
class Entry:
    module: str
    cls: str
    state: str | None
    reason: str
    lineno: int


def registry_line(module: str, cls: str, state: str | None, reason: str) -> str:
    return f"{module}  {cls}" + (f"  {state}" if state else "") + f"  # {reason}"


def load_registry(path: Path) -> tuple[dict[str, Entry], list[str]]:
    """(entries, problems). Any problem means the registry cannot be evaluated (exit 2)."""
    entries: dict[str, Entry] = {}
    problems: list[str] = []
    if not path.exists():
        return entries, [f"{path.name}: registry file is missing"]
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        left, _, reason = line.partition("#")
        tokens, reason = left.split(), reason.strip()
        where = f"{path.name}:{lineno}"
        if len(tokens) not in (2, 3):
            problems.append(f"{where}: malformed line (want `<module>  <class>[  <pending-state>]  # <reason>`): {line}")
            continue
        module, cls, state = tokens[0], tokens[1], tokens[2] if len(tokens) == 3 else None
        if cls not in CLASSES:
            problems.append(f"{where}: unknown class `{cls}` for {module} (want one of {', '.join(CLASSES)})")
            continue
        if state is not None and cls != "instructed":
            problems.append(f"{where}: {module} is `{cls}` and cannot carry a pending state (`{state}`)")
            continue
        if state is not None and state not in STATES:
            problems.append(f"{where}: unknown pending state `{state}` for {module} (want one of {', '.join(STATES)})")
            continue
        if cls == "bank-own" and len(reason) < MIN_REASON:
            problems.append(
                f"{where}: {module} is `bank-own` without a reason of at least {MIN_REASON} characters — "
                f"an exemption from the admission chain says what the module books and why nobody instructs it",
            )
            continue
        if module in entries:
            problems.append(f"{where}: duplicate module {module} (first at line {entries[module].lineno})")
            continue
        entries[module] = Entry(module, cls, state, reason, lineno)
    return entries, problems


def evaluate(root: Path, registry: Path) -> int:
    derived, files = derive(root)
    if not derived:
        gatelib.subjects_unresolved(
            f"no value-moving module derived under {root} ({files} .kt files read) — wrong root, or a sink was renamed",
        )
        print(
            "::error::value-movement-admission: the scan derived ZERO value-moving modules. That is not a clean "
            "result: either --root is not the repository, or the sink signatures no longer match the tree.",
            file=sys.stderr,
        )
        return 2
    gatelib.subjects(len(derived), "value-moving modules derived from src/main")
    entries, problems = load_registry(registry)
    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        print(f"\nCould not evaluate: {len(problems)} registry problem(s) in {registry.name}.", file=sys.stderr)
        return 2

    findings: list[str] = []
    for name, mod in sorted(derived.items()):
        entry = entries.get(name)
        if entry is None:
            findings.append(
                f"{name}: UNCLASSIFIED value-moving module [{mod.evidence()}]. Add to {registry.name}: "
                f"`{registry_line(name, 'instructed', 'pending-chain', '<what it books, on whose instruction>')}` "
                f"— or `bank-own` with the reason nobody instructs it.",
            )
            continue
        if entry.cls != "instructed":
            continue
        current = registry_line(name, entry.cls, entry.state, entry.reason)
        if entry.state is None and not mod.fraud_bound:
            want = "pending-fraud" if mod.chain_bound else "pending-chain"
            why = (
                "binds the chain but its FRAUD step is not a real check" if mod.chain_bound
                else "does not bind PaymentAdmissionChain"
            )
            findings.append(
                f"{name}: declared fully bound but {why} [{mod.evidence()}]. Bind it, or record the debt: "
                f"`{registry_line(name, 'instructed', want, entry.reason)}`",
            )
        elif entry.state == "pending-chain" and mod.chain_bound:
            want = None if mod.fraud_bound else "pending-fraud"
            findings.append(
                f"{name}: STALE `pending-chain` — the module now binds PaymentAdmissionChain. Replace "
                f"`{current}` with `{registry_line(name, 'instructed', want, entry.reason)}`",
            )
        elif entry.state == "pending-fraud" and mod.fraud_bound:
            findings.append(
                f"{name}: STALE `pending-fraud` — the FRAUD step is now a real check. Replace "
                f"`{current}` with `{registry_line(name, 'instructed', None, entry.reason)}`",
            )
        elif entry.state == "pending-fraud" and not mod.chain_bound:
            findings.append(
                f"{name}: `pending-fraud` but the module does not bind PaymentAdmissionChain at all "
                f"[{mod.evidence()}]. Replace `{current}` with "
                f"`{registry_line(name, 'instructed', 'pending-chain', entry.reason)}`",
            )
    for name, entry in sorted(entries.items()):
        if name not in derived:
            findings.append(
                f"{name}: STALE registry entry ({entry.cls}) — no value-sink signature derives from its src/main any "
                f"more. Remove line {entry.lineno} of {registry.name}: "
                f"`{registry_line(name, entry.cls, entry.state, entry.reason)}`",
            )
    if findings:
        for f in findings:
            print(f"::error::{f}", file=sys.stderr)
        print(f"\n{len(findings)} finding(s). The scope is derived from code; {registry.name} only classifies it.", file=sys.stderr)
        return 1

    def count(cls: str, state: str | None) -> int:
        return sum(1 for e in entries.values() if e.cls == cls and e.state == state)

    print(
        f"OK: {len(derived)} value-moving modules ({files} .kt files read), all classified, none stale — "
        f"instructed: {count('instructed', None)} fully bound, {count('instructed', 'pending-chain')} pending-chain, "
        f"{count('instructed', 'pending-fraud')} pending-fraud; bank-own: {count('bank-own', None)}.",
    )
    return 0


# --------------------------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------------------------
_TXN_IFACE = "interface TxClient :\n    TransactionsApi\n"
_BOUND_CHAIN = (
    "val chain = PaymentAdmissionChain.Builder<Ctx>(budget, UnavailablePolicy.HOLD)\n"
    "    .unbound(AdmissionStep.AUTHORITY, why)\n"
    "    .check(\n        AdmissionStep.FRAUD\n    ) { fraud.score(it) }\n"
)
_UNBOUND_FRAUD = (
    "val chain = PaymentAdmissionChain.Builder<Ctx>(budget, UnavailablePolicy.HOLD)\n"
    "    // .check(AdmissionStep.FRAUD) { fraud.score(it) }  <- prose, not code\n"
    "    .unbound(AdmissionStep.FRAUD, why)\n"
)
_COMMENT_ONLY_CHECK = (
    "/* the rail will call .check(AdmissionStep.FRAUD) { } once wired */\n"
    "val chain = PaymentAdmissionChain.Builder<Ctx>(budget, UnavailablePolicy.HOLD)\n"
)

_FIXTURE = {
    "openbank-alpha-service": {  # S-TXN-API, fully bound
        "Client.kt": "import x.TransactionsApi\n@RegisterRestClient\n" + _TXN_IFACE,
        "Use.kt": "class Use(val c: TxClient) { fun go() = TransactionClient(c).initiate(req) }\n" + _BOUND_CHAIN,
    },
    "openbank-reader-service": {  # inherits, only lists; an unrelated .reverse( elsewhere
        "Client.kt": "interface ReadClient : TransactionsApi\n",
        "Use.kt": "class Use(val c: ReadClient) { fun go() = TransactionClient(c).list(q) }\n",
        "Text.kt": "fun flip(sb: StringBuilder) = sb.reverse().toString()\n",
    },
    "openbank-beta-service": {  # S-REST, class + method @Path split, @Path before @POST
        "Ledger.kt": (
            '@RegisterRestClient(configKey = "ledger-api")\n@Path("/api/v1/journals")\n'
            "interface LedgerClient {\n"
            '    @GET\n    @Path("/{id}")\n    fun get(@PathParam("id") id: UUID): Uni<Response>\n\n'
            '    @Path("/{id}/reverse")\n    @POST\n    fun reverse(@PathParam("id") id: UUID): Uni<Response>\n}\n'
        ),
    },
    "openbank-provider-like-service": {  # a `class` resource and a @GET client: neither is a client POST
        "Resource.kt": '@Path("/api/v1/transactions")\nclass TxResource {\n    @POST\n    fun create(r: Req): Response = TODO()\n}\n',
        "Read.kt": '@Path("/api/v1/journals")\ninterface ReadLedger {\n    @GET\n    fun list(): Uni<Response>\n}\n',
    },
    "openbank-gamma-edge": {  # S-URL
        "Edge.kt": 'class Edge {\n    fun pay() = upstream.post("$transactionServiceUrl/api/v1/transactions", body)\n}\n',
    },
    "openbank-getter-edge": {  # the same literal passed to get( is a read
        "Edge.kt": 'class Edge {\n    fun list() = upstream.get("${cfg.txUrl}/api/v1/transactions", party)\n}\n',
    },
    "openbank-comment-service": {  # every signature, only inside comments
        "Notes.kt": (
            '// upstream.post("$transactionServiceUrl/api/v1/transactions", body)\n'
            "/* outer /* interface C : TransactionsApi */ still a comment:\n"
            '   @Path("/api/v1/journals") interface L { @POST fun post(): R }\n'
            "   client.initiate(req) */\n"
            'val docs = "see https://example.invalid/api // not a comment"\n'
            "class Notes\n"
        ),
    },
    "openbank-delta-service": {  # S-URL, chain bound, FRAUD unbound (the check only in a comment)
        "Pay.kt": 'fun pay() = http.post("$ledgerUrl/api/v1/journals", b)\n' + _UNBOUND_FRAUD,
    },
    "openbank-epsilon-service": {  # S-URL, chain bound, .check(FRAUD only in a block comment
        "Pay.kt": 'fun pay() = http.post("$ledgerUrl/api/v1/journals", b)\n' + _COMMENT_ONLY_CHECK,
    },
    "openbank-zeta-service": {  # S-URL, no chain at all
        "Pay.kt": 'fun pay() = http.post("$balanceUrl/api/v1/balances/$accountId/debit", b)\n',
    },
    "openbank-transaction-service": {  # a provider: excluded even though it would match
        "Self.kt": 'fun loop() = http.post("$selfUrl/api/v1/transactions", b)\n',
    },
    "openbank-libs-thing": {
        "Lib.kt": 'fun lib() = http.post("$u/api/v1/transactions", b)\n',
    },
}

_GOOD_REGISTRY = (
    "# header\n\n"
    "openbank-alpha-service  instructed  # customer credit transfers\n"
    "openbank-beta-service  bank-own  # reverses the bank's own accrual journals, no instructing party\n"
    "openbank-gamma-edge  instructed  pending-chain  # own-account transfers posted directly\n"
    "openbank-delta-service  instructed  pending-fraud  # binds the chain, FRAUD still unbound\n"
    "openbank-epsilon-service  instructed  pending-fraud  # binds the chain, no FRAUD step yet\n"
    "openbank-zeta-service  instructed  pending-chain  # debits on a mandate, no chain\n"
)


def _build_fixture(root: Path) -> None:
    for module, files in _FIXTURE.items():
        base = root / module / "src" / "main" / "kotlin"
        base.mkdir(parents=True)
        for name, text in files.items():
            (base / name).write_text(text, encoding="utf-8")
    # src/test and build/ are never scanned.
    stray = root / "openbank-test-only-service" / "src" / "test" / "kotlin"
    stray.mkdir(parents=True)
    (stray / "T.kt").write_text('fun t() = http.post("$u/api/v1/transactions", b)\n', encoding="utf-8")


def _run(root: Path, registry_text: str | None) -> tuple[int, str]:
    reg = root / "registry.txt"
    if registry_text is None:
        reg.unlink(missing_ok=True)
    else:
        reg.write_text(registry_text, encoding="utf-8")
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
        rc = evaluate(root, reg)
    return rc, buf.getvalue()


def self_test() -> int:
    """Prove the gate can fail: each bad shape is rejected, each good control is accepted."""
    checks: dict[str, bool] = {}
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "tree"
        root.mkdir()
        _build_fixture(root)
        derived, _ = derive(root)

        def sigs(module: str) -> set[str]:
            return set(derived[module].signatures) if module in derived else set()

        checks["S-TXN-API: inheritor with a booking call (interface split over two lines) is selected"] = (
            sigs("openbank-alpha-service") == {"S-TXN-API"}
        )
        checks["S-TXN-API: inheritor that only calls .list( (plus an unrelated sb.reverse()) is NOT selected"] = (
            "openbank-reader-service" not in derived
        )
        checks["S-REST: class @Path + method @Path split, @Path before @POST, is selected"] = (
            sigs("openbank-beta-service") == {"S-REST"}
            and "/api/v1/journals/{id}/reverse" in derived["openbank-beta-service"].signatures["S-REST"]
        )
        checks["S-REST: @POST on a `class` (provider resource) and @GET on a sink path are NOT selected"] = (
            "openbank-provider-like-service" not in derived
        )
        checks["S-URL: \"$base/api/v1/transactions\" passed to post( is selected"] = sigs("openbank-gamma-edge") == {"S-URL"}
        checks["S-URL: the same literal passed to get( is NOT selected"] = "openbank-getter-edge" not in derived
        checks["signatures only inside // and nested /* /* */ */ comments are NOT selected"] = (
            "openbank-comment-service" not in derived
        )
        _, _, lits = strip_kotlin(_FIXTURE["openbank-comment-service"]["Notes.kt"])
        checks["`//` inside a string literal is not treated as a comment"] = (
            [c for _, c in lits] == ["see https://example.invalid/api // not a comment"]
        )
        checks["providers, openbank-libs* and src/test are out of scope"] = not (
            {"openbank-transaction-service", "openbank-libs-thing", "openbank-test-only-service"} & set(derived)
        )
        checks["exactly the six expected modules derive"] = set(derived) == {
            "openbank-alpha-service", "openbank-beta-service", "openbank-gamma-edge",
            "openbank-delta-service", "openbank-epsilon-service", "openbank-zeta-service",
        }
        checks["fully bound: Builder + .check(AdmissionStep.FRAUD split over lines"] = (
            "openbank-alpha-service" in derived and derived["openbank-alpha-service"].fraud_bound
        )
        checks["chain-bound with .unbound(AdmissionStep.FRAUD is NOT fraud-bound"] = (
            derived["openbank-delta-service"].chain_bound and not derived["openbank-delta-service"].fraud_bound
        )
        checks[".check(AdmissionStep.FRAUD only inside a comment does NOT count"] = (
            derived["openbank-epsilon-service"].chain_bound and not derived["openbank-epsilon-service"].fraud_check
        )

        rc, out = _run(root, _GOOD_REGISTRY)
        checks["control: a correct registry passes, with SUBJECTS=6"] = rc == 0 and "SUBJECTS=6" in out and "OK:" in out

        def variant(old: str, new: str) -> str:
            assert old in _GOOD_REGISTRY, old
            return _GOOD_REGISTRY.replace(old, new)

        rc, out = _run(root, variant("openbank-gamma-edge  instructed  pending-chain  # own-account transfers posted directly\n", ""))
        checks["unclassified derived module fails (exit 1) naming the module"] = (
            rc == 1 and "openbank-gamma-edge: UNCLASSIFIED" in out
        )
        rc, out = _run(root, _GOOD_REGISTRY + "openbank-gone-service  bank-own  # books nothing any more, kept by mistake\n")
        checks["stale registry entry fails (exit 1) naming the module"] = (
            rc == 1 and "openbank-gone-service: STALE registry entry" in out
        )
        rc, out = _run(root, variant("openbank-zeta-service  instructed  pending-chain", "openbank-zeta-service  instructed"))
        checks["instructed + no state + unbound fails, proposing pending-chain"] = (
            rc == 1 and "openbank-zeta-service: declared fully bound" in out and "pending-chain" in out
        )
        rc, out = _run(root, variant("openbank-delta-service  instructed  pending-fraud", "openbank-delta-service  instructed"))
        checks["instructed + no state + chain-bound but FRAUD unbound fails, proposing pending-fraud"] = (
            rc == 1 and "openbank-delta-service: declared fully bound" in out and "instructed  pending-fraud" in out
        )
        rc, out = _run(root, variant("openbank-delta-service  instructed  pending-fraud", "openbank-delta-service  instructed  pending-chain"))
        checks["pending-chain on a chain-bound module fails as stale"] = (
            rc == 1 and "openbank-delta-service: STALE `pending-chain`" in out
        )
        rc, out = _run(root, variant("openbank-alpha-service  instructed", "openbank-alpha-service  instructed  pending-fraud"))
        checks["pending-fraud on a fraud-bound module fails as stale"] = (
            rc == 1 and "openbank-alpha-service: STALE `pending-fraud`" in out
        )
        rc, out = _run(root, variant("openbank-zeta-service  instructed  pending-chain", "openbank-zeta-service  instructed  pending-fraud"))
        checks["pending-fraud on a module with no chain fails as the wrong state"] = (
            rc == 1 and "openbank-zeta-service: `pending-fraud` but the module does not bind" in out
        )
        rc, out = _run(root, variant("  # reverses the bank's own accrual journals, no instructing party", ""))
        checks["bank-own without a reason -> exit 2"] = rc == 2 and "without a reason" in out
        rc, out = _run(root, variant("  # reverses the bank's own accrual journals, no instructing party", "  # internal"))
        checks["bank-own with a too-short reason -> exit 2"] = rc == 2 and "without a reason" in out
        rc, out = _run(root, _GOOD_REGISTRY + "openbank-alpha-service  instructed  # again\n")
        checks["duplicate module -> exit 2"] = rc == 2 and "duplicate module openbank-alpha-service" in out
        rc, out = _run(root, variant("openbank-alpha-service  instructed", "openbank-alpha-service  customer"))
        checks["unknown class -> exit 2"] = rc == 2 and "unknown class `customer`" in out
        rc, out = _run(root, variant("instructed  pending-chain  # own-account", "instructed  pending-review  # own-account"))
        checks["unknown pending state -> exit 2"] = rc == 2 and "unknown pending state `pending-review`" in out
        rc, out = _run(root, variant("openbank-beta-service  bank-own", "openbank-beta-service  bank-own  pending-chain"))
        checks["bank-own with a pending state -> exit 2"] = rc == 2 and "cannot carry a pending state" in out
        rc, out = _run(root, _GOOD_REGISTRY + "just-one-token\n")
        checks["malformed line -> exit 2"] = rc == 2 and "malformed line" in out
        rc, out = _run(root, None)
        checks["missing registry file -> exit 2"] = rc == 2 and "registry file is missing" in out

        empty = Path(tmp) / "empty"
        empty.mkdir()
        rc, out = _run(empty, _GOOD_REGISTRY)
        checks["empty tree -> SUBJECTS=UNRESOLVED and non-zero, never a pass"] = (
            rc != 0 and f"{gatelib.SUBJECTS_PREFIX}{gatelib.SUBJECTS_UNRESOLVED}" in out and "OK:" not in out
        )
    for name, ok in checks.items():
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
    return 1 if not all(checks.values()) else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--list", action="store_true", help="derived modules with their signatures and file:line")
    ap.add_argument("--print-registry", action="store_true", help="emit registry lines for unclassified modules")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = Path(args.root)
    if args.list or args.print_registry:
        derived, _ = derive(root)
        entries, problems = load_registry(REGISTRY)
        for p in problems:  # a listing over an unreadable registry must not hide that
            print(f"::error::{p}", file=sys.stderr)
        for name, mod in sorted(derived.items()):
            if args.list:
                binding = "fraud-bound" if mod.fraud_bound else "chain-bound" if mod.chain_bound else "unbound"
                print(f"{name}  [{binding}]")
                for sig, where in sorted(mod.signatures.items()):
                    print(f"    {sig:<9} {where}")
            elif name not in entries:
                print(registry_line(name, "instructed", "pending-chain", f"TODO classify — {mod.evidence()}"))
        return 2 if problems or not derived else 0
    return evaluate(root, REGISTRY)


if __name__ == "__main__":
    sys.exit(main())
