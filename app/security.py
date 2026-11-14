"""Auth, brute-force throttling, security headers and redaction of sensitive financial details."""
import hmac
import os
import re
import time
from collections import defaultdict, deque

from fastapi import Header, HTTPException, Query, Request

LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}
_failures: dict[str, deque] = defaultdict(deque)
MAX_FAILURES, WINDOW = 10, 600  # 10 bad tokens per 10 minutes per client


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _throttle(ip: str) -> None:
    q = _failures[ip]
    now = time.monotonic()
    while q and now - q[0] > WINDOW:
        q.popleft()
    if len(q) >= MAX_FAILURES:
        raise HTTPException(429, "Too many failed attempts. Try again later.")


def _check(request: Request, supplied: str | None, accepted: list[str]) -> None:
    ip = _client_ip(request)
    _throttle(ip)
    accepted = [t for t in accepted if t]
    if not accepted:
        # No token configured: only allow requests from this machine.
        if ip in LOCAL_HOSTS:
            return
        raise HTTPException(401, "Set ARTHA_TOKEN on the server to use Artha from another device")
    if supplied and any(hmac.compare_digest(supplied.encode(), t.encode()) for t in accepted):
        return
    _failures[ip].append(time.monotonic())
    raise HTTPException(401, "Missing or wrong token")


def require_token(request: Request, x_token: str | None = Header(default=None)) -> None:
    """Full access. Token must come in the X-Token header (never in URLs, which end up in logs/history)."""
    _check(request, x_token, [os.environ.get("ARTHA_TOKEN", "")])


def require_webhook_token(
    request: Request,
    x_token: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> None:
    """Webhook access. Phone automation apps often can only put the token in the URL, so allow that here,
    and accept a separate add-only ARTHA_WEBHOOK_TOKEN so the main token never has to live in those apps."""
    _check(request, x_token or token, [os.environ.get("ARTHA_TOKEN", ""), os.environ.get("ARTHA_WEBHOOK_TOKEN", "")])


SECURITY_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    ),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "X-Frame-Options": "DENY",
    "Permissions-Policy": "microphone=(self), camera=(self), geolocation=()",
    "Cross-Origin-Opener-Policy": "same-origin",
}


async def security_headers(request: Request, call_next):
    response = await call_next(request)
    for k, v in SECURITY_HEADERS.items():
        response.headers.setdefault(k, v)
    if request.url.scheme == "https":
        response.headers.setdefault("Strict-Transport-Security", "max-age=31536000")
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


# ---------- redaction ----------
_LONG_NUM = re.compile(r"(?<![\d.,])(\d{4,})(\d{4})(?![\d.,])")         # account / card numbers
_BALANCE = re.compile(r"(?i)\b(avl\.?\s*bal|available\s+balance|avl\.?\s*lmt|available\s+limit|bal)\b[^.;\n]*")
_OTP = re.compile(r"(?i)\b(otp|one[- ]time password|verification code)\b[^.;\n]*")


def redact(text: str) -> str:
    """Mask account/card numbers and drop balances/OTPs before storing or sending text anywhere."""
    if not text:
        return text
    text = _LONG_NUM.sub(lambda m: "X" * min(len(m.group(1)), 4) + m.group(2), text)
    text = _BALANCE.sub("[balance hidden]", text)
    text = _OTP.sub("[code hidden]", text)
    return text
