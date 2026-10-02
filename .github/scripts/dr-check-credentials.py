#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Write one-run DR viewer credentials locally; only the public key enters Kubernetes."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def generate(directory):
    destination = Path(directory)
    # The caller creates an empty, private directory and owns its teardown.
    if not destination.is_dir() or any(destination.iterdir()):
        raise ValueError("credentials require an existing empty directory")
    destination.chmod(0o700)
    with tempfile.TemporaryDirectory(prefix="signing-", dir=destination) as temporary:
        private_key = Path(temporary) / "key.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt",
                        "rsa_keygen_bits:2048", "-out", str(private_key)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        public_key = subprocess.check_output(
            ["openssl", "pkey", "-in", str(private_key), "-pubout", "-outform", "DER"],
            stderr=subprocess.DEVNULL)
        now = int(time.time())
        claims = {"iss": "urn:openbank:dr-check", "aud": "openbank-dr-check",
                  "sub": "dr-viewer", "groups": ["ROLE_VIEWER"], "iat": now, "exp": now + 3600}
        message = encode(b'{"alg":"RS256","typ":"JWT"}') + "." + encode(
            json.dumps(claims, separators=(",", ":")).encode())
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
            input=message.encode(), capture_output=True, check=True).stdout
        header = "Authorization: Bearer " + message + "." + encode(signature) + "\n"
        for name, value in (("public-key", base64.b64encode(public_key).decode()),
                            ("authorization-header", header)):
            descriptor = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as stream:
                stream.write(value)
    # No key, JWT or header is printed, and the signing private key is now gone.


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: dr-check-credentials.py EMPTY_PRIVATE_DIRECTORY")
    generate(sys.argv[1])
