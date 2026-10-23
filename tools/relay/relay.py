#!/usr/bin/env python3
"""Screening upload relay.

A screening round publishes its configuration by posting it to a Cloudflare
worker. The phone sends that request through whichever node is carrying its
tunnel, and the upload is the one long-lived write of the round: a node that
drops the connection halfway, or a worker that gives up on a body arriving in
small pieces, ends a round that measured everything correctly with nothing
published.

This service runs on the sandbox and accepts the same request the worker
would have taken, then pushes it out from here: a datacenter line that is
neither slow nor far from Cloudflare. The body is kept after every attempt,
so a round whose response was lost can be finished by hand with POST /retry
instead of waiting for the next schedule.

The bearer token is never stored in the clear on purpose: it is forwarded
exactly as received. The spool keeps only the configuration body.
"""

import gzip
import json
import os
import time
import threading

import requests
from flask import Flask, Response, jsonify, request

UPSTREAM = os.environ.get(
    "RELAY_UPSTREAM", "https://cf-converter.elaimedia.dns-dynamic.net"
).rstrip("/")
PORT = int(os.environ.get("RELAY_PORT", "5001"))
SPOOL = os.environ.get("RELAY_SPOOL", "/data/home/relay/spool")
ATTEMPTS = int(os.environ.get("RELAY_ATTEMPTS", "4"))
MAX_BYTES = int(os.environ.get("RELAY_MAX_BYTES", str(8 * 1024 * 1024)))
VERBOSE = os.environ.get("RELAY_VERBOSE", "1") == "1"

# The paths the worker serves. A phone configured with either of them against
# this sandbox is relayed to the same path on the worker; /upload is the short
# name for the screening one.
SCREENING_PATH = "/internal/freeclashnode"
APP_CONFIG_PATH = "/internal/app-config"
PATHS = (SCREENING_PATH, APP_CONFIG_PATH, "/upload")

app = Flask(__name__)
lock = threading.Lock()


def log(message):
    if VERBOSE:
        print(time.strftime("%Y-%m-%d %H:%M:%S"), message, flush=True)


def spool_name(target):
    return "app-config.yaml.gz" if target == APP_CONFIG_PATH else "freeclashnode.yaml.gz"


def spool_path(name):
    return os.path.join(SPOOL, name)


def push(target, body, headers):
    """Posts [body] to the worker, retrying what is worth retrying.

    A 4xx other than 408/429 is the worker rejecting the request itself - a bad
    token, a malformed body - and repeating it would only spend the round's
    time to be told the same thing.
    """
    started = time.time()
    last = ""

    for attempt in range(1, ATTEMPTS + 1):
        try:
            response = requests.post(
                UPSTREAM + target, data=body, headers=headers, timeout=(15, 240)
            )

            if response.ok:
                log(
                    "%s: %d bytes -> %d in %.1fs (attempt %d)"
                    % (target, len(body), response.status_code,
                       time.time() - started, attempt)
                )

                return response

            last = "HTTP %d: %s" % (response.status_code, response.text[:200])
            log("%s: attempt %d: %s" % (target, attempt, last))

            if 400 <= response.status_code < 500 and response.status_code not in (408, 429):
                return response
        except Exception as error:  # noqa: BLE001 - every failure is retried the same way
            last = "%s: %s" % (type(error).__name__, error)
            log("%s: attempt %d: %s" % (target, attempt, last))

        if attempt < ATTEMPTS:
            time.sleep(min(2 ** attempt, 20))

    return Response(
        json.dumps({"ok": False, "error": "upstream failed", "detail": last}),
        status=502,
        mimetype="application/json",
    )


@app.get("/health")
def health():
    return jsonify(
        ok=True,
        upstream=UPSTREAM,
        attempts=ATTEMPTS,
        spooled=sorted(os.listdir(SPOOL)) if os.path.isdir(SPOOL) else [],
    )


@app.post("/internal/freeclashnode")
@app.post("/internal/app-config")
@app.post("/upload")
def upload():
    target = SCREENING_PATH if request.path == "/upload" else request.path

    body = request.get_data(cache=False)
    if not body:
        return jsonify(ok=False, error="empty body"), 400
    if len(body) > MAX_BYTES:
        return jsonify(ok=False, error="body larger than %d bytes" % MAX_BYTES), 413

    encoding = (request.headers.get("Content-Encoding") or "").strip().lower()
    plain = body
    if encoding == "gzip" or body[:2] == b"\x1f\x8b":
        try:
            plain = gzip.decompress(body)
        except OSError as error:
            log("%s: body does not decompress: %s" % (target, error))

            return jsonify(ok=False, error="body is not valid gzip"), 400

    # Kept before the attempt, not after: a body that arrives is the only copy,
    # and a round that died waiting for the response can still be published by
    # POST /retry.
    try:
        os.makedirs(SPOOL, exist_ok=True)
        name = spool_name(target)
        with lock:
            temporary = spool_path(name + ".part")
            with open(temporary, "wb") as handle:
                handle.write(body)
            os.replace(temporary, spool_path(name))
    except OSError as error:
        log("cannot spool %s: %s" % (target, error))

    headers = {
        "Content-Type": request.headers.get("Content-Type")
        or "application/yaml; charset=utf-8",
        "User-Agent": request.headers.get("User-Agent") or "cmfa-relay/1.0",
    }
    if encoding:
        headers["Content-Encoding"] = encoding
    authorization = request.headers.get("Authorization")
    if authorization:
        headers["Authorization"] = authorization

    log("%s: received %d bytes (%d plain)" % (target, len(body), len(plain)))

    response = push(target, body, headers)

    return Response(
        response.content,
        status=response.status_code,
        content_type=response.headers.get("Content-Type") or "application/json",
    )


@app.post("/retry")
def retry():
    """Pushes the last spooled body again, for a round whose response was lost."""
    target = request.args.get("path") or SCREENING_PATH
    if target not in PATHS or target == "/upload":
        target = SCREENING_PATH

    path = spool_path(spool_name(target))
    if not os.path.exists(path):
        return jsonify(ok=False, error="nothing spooled for %s" % target), 404

    with open(path, "rb") as handle:
        body = handle.read()

    headers = {
        "Content-Type": "application/yaml; charset=utf-8",
        "Content-Encoding": "gzip",
        "User-Agent": "cmfa-relay/1.0",
    }
    token = (request.headers.get("Authorization") or "").strip()
    if token:
        headers["Authorization"] = token
    else:
        token_file = os.environ.get("RELAY_TOKEN_FILE", "/data/home/relay/token")
        if os.path.exists(token_file):
            with open(token_file, "r", encoding="utf-8") as handle:
                stored = handle.read().strip()
            if stored:
                headers["Authorization"] = stored if stored.lower().startswith("bearer ") else "Bearer " + stored

    if "Authorization" not in headers:
        return jsonify(ok=False, error="no token: pass Authorization or write RELAY_TOKEN_FILE"), 401

    log("%s: retrying %d spooled bytes" % (target, len(body)))

    response = push(target, body, headers)

    return Response(
        response.content,
        status=response.status_code,
        content_type=response.headers.get("Content-Type") or "application/json",
    )


if __name__ == "__main__":
    os.makedirs(SPOOL, exist_ok=True)
    app.run(host="0.0.0.0", port=PORT, threaded=True)
