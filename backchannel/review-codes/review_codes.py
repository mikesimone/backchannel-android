#!/usr/bin/env python3
"""Play reviewer code page.

Google Play's reviewers can't create accounts or contact us, so they get a
dedicated phone number and this page, which shows the newest Signal
verification code texted to that number. The number is a Google Voice line
whose texts are forwarded by email into a Workspace mailbox; this reads that
mailbox through a service account with domain-wide delegation.

It shows only the code and its age, never the text itself, and only for
texts that mention Signal. Texts to the number older than CLEANUP_HOURS are
moved to Trash (needs the gmail.modify scope; skipped with gmail.readonly).

Environment:
  GOOGLE_SA_JSON     service account key file (domain-wide delegation)
  MAILBOX            mailbox to read, e.g. mike@mikesimone.net
  REVIEW_NUMBER      the reviewer number, digits only with country code
  REVIEW_PASSWORD    password for HTTP basic auth (user "reviewer")
  LISTEN             host:port, default 127.0.0.1:8096
  CODE_MAX_AGE_MIN   ignore codes older than this, default 15
  CLEANUP_HOURS      trash texts older than this, default 24
"""

import base64
import hmac
import html
import os
import re
import threading
import time
from email.utils import parsedate_to_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import google.auth.transport.requests
import google.oauth2.service_account
import requests

GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"
MODIFY = "https://www.googleapis.com/auth/gmail.modify"
READONLY = "https://www.googleapis.com/auth/gmail.readonly"

SA_FILE = os.path.expanduser(os.environ["GOOGLE_SA_JSON"])
MAILBOX = os.environ["MAILBOX"]
NUMBER = re.sub(r"\D", "", os.environ["REVIEW_NUMBER"])
PASSWORD = os.environ["REVIEW_PASSWORD"]
HOST, PORT = os.environ.get("LISTEN", "127.0.0.1:8096").rsplit(":", 1)
CODE_MAX_AGE = int(os.environ.get("CODE_MAX_AGE_MIN", "15")) * 60
CLEANUP_HOURS = int(os.environ.get("CLEANUP_HOURS", "24"))

# Voice forwards texts to NUMBER from "<NUMBER>.<sender>.<id>@txt.voice.google.com".
# Gmail search can't match the number inside that address, so search all Voice
# texts and check the sender here.
QUERY = "from:txt.voice.google.com"
CODE_RE = re.compile(r"\b(\d{3})[- ]?(\d{3})\b")


class Gmail:
    def __init__(self):
        self.lock = threading.Lock()
        self.creds = None
        self.can_modify = True

    def _creds(self):
        with self.lock:
            if self.creds is None or not self.creds.valid:
                scopes = [MODIFY] if self.can_modify else [READONLY]
                creds = google.oauth2.service_account.Credentials.from_service_account_file(
                    SA_FILE, scopes=scopes, subject=MAILBOX)
                try:
                    creds.refresh(google.auth.transport.requests.Request())
                except Exception:
                    if not self.can_modify:
                        raise
                    # gmail.modify not delegated yet: read-only, no cleanup.
                    self.can_modify = False
                    self.creds = None
                    return self._creds()
                self.creds = creds
            return self.creds

    def get(self, path, **params):
        r = requests.get(f"{GMAIL}/{path}", params=params, timeout=15,
                         headers={"Authorization": "Bearer " + self._creds().token})
        r.raise_for_status()
        return r.json()

    def post(self, path, body):
        r = requests.post(f"{GMAIL}/{path}", json=body, timeout=15,
                          headers={"Authorization": "Bearer " + self._creds().token})
        r.raise_for_status()
        return r.json() if r.content else {}


gmail = Gmail()


def body_text(payload):
    """Plain text of a message, from its text/plain part (or the body)."""
    parts = [payload]
    while parts:
        p = parts.pop(0)
        if p.get("mimeType") == "text/plain" and p.get("body", {}).get("data"):
            return base64.urlsafe_b64decode(p["body"]["data"]).decode("utf-8", "replace")
        parts.extend(p.get("parts", []))
    return ""


def to_number(msg):
    """True if a Voice text was sent to the reviewer number."""
    sender = next((h["value"] for h in msg["payload"].get("headers", [])
                   if h["name"].lower() == "from"), "")
    return re.search(rf"(?<!\d){NUMBER}\.\d+\.[^@]+@txt\.voice\.google\.com", sender) is not None


