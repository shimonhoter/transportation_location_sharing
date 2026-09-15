"""Minimal ride-location server.

Standard library only (no Flask / third-party deps), by design: this is
meant to run as a single free-tier web service (e.g. Render.com) with zero
install step.

State model: exactly one "last known location" record in memory, replaced
on every authenticated POST regardless of which device sent it, and
expired after RIDE_LOCATION_TTL_SECONDS. No history is ever kept — see
docs/SPEC_EN.md section 4.2 / 5.

Endpoints:
    GET  /health    -> 200 "ok", no auth (for uptime pings / cold start)
    GET  /location  -> latest location, or {"active": false} once expired
    POST /location  -> submit a location update (requires auth)

Auth: either an `Authorization: Bearer <token>` header or an
`X-Ride-Token: <token>` header, checked against the RIDE_TOKEN environment
variable. The server refuses to start without RIDE_TOKEN set, so a secret
is never accidentally left at a default value in a public deployment.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_TTL_SECONDS = 90
MAX_BODY_BYTES = 8192

_state_lock = threading.Lock()
_last_location: dict | None = None


def _ttl_seconds() -> float:
    return float(os.environ.get("RIDE_LOCATION_TTL_SECONDS", DEFAULT_TTL_SECONDS))


def _expected_token() -> str:
    token = os.environ.get("RIDE_TOKEN")
    if not token:
        raise RuntimeError(
            "RIDE_TOKEN environment variable is not set. Refusing to start "
            "without a shared secret configured."
        )
    return token


def _extract_token(headers) -> str | None:
    auth = headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[len("Bearer "):].strip()
    ride_token = headers.get("X-Ride-Token")
    if ride_token:
        return ride_token.strip()
    return None


class LocationRequestHandler(BaseHTTPRequestHandler):
    server_version = "RideLocationServer/1.0"

    def log_message(self, fmt, *args):  # noqa: A003 - stdlib signature
        sys.stderr.write("%s - - [%s] %s\n" % (self.client_address[0], self.log_date_time_string(), fmt % args))

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - stdlib signature
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
            return
        if self.path == "/location":
            self._handle_get_location()
            return
        self._send_json(404, {"error": "not_found"})

    def do_POST(self):  # noqa: N802 - stdlib signature
        if self.path == "/location":
            self._handle_post_location()
            return
        self._send_json(404, {"error": "not_found"})

    def _handle_get_location(self) -> None:
        with _state_lock:
            location = dict(_last_location) if _last_location else None

        if location is None:
            self._send_json(200, {"active": False})
            return

        age = time.time() - location["received_at"]
        if age > _ttl_seconds():
            self._send_json(200, {"active": False})
            return

        response = {
            "active": True,
            "lat": location["lat"],
            "lon": location["lon"],
            "age_seconds": round(age, 1),
        }
        if location.get("nickname"):
            response["nickname"] = location["nickname"]
        self._send_json(200, response)

    def _handle_post_location(self) -> None:
        try:
            expected_token = _expected_token()
        except RuntimeError as exc:
            self._send_json(500, {"error": str(exc)})
            return

        token = _extract_token(self.headers)
        if token != expected_token:
            self._send_json(401, {"error": "unauthorized"})
            return

        length = int(self.headers.get("Content-Length", 0))
        if length <= 0 or length > MAX_BODY_BYTES:
            self._send_json(400, {"error": "invalid_content_length"})
            return

        raw_body = self.rfile.read(length)
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send_json(400, {"error": "invalid_json"})
            return

        try:
            lat = float(payload["lat"])
            lon = float(payload["lon"])
        except (KeyError, TypeError, ValueError):
            self._send_json(400, {"error": "lat_lon_required"})
            return

        if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
            self._send_json(400, {"error": "lat_lon_out_of_range"})
            return

        nickname = payload.get("nickname")
        if nickname is not None and not isinstance(nickname, str):
            self._send_json(400, {"error": "invalid_nickname"})
            return

        record = {
            "lat": lat,
            "lon": lon,
            "nickname": nickname,
            "received_at": time.time(),
        }
        with _state_lock:
            global _last_location
            _last_location = record

        self._send_json(200, {"status": "ok"})


def main() -> None:
    _expected_token()  # fail fast if RIDE_TOKEN is missing
    port = int(os.environ.get("PORT", 8000))
    server = ThreadingHTTPServer(("0.0.0.0", port), LocationRequestHandler)
    print(f"Ride location server listening on :{port} (TTL={_ttl_seconds()}s)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
