#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Shared file-read and YAML-parse cache for the gate checkers.

WHY THIS EXISTS
---------------
The gates are 129 separate programs over ONE corpus. Nothing shares anything: 107 python
scripts in this directory, zero cross-imports, and 61 of them call `yaml.safe_load` directly.
So the same 647 gitops manifests and the same 3121 `.kt` files are read and re-parsed by gate
after gate, and inside a single gate by pass after pass.

Measured on 2026-08-09 against `origin/main`:

  * `check-probe-port-listener.py` — 43.3s, the slowest gate in the `gitops` shard. cProfile:
    **93% of it is `yaml.load_all`**, 3790 parse calls over 647 distinct files. Memoising the
    parse inside that one process took it to 5.8s with a byte-identical verdict (116 ports).
  * The 20 single-script python gates in the `gitops` shard cost 231s as separate processes.
    Run with one shared parse they cost 8.7s — same verdicts, same counts (647 manifests,
    42 OPA sidecars, 116 ports).
  * Doing the work ONCE is cheap: parsing all 647 gitops yaml is 4.5s, against 0.03s to read
    them and 0.01s to walk the tree. Everything above that is repetition, and it is all in
    the parser — which is why the parse is the only thing cached here.

WHAT IT GUARANTEES
------------------
1. **Callers cannot poison each other.** Every parsed structure is returned as a deep copy, so
   a checker that mutates what it got cannot change what the next caller sees. This is the one
   property a cache like this can get catastrophically wrong, and it would show up as a gate
   that is correct alone and wrong in a shard — the hardest possible failure to reproduce. The
   copy is affordable precisely because parsing is not: deep-copying the whole gitops corpus is
   0.04s against 5.9s to parse it.
2. **The cache key is the CONTENT, never the path or the mtime.** Two paths with identical
   bytes share one parse; a file rewritten with the same bytes is still a hit; a file rewritten
   with different bytes can never be a stale hit. Gates that regenerate files mid-run (the
   drift gates run their generator and then diff) therefore cannot read a pre-generation parse.
3. **The disk layer is opt-in and scoped to one run.** `run-gates.py` creates a directory per
   invocation, exports `GATE_PARSE_CACHE`, and removes it afterwards, so the cross-process
   sharing lasts exactly as long as the shard and no state survives to the next run. Without
   that variable — a checker run by hand, or from a workflow that is not the gate runner — the
   in-process memo is all that is active and behaviour is unchanged.

WHAT IT IS NOT
--------------
Not a way to make a gate cheaper by looking at fewer files. The corpus each gate examines is
unchanged; only the number of times it is decoded goes down. A gate that reads less is a gate
that checks less, which is the failure this repo names most often.

Usage:
    import gatelib
    text  = gatelib.read_text(path)                 # str, memoised
    doc   = gatelib.load_yaml(path)                 # first document, or None
    docs  = gatelib.load_yaml_all(path)             # list of documents
    paths = gatelib.rglob(root, "*.yaml")           # sorted tuple, memoised listing

