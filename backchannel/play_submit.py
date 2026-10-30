#!/usr/bin/env python3
"""Send the draft release on a Play track for review (status completed).

Used when a release was uploaded as a draft (Actions variable
PLAY_RELEASE_STATUS=draft) so Mike could check the build first.
Uses the service account JSON at $PLAY_JSON.
"""
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build

PACKAGE = "net.mikesimone.backchannel"
TRACK = sys.argv[1] if len(sys.argv) > 1 else "alpha"

creds = service_account.Credentials.from_service_account_file(
    os.environ["PLAY_JSON"], scopes=["https://www.googleapis.com/auth/androidpublisher"]
)
edits = build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()
edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]

track = edits.tracks().get(packageName=PACKAGE, editId=edit_id, track=TRACK).execute()
drafts = [r for r in track.get("releases", []) if r.get("status") == "draft"]
if not drafts:
    sys.exit(f"No draft release on {TRACK}; nothing to send.")
draft = max(drafts, key=lambda r: max(int(v) for v in r.get("versionCodes", ["0"])))
draft["status"] = "completed"
print(f"Sending {draft.get('name')} ({', '.join(draft.get('versionCodes', []))}) on {TRACK} for review")
track["releases"] = [draft]
edits.tracks().update(packageName=PACKAGE, editId=edit_id, track=TRACK, body=track).execute()
edits.commit(packageName=PACKAGE, editId=edit_id).execute()
print("Committed.")
