# SPDX-License-Identifier: Apache-2.0
"""Counts GitHub REST/GraphQL requests that Python scripts send, for gh-call-counter.sh.

The gh shim only sees `gh` invocations. Scripts that talk to the API with urllib or requests
(merge-train.py, the stall watch, most gates) bypassed it, so the merge-train build job reported
`total=0` while its own statistics said `sent=180`. `gh-call-counter.sh install` copies this file
into the counter directory and puts that directory on PYTHONPATH; Python then imports it at start-up
(site.py's `sitecustomize` hook) in every interpreter the job launches, without any script change.

Hooked at http.client's putrequest, which both urllib and requests (urllib3) go through, so one patch covers both. Only
requests whose host is the API host are logged, as `<caller>\\tpy <METHOD> <path-without-query>\\t-`:
no headers, no body, no query string. Like the shim it fails open: an error here never stops the
request, and an unwritable log only loses the line.

Lower bound still: `python3 -I`/`-S`, a script that replaces PYTHONPATH, and Octokit in
actions/github-script are not seen.
"""
import os
import sys


def _install() -> None:
    log = os.environ.get("GH_CALL_COUNTER_LOG", "")
    if not log:
        return
    import http.client
    import re
    from urllib.parse import urlsplit

    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    hosts = {"api.github.com", (urlsplit(api_url).hostname or "").lower()} - {""}
    caller = os.path.basename(sys.argv[0]) if sys.argv and sys.argv[0] else "python"
    # putrequest, not request: urllib3 2.x (under `requests`) overrides HTTPConnection.request and
    # never calls it, but both it and http.client's own request() go through putrequest.
    original = http.client.HTTPConnection.putrequest

    def putrequest(self, method, url, *args, **kwargs):
        try:
            # Through an HTTPS proxy `host` is the proxy; the API host is the CONNECT tunnel target.
            host = (getattr(self, "_tunnel_host", None) or getattr(self, "host", "") or "").lower()
            if host in hosts:
                path = urlsplit(url).path if "://" in url else url.split("?", 1)[0]
                what = re.sub(r"[^a-zA-Z0-9/._ -]", "_", f"py {method} {path}")[:120]
                with open(log, "a", encoding="utf-8") as f:
                    f.write(f"{caller}\t{what}\t-\n")
        except Exception:  # noqa: BLE001 - counting must never break the request
            pass
        return original(self, method, url, *args, **kwargs)

    http.client.HTTPConnection.putrequest = putrequest


def _chain() -> None:
    """Run the sitecustomize this one shadows (Ubuntu ships one), so installing the counter
    changes nothing else about the interpreter."""
    here = os.path.dirname(os.path.abspath(__file__))
    for entry in sys.path:
        if not entry or os.path.abspath(entry) == here:
            continue
        candidate = os.path.join(entry, "sitecustomize.py")
        if os.path.isfile(candidate):
            with open(candidate, encoding="utf-8") as f:
                exec(compile(f.read(), candidate, "exec"), {"__name__": "sitecustomize", "__file__": candidate})
            return


try:
    _install()
except Exception:  # noqa: BLE001
    pass
try:
    _chain()
except Exception:  # noqa: BLE001
    pass