Import it with `sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))` — the
checkers are run as scripts from the repo root, so the directory is not on `sys.path` by
default.
"""

from __future__ import annotations

import copy
import hashlib
import marshal
import os
import pathlib
import tempfile
from typing import Any

import yaml

# libyaml when the wheel was built with it (the manylinux/macOS wheels on PyPI are), the pure
# python parser otherwise. Callers get the same objects either way; this only decides speed.
SafeLoader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)

CACHE_DIR_ENV = "GATE_PARSE_CACHE"
# Pinned rather than `marshal.version`: the blob directory is created fresh per run, so there is
# never a cross-version blob to read, and pinning keeps the format from moving under a reader.
MARSHAL_VERSION = 4

_parse_cache: dict[str, list] = {}


def _disk_dir() -> pathlib.Path | None:
    d = os.environ.get(CACHE_DIR_ENV)
    if not d:
        return None
    p = pathlib.Path(d)
    return p if p.is_dir() else None


def read_text(path: os.PathLike | str, errors: str = "strict") -> str:
    """`path.read_text(encoding="utf-8")`. NOT cached, deliberately — see below.

    Caching this by path is the obvious next optimisation and it is wrong. A checker that
    WRITES a file and reads it back in the same process would get the pre-write text, and the
    first thing that broke was not a gate but a self-test: `check-libs-annotations-implemented`
    builds a fixture tree, runs its analysis, rewrites the same paths and runs it again. With
    a path-keyed read cache its rule B stopped flagging, i.e. the gate reported itself
    unfalsifiable — the correct outcome, from a cache that had silently lied.

    A stat-based invalidation (mtime_ns + size) would cover almost all of it, and "almost" is
    the wrong standard for a cache under a gate: same-size rewrites inside one filesystem
    timestamp tick are exactly what a fixture loop does. The parse cache below needs no such
    argument — it is keyed by the CONTENT it parsed, so it cannot be stale by construction —
    and parsing, not reading, is what costs: 5.9s to parse the gitops corpus, 0.03s to read it
    (0.01s to walk it, which is why the glob helpers below do not cache either).
    """
    return pathlib.Path(path).read_text(encoding="utf-8", errors=errors)


def _parse(text: str) -> list:
    """Every document in `text`, cached by content sha across processes when enabled."""
    sha = hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest()
    docs = _parse_cache.get(sha)
    if docs is not None:
        return docs

    disk = _disk_dir()
    # `marshal`, deliberately, NOT pickle. The blobs are written by this process into a
    # directory run-gates.py made and will delete, so the threat model is thin either way —
    # but marshal cannot instantiate an arbitrary class or call anything on load, so a
    # corrupted or planted blob is a decode error rather than code execution. The cost is
    # that marshal handles only the plain types (dict/list/str/int/float/bool/None); a YAML
    # timestamp deserialises to `datetime`, which marshal refuses, and such a document simply
    # does not get a disk entry. JSON is NOT the alternative it looks like: it would coerce a
    # datetime or a non-string key on the way through and hand the next gate a document that
    # differs from what the parser produced.
    blob_path = disk / f"{sha}.marshal" if disk is not None else None
    if blob_path is not None and blob_path.is_file():
        try:
            docs = marshal.loads(blob_path.read_bytes())
        except Exception:  # a truncated or unreadable blob is a cache miss, never a failure
            docs = None
    if docs is None:
        docs = list(yaml.load_all(text, Loader=SafeLoader))
        if blob_path is not None:
            try:
                # Write-then-rename: four gates run concurrently in a shard and may reach the
                # same file at the same moment. A half-written blob that another process reads
                # would be a cache miss (handled above), but renaming keeps it from happening
                # at all.
                blob = marshal.dumps(docs, MARSHAL_VERSION)
                fd, tmp = tempfile.mkstemp(dir=str(blob_path.parent), suffix=".part")
                with os.fdopen(fd, "wb") as fh:
                    fh.write(blob)
                os.replace(tmp, blob_path)
            except ValueError:
                pass  # a type marshal will not carry (datetime): in-process cache only
            except Exception:
                pass  # the cache is an optimisation; never let it fail a gate
    _parse_cache[sha] = docs
    return docs


def load_yaml_all(path: os.PathLike | str, errors: str = "strict") -> list:
    """Every YAML document in `path`. Raises `yaml.YAMLError` exactly as `safe_load_all` does."""
    return copy.deepcopy(_parse(read_text(path, errors=errors)))


def load_yaml(path: os.PathLike | str, errors: str = "strict") -> Any:
    """The first YAML document in `path`, or None for an empty file."""
    docs = _parse(read_text(path, errors=errors))
    return copy.deepcopy(docs[0]) if docs else None


def loads_all(text: str) -> list:
    """As `load_yaml_all`, for text a caller already holds (an embedded ConfigMap value)."""
    return copy.deepcopy(_parse(text))


def loads(text: str) -> Any:
    docs = _parse(text)
    return copy.deepcopy(docs[0]) if docs else None


def rglob(root: os.PathLike | str, pattern: str) -> tuple[pathlib.Path, ...]:
    """`sorted(root.rglob(pattern))`. Not cached, for the same reason as read_text: a gate that
    generates files and then enumerates them must see them."""
    return tuple(sorted(pathlib.Path(root).rglob(pattern)))


def glob(root: os.PathLike | str, pattern: str) -> tuple[pathlib.Path, ...]:
    return tuple(sorted(pathlib.Path(root).glob(pattern)))


SUBJECTS_PREFIX = "SUBJECTS="


def subjects(count: int, label: str = "") -> None:
    """Declare how many things this checker actually examined.

    run-gates.py reads the LAST such line and fails the gate when it is below the
    `min_subjects:` declared in gates.yaml. The point is the failure mode measured on
    2026-08-09: delete every `.kt` file in the tree and nine Kotlin-subject gates still report
    PASS, several of them printing `0 ... checked` while exiting 0. The count was already in
    the output; nothing acted on it. A renamed directory, a moved source root or a changed
    glob turns a gate into a green no-op, and there was no layer that could tell.

    Print it unconditionally, including on the failure path — a gate that found its corpus and
    then failed on it must not also be reported as having lost its corpus.
    """
    print(f"{SUBJECTS_PREFIX}{int(count)}" + (f"  # {label}" if label else ""))


SUBJECTS_UNRESOLVED = "UNRESOLVED"


def subjects_unresolved(reason: str) -> None:
    """Declare that the corpus could not be READ at all — a third state, not a count of zero.

    A gate whose corpus lives behind a network API has three outcomes, not two: it compared
    and found nothing wrong, it compared and found a violation, or it could not compare. The
    third must render as neither a pass nor a failure — see check-stale-comment-references.py's
    repo rule, and check-ruleset-context-parity.py, which on 2026-08-21 exited 1 on a shared
    installation rate limit and reddened a PR whose diff could not have caused it.

    `min_subjects:` exists to catch a gate that silently lost its corpus, and 0 subjects is
    exactly what an unreachable API produces — so without this line the manifest floor would
    convert the third state straight back into a red one layer up. run-gates.py reads this and
    skips the floor for that run only; a run that DID read its corpus is still held to it.
    """
    print(f"{SUBJECTS_PREFIX}{SUBJECTS_UNRESOLVED}  # {reason}")


def clear() -> None:
    """Drop the in-process parse cache. For tests."""
    _parse_cache.clear()
