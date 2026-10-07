"""Independent public-status collector and read-only API.

The scheduled collector persists only public, aggregate probe results. The API never
performs checks on demand and never exposes raw network errors or internal topology.
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

BUCKET = os.environ.get("STATUS_DATA_BUCKET", "")
KEY = "state.json"
INTERVAL_SECONDS = 120
STALE_SECONDS = 600
RETENTION_SECONDS = 30 * 86400
COMPONENTS = {
    "website": {
        "name": "Public website",
        "description": "DNS, TLS, and page content for both website addresses.",
        "coverage": "DNS + HTTPS + content",
    },
    "customer_login": {
        "name": "Customer sign-in",
        "description": "Customer identity discovery responds with valid configuration.",
        "coverage": "Login discovery only",
    },
    "api_edge": {
        "name": "API edge",
        "description": "Public edge reachability and TLS. Transactions are not tested.",
        "coverage": "Edge reachability only",
    },
}


def timestamp(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def request(url, *, accept=None, timeout=6):
    headers = {"User-Agent": "OpenBankPublicStatus/1.0"}
    if accept:
        headers["Accept"] = accept
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout) as response:
        return response.status, response.read(400_000), response.geturl()


def dns_check(resolver, domain):
    base = "https://dns.google/resolve" if resolver == "google" else "https://cloudflare-dns.com/dns-query"
    query = urllib.parse.urlencode({"name": domain, "type": "A"})
    try:
        status, body, _ = request(base + "?" + query, accept="application/dns-json")
        payload = json.loads(body)
        answers = payload.get("Answer") or []
        return status == 200 and payload.get("Status") == 0 and any(a.get("type") == 1 and a.get("data") for a in answers)
    except (OSError, ValueError, KeyError, TimeoutError):
        return False


def website_check(domain):
    try:
        status, body, final_url = request("https://" + domain + "/")
        text = body.decode("utf-8", errors="replace")
        return status == 200 and "OpenBank" in text and urllib.parse.urlparse(final_url).hostname in {"open-bank.tech", "www.open-bank.tech"}
    except (OSError, ValueError, TimeoutError):
        return False


def login_check():
    url = "https://kc.open-bank.tech/realms/openbank-customers/.well-known/openid-configuration"
    try:
        status, body, _ = request(url, accept="application/json")
        data = json.loads(body)
        return status == 200 and data.get("issuer") == "https://kc.open-bank.tech/realms/openbank-customers" and data.get("authorization_endpoint", "").startswith("https://kc.open-bank.tech/")
    except (OSError, ValueError, KeyError, TimeoutError):
        return False


def api_edge_check():
    try:
        status, _, _ = request("https://api.open-bank.tech/")
        return status in {200, 204, 301, 302, 401, 403, 404}
    except urllib.error.HTTPError as error:
        return error.code in {401, 403, 404}
    except (OSError, ValueError, TimeoutError):
        return False


def run_checks():
    tasks = {
        "website_apex": lambda: website_check("open-bank.tech"),
        "website_www": lambda: website_check("www.open-bank.tech"),
        "dns_cf_apex": lambda: dns_check("cloudflare", "open-bank.tech"),
        "dns_cf_www": lambda: dns_check("cloudflare", "www.open-bank.tech"),
        "dns_google_apex": lambda: dns_check("google", "open-bank.tech"),
        "dns_google_www": lambda: dns_check("google", "www.open-bank.tech"),
        "customer_login": login_check,
        "api_edge": api_edge_check,
    }
    with ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        futures = {name: pool.submit(check) for name, check in tasks.items()}
        results = {}
        for name, future in futures.items():
            try:
                results[name] = bool(future.result(timeout=9))
            except Exception:  # A failed probe must never turn into healthy state.
                results[name] = False
    return {
        "website": all(results[name] for name in results if name.startswith("website_") or name.startswith("dns_")),
        "customer_login": results["customer_login"],
        "api_edge": results["api_edge"],
    }


def empty_state():
    return {"schemaVersion": 1, "checkedAt": None, "components": {}, "samples": [], "incidents": []}


def load_state(s3):
    try:
        return json.loads(s3.get_object(Bucket=BUCKET, Key=KEY)["Body"].read())
    except s3.exceptions.NoSuchKey:
        return empty_state()
    except Exception as error:
        if getattr(error, "response", {}).get("Error", {}).get("Code") == "NoSuchKey":
            return empty_state()
        raise


def update_state(state, observed, now):
    state = dict(state)
    previous = state.get("components") or {}
    components = {}
    incidents = list(state.get("incidents") or [])
    for key, good in observed.items():
        prior = previous.get(key) or {}
        failures = 0 if good else prior.get("consecutiveFailures", 0) + 1
        successes = prior.get("consecutiveSuccesses", 0) + 1 if good else 0
        was_bad = prior.get("status") == "outage"
        is_bad = failures >= 3 or (was_bad and successes < 2)
        pending = not good and not is_bad
        recovering = good and was_bad and successes < 2
        status = "outage" if is_bad else "pending" if pending or recovering else "operational"
        components[key] = {"status": status, "consecutiveFailures": failures, "consecutiveSuccesses": successes, "lastResult": "Pass" if good else "Fail"}
        if is_bad and not was_bad:
            incidents.insert(0, {"id": f"{key}-{now}", "component": key, "title": COMPONENTS[key]["name"] + " interruption", "summary": "External checks confirmed an interruption. We are investigating.", "startedAt": timestamp(now), "resolvedAt": None})
        if was_bad and not is_bad:
            for incident in incidents:
                if incident.get("component") == key and not incident.get("resolvedAt"):
                    incident["resolvedAt"] = timestamp(now)
                    incident["summary"] = "External checks recovered and remained successful."
                    break
    state["components"] = components
    state["checkedAt"] = timestamp(now)
    samples = [sample for sample in state.get("samples", []) if sample[0] >= now - RETENTION_SECONDS]
    samples.append([now, 1 if observed["website"] else 0])
    state["samples"] = samples
    state["incidents"] = incidents[:100]
    return state


def history(samples, now, duration, bucket_count):
    start = now - duration
    width = duration / bucket_count
    buckets = [{"start": timestamp(int(start + i * width)), "success": 0, "total": 0} for i in range(bucket_count)]
    for when, passed in samples:
        if start <= when <= now:
            index = min(bucket_count - 1, int((when - start) / width))
            buckets[index]["total"] += 1
            buckets[index]["success"] += int(passed)
    total = sum(bucket["total"] for bucket in buckets)
    success = sum(bucket["success"] for bucket in buckets)
    return {"availabilityPercent": round(100 * success / total, 4) if total else None, "totalSamples": total, "successfulSamples": success, "buckets": buckets}


def snapshot_is_fresh(state, now):
    check_time = state.get("checkedAt")
    if not check_time:
        return False
    try:
        checked_epoch = datetime.fromisoformat(check_time.replace("Z", "+00:00")).timestamp()
        return -60 <= now - checked_epoch <= STALE_SECONDS
    except ValueError:
        return False


def public_state(state, now):
    components = state.get("components") or {}
    check_time = state.get("checkedAt")
    stale = not snapshot_is_fresh(state, now)
    statuses = [components.get(key, {}).get("status", "unknown") for key in COMPONENTS]
    if stale or not statuses:
        overall = "unknown"
    elif any(status == "outage" for status in statuses):
        overall = "partial_outage" if statuses.count("outage") < len(statuses) else "major_outage"
    elif any(status != "operational" for status in statuses):
        overall = "unknown"
    else:
        overall = "operational"
    exposed = []
    for key, definition in COMPONENTS.items():
        entry = components.get(key) or {}
        exposed.append({"id": key, **definition, "status": "unknown" if stale else entry.get("status", "unknown"), "lastResult": entry.get("lastResult", "Unverified")})
    samples = state.get("samples") or []
    return {
        "schemaVersion": 1,
        "checkedAt": check_time,
        "status": overall,
        "message": "A failed check is being verified." if overall == "unknown" and not stale else None,
        "components": exposed,
        "history": {"24h": history(samples, now, 86400, 48), "30d": history(samples, now, RETENTION_SECONDS, 30)},
        "incidents": state.get("incidents") or [],
        "measurement": {"intervalSeconds": INTERVAL_SECONDS, "staleAfterSeconds": STALE_SECONDS, "timeZone": "UTC", "scope": "External website, customer login discovery, and API edge checks"},
    }


def response(status, body):
    return {"statusCode": status, "headers": {"content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=30", "access-control-allow-origin": "*", "x-content-type-options": "nosniff"}, "body": json.dumps(body, separators=(",", ":"))}


def handler(event, context):
    import boto3  # Included in the managed Lambda runtime; pure logic stays locally testable.

    s3 = boto3.client("s3")
    now = int(time.time())
    if event.get("source") == "aws.events":
        state = update_state(load_state(s3), run_checks(), now)
        s3.put_object(Bucket=BUCKET, Key=KEY, Body=json.dumps(state, separators=(",", ":")).encode(), ContentType="application/json", ServerSideEncryption="AES256")
        return {"checkedAt": state["checkedAt"], "status": public_state(state, now)["status"]}
    path = event.get("rawPath", "")
    if path == "/api/v1/healthz":
        return response(200, {"status": "ok", "service": "openbank-public-status-api"})
    if path not in {"/api/v1/status", "/api/v1/health", "/api/v1/history", "/api/v1/incidents", "/api/v1/freshness"}:
        return response(404, {"error": "Not found"})
    try:
        state = load_state(s3)
        public = public_state(state, now)
    except Exception:
        return response(503, {"status": "unknown", "error": "Status data unavailable"})
    if path == "/api/v1/freshness":
        fresh = snapshot_is_fresh(state, now)
        return response(200 if fresh else 503, {"status": "fresh" if fresh else "stale", "checkedAt": public["checkedAt"]})
    if path == "/api/v1/health":
        body = {key: public[key] for key in ("schemaVersion", "checkedAt", "status", "components", "measurement")}
        return response(200 if public["status"] == "operational" else 503, body)
    if path == "/api/v1/history":
        return response(200, {"schemaVersion": 1, "checkedAt": public["checkedAt"], "history": public["history"]})
    if path == "/api/v1/incidents":
        return response(200, {"schemaVersion": 1, "checkedAt": public["checkedAt"], "incidents": public["incidents"]})
    return response(200, public)
