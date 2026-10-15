#!/usr/bin/env python3
"""Push the store listing in fastlane/metadata/android to Google Play.

Text (title, short and full description) and graphics (icon, feature
graphic, phone screenshots) for each language folder. Uses the service
account JSON at $PLAY_JSON. Changelogs are left to the Release workflow.
"""
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "net.mikesimone.backchannel"
ROOT = sys.argv[1] if len(sys.argv) > 1 else "fastlane/metadata/android"
IMAGES = {"icon.png": "icon", "featureGraphic.png": "featureGraphic"}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


creds = service_account.Credentials.from_service_account_file(
    os.environ["PLAY_JSON"], scopes=["https://www.googleapis.com/auth/androidpublisher"]
)
edits = build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()
edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]

for lang in sorted(os.listdir(ROOT)):
    d = os.path.join(ROOT, lang)
    if not os.path.isdir(d):
        continue
    body = {
        "language": lang,
        "title": read(os.path.join(d, "title.txt")),
        "shortDescription": read(os.path.join(d, "short_description.txt")),
        "fullDescription": read(os.path.join(d, "full_description.txt")),
    }
    edits.listings().update(packageName=PACKAGE, editId=edit_id, language=lang, body=body).execute()
    print(f"{lang}: text updated")

    images = os.path.join(d, "images")
    for name, kind in IMAGES.items():
        path = os.path.join(images, name)
        if os.path.exists(path):
            edits.images().deleteall(packageName=PACKAGE, editId=edit_id, language=lang, imageType=kind).execute()
            edits.images().upload(packageName=PACKAGE, editId=edit_id, language=lang, imageType=kind,
                                  media_body=MediaFileUpload(path, mimetype="image/png")).execute()
            print(f"{lang}: {kind} uploaded")
    shots = os.path.join(images, "phoneScreenshots")
    files = sorted(f for f in os.listdir(shots) if f.endswith(".png")) if os.path.isdir(shots) else []
    if files:
        edits.images().deleteall(packageName=PACKAGE, editId=edit_id, language=lang, imageType="phoneScreenshots").execute()
        for f in files:
            edits.images().upload(packageName=PACKAGE, editId=edit_id, language=lang, imageType="phoneScreenshots",
                                  media_body=MediaFileUpload(os.path.join(shots, f), mimetype="image/png")).execute()
        print(f"{lang}: {len(files)} phone screenshots uploaded")

# A draft app can't send changes for review yet; Play holds them until it can.
try:
    edits.commit(packageName=PACKAGE, editId=edit_id).execute()
except Exception as e:  # noqa: BLE001
    if "changesNotSentForReview" not in str(e):
        raise
    edits.commit(packageName=PACKAGE, editId=edit_id, changesNotSentForReview=True).execute()
print("committed")
