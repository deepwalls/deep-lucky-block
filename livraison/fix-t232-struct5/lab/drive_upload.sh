#!/usr/bin/env bash
# T232 : upload Drive via compte de service, sans navigateur.
# Prerequis : deep-lucky-block/service-account.json + DRIVE_FOLDER_ID
# (le dossier Drive doit etre partage a l'e-mail du compte de service).
set -e
cd "$(dirname "$0")"
python3 -m pip install --quiet --user google-api-python-client google-auth 2>/dev/null \
  || pip3 install --quiet --user google-api-python-client google-auth
python3 - << 'PYEOF'
import os, sys
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

KEY = '/home/user/deep-lucky-block/service-account.json'
FOLDER = os.environ.get('DRIVE_FOLDER_ID', '')
if not os.path.exists(KEY):
    sys.exit('ERREUR: service-account.json absent (deposez-le a la racine du repo)')
if not FOLDER:
    sys.exit('ERREUR: definissez DRIVE_FOLDER_ID=<id du dossier Drive>')

creds = service_account.Credentials.from_service_account_file(
    KEY, scopes=['https://www.googleapis.com/auth/drive.file'])
drv = build('drive', 'v3', credentials=creds)

BASE = '/home/user/deep-lucky-block/livraison/fix-t232-struct5'
FILES = [
    ('T232 version finale', f'{BASE}/DEEP-LUCKY-BLOCK-T232-STRUCT5-FIX.zip'),
    ('note matrice',        f'{BASE}/T232-NOTE.txt'),
    ('Structures5Procedure.java', f'{BASE}/src/main/java/deepluckyblock/procedures/Structures5Procedure.java'),
    ('SafeSurface.java',    f'{BASE}/src/main/java/deepluckyblock/util/SafeSurface.java'),
    ('ChunkKeeper.java',    f'{BASE}/src/main/java/deepluckyblock/util/ChunkKeeper.java'),
    ('DebugLog.java',       f'{BASE}/src/main/java/deepluckyblock/util/DebugLog.java'),
]
for label, path in FILES:
    body = {'name': os.path.basename(path), 'parents': [FOLDER]}
    media = MediaFileUpload(path, resumable=True)
    f = drv.files().create(body=body, media_body=media,
                           fields='id,name,webViewLink').execute()
    drv.permissions().create(fileId=f['id'],
        body={'type': 'anyone', 'role': 'reader'}).execute()
    f2 = drv.files().get(fileId=f['id'], fields='webViewLink').execute()
    print(f"[OK] {label:28s} -> {f2['webViewLink']}")
print('TERMINE')
PYEOF