def latest_code():
    """(code, age_seconds) of the newest Signal code, or None."""
    listing = gmail.get("messages", q=f"{QUERY} newer_than:1d", maxResults=20)
    for ref in listing.get("messages", []):
        msg = gmail.get(f"messages/{ref['id']}", format="full")
        if not to_number(msg):
            continue
        age = time.time() - int(msg["internalDate"]) / 1000
        if age > CODE_MAX_AGE:
            return None  # newest first, so everything after is older
        text = body_text(msg["payload"])
        if "signal" not in text.lower():
            continue
        m = CODE_RE.search(text)
        if m:
            return m.group(1) + m.group(2), age
    return None


def cleanup():
    if not gmail.can_modify:
        return
    listing = gmail.get("messages", q=f"{QUERY} older_than:{CLEANUP_HOURS}h", maxResults=100)
    ids = [m["id"] for m in listing.get("messages", [])
           if to_number(gmail.get(f"messages/{m['id']}", format="metadata", metadataHeaders="From"))]
    if ids:
        gmail.post("messages/batchModify", {"ids": ids, "addLabelIds": ["TRASH"]})


def cleanup_loop():
    while True:
        try:
            cleanup()
        except Exception as e:
            print(f"cleanup failed: {e}", flush=True)
        time.sleep(3600)


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="10">
<title>Backchannel review code</title>
<style>
:root {{ --bg: #faf7fc; --fg: #2a2230; --muted: #6f6478; --accent: #8a5cb8; --card: #fff; }}
@media (prefers-color-scheme: dark) {{
  :root {{ --bg: #0d0b10; --fg: #ece6f2; --muted: #a99bb5; --accent: #c2a0e2; --card: #18141d; }}
}}
body {{ margin: 0; background: var(--bg); color: var(--fg);
  font: 16px/1.5 system-ui, sans-serif; display: grid; place-items: center; min-height: 100vh; }}
main {{ background: var(--card); padding: 32px 24px; border-radius: 16px; max-width: 420px;
  margin: 16px; box-shadow: 0 4px 24px rgba(0,0,0,.08); text-align: center; }}
h1 {{ font-size: 18px; margin: 0 0 16px; }}
.code {{ font: 600 44px/1.2 ui-monospace, monospace; letter-spacing: 4px; color: var(--accent); }}
p {{ color: var(--muted); margin: 12px 0 0; }}
</style></head>
<body><main>
<h1>Backchannel: Signal code for +{number}</h1>
{content}
<p>This page refreshes itself every 10 seconds.</p>
</main></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # no access log: nothing here is worth keeping

    def authorized(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Basic "):
            return False
        try:
            user, _, pw = base64.b64decode(auth[6:]).decode().partition(":")
        except Exception:
            return False
        return user == "reviewer" and hmac.compare_digest(pw, PASSWORD)

    def send(self, status, body, ctype="text/html; charset=utf-8", extra=()):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Robots-Tag", "noindex")
        for k, v in extra:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            return self.send(200, "ok\n", "text/plain")
        if self.path == "/robots.txt":
            return self.send(200, "User-agent: *\nDisallow: /\n", "text/plain")
        if not self.authorized():
            return self.send(401, "Login required\n", "text/plain",
                             [("WWW-Authenticate", 'Basic realm="Backchannel review"')])
        try:
            found = latest_code()
        except Exception as e:
            print(f"lookup failed: {e}", flush=True)
            content = "<p>Couldn't check for a code just now. It will retry.</p>"
        else:
            if found:
                code, age = found
                content = (f'<div class="code">{html.escape(code[:3])}-{html.escape(code[3:])}</div>'
                           f"<p>Received {int(age // 60)} min {int(age % 60)} s ago.</p>")
            else:
                content = ("<p>No code in the last 15 minutes.</p>"
                           "<p>In Backchannel, enter the number above and choose to get the code "
                           "by SMS. It shows up here within about a minute.</p>")
        self.send(200, PAGE.format(number=NUMBER, content=content))


if __name__ == "__main__":
    threading.Thread(target=cleanup_loop, daemon=True).start()
    print(f"listening on {HOST}:{PORT}, mailbox {MAILBOX}, number +{NUMBER}", flush=True)
    ThreadingHTTPServer((HOST, int(PORT)), Handler).serve_forever()
