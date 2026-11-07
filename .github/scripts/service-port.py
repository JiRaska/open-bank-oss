#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Print the container port an image build should EXPOSE for one service directory.

The port has ONE source: `quarkus.http.port` in the service's `application.yaml`. The
deploy pipelines (`auto-deploy.yml`, `build-push-service.sh`) used to lift it from a
second copy, the `EXPOSE` line of `openbank-*/Dockerfile`, and silently fell back to
8080 when that file was missing. Six deployed services (devops-agent, finops-agent,
fraud, lending, sdd, settlement) had no such file and shipped `EXPOSE 8080` while
listening elsewhere.

Resolution order:
  1. `quarkus.http.port` in src/main/resources/application.yaml (an int, or a
     `${VAR:default}` expression, whose default is used).
  2. `EXPOSE <n>` in <dir>/Dockerfile, for the services that still carry a stub.
  3. 8080, with a warning on stderr. EXPOSE is metadata (the pod's containerPort is
     what routes traffic), so the fallback cannot break a deploy, but it is loud.

Usage: service-port.py <service-dir>
"""

from __future__ import annotations

import pathlib
import re
import sys

EXPOSE_RE = re.compile(r"^EXPOSE\s+(\d+)\s*$", re.M)
KEY_RE = re.compile(r"^(\s*)([A-Za-z0-9_.\-]+):\s*(.*)$")
EXPR_RE = re.compile(r"^\$\{[^:}]+:(\d+)\}$")


def yaml_port(service: pathlib.Path) -> int | None:
    """`quarkus.http.port`, nested or dotted. Stdlib only: the deploy runners do not
    ship PyYAML, and a missing import there would fail every image push.

    Tracks the key path by indentation. Only plain `key: value` mappings are needed to
    reach this one scalar; anything fancier (anchors, flow style) yields None and the
    caller falls through to the next source.
    """
    f = service / "src/main/resources/application.yaml"
    if not f.is_file():
        return None
    stack: list[tuple[int, str]] = []
    found: list[int] = []
    for line in f.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "-")):
            continue
        if line.startswith("---"):
            stack.clear()
            continue
        m = KEY_RE.match(line)
        if not m:
            continue
        indent, key, value = len(m.group(1)), m.group(2), m.group(3).split(" #")[0].strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([k for _, k in stack] + [key])
        if value:
            if path == "quarkus.http.port":
                v = value.strip("\"'")
                e = EXPR_RE.match(v)
                if e:
                    found.append(int(e.group(1)))
                elif v.isdigit():
                    found.append(int(v))
        else:
            stack.append((indent, key))
    return found[0] if len(found) == 1 else None


def stub_port(service: pathlib.Path) -> int | None:
    f = service / "Dockerfile"
    if not f.is_file():
        return None
    m = EXPOSE_RE.search(f.read_text(encoding="utf-8"))
    return int(m.group(1)) if m else None


def resolve(service: pathlib.Path) -> tuple[int, str]:
    for source, port in (("application.yaml", yaml_port(service)),
                         ("Dockerfile EXPOSE", stub_port(service))):
        if port is not None:
            return port, source
    return 8080, "default"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    service = pathlib.Path(sys.argv[1])
    if not service.is_dir():
        print(f"service-port: {service} is not a directory", file=sys.stderr)
        return 2
    port, source = resolve(service)
    if source == "default":
        print(f"::warning::{service}: no quarkus.http.port and no Dockerfile EXPOSE; "
              f"defaulting to 8080", file=sys.stderr)
    print(port)
    return 0


if __name__ == "__main__":
    sys.exit(main())
