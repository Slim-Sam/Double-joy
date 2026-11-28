#!/usr/bin/env python3
"""Создание релиза «Двойная радость X.Y.Z» на GitHub + хранение только последних 5 релизов.

Использование:
  python3 tools_make_release.py <PAT> <VERSION> <APK_PATH> [ОПИСАНИЕ]

Что делает:
  1) POST  /repos/Slim-Sam/Double-joy/releases        — релиз (tag vX.Y.Z, имя «Двойная радость X.Y.Z»)
  2) POST  uploads.github.com/.../assets              — APK-вложение (Двойная_радость_X.Y.Z.apk)
  3) GET   /repos/.../releases                        — оставить только 5 НОВЕЙШИХ, старые удалить
"""
import json
import sys
import urllib.parse
import urllib.request

REPO = "Slim-Sam/Double-joy"
API = f"https://api.github.com/repos/{REPO}"
UPLOADS = f"https://uploads.github.com/repos/{REPO}"
KEEP = 5


def req(url, token, data=None, method=None, content_type="application/json", raw=False):
    body = None
    if data is not None:
        body = data if raw else json.dumps(data).encode("utf-8")
    r = urllib.request.Request(url, data=body, method=method or ("POST" if data is not None else "GET"))
    r.add_header("Authorization", f"Bearer {token}")
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body is not None and not raw:
        r.add_header("Content-Type", content_type)
    elif raw:
        r.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(r) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"HTTP {e.code} на {url}\n{detail}")


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    pat, version, apk = sys.argv[1], sys.argv[2], sys.argv[3]
    note = sys.argv[4] if len(sys.argv) > 4 else (
        f"APK **Двойная радость {version}**. Обновление поверх предыдущей версии "
        f"(установка апгрейдом, без удаления)."
    )
    tag = f"v{version}"
    title = f"Двойная радость {version}"
    asset_name = f"Double-joy_{version}.apk"  # GitHub нормализует кириллицу

    with open(apk, "rb") as f:
        apk_bytes = f.read()
    print(f"APK: {apk} ({len(apk_bytes)} байт)")

    rel = req(f"{API}/releases", pat, {
        "tag_name": tag,
        "name": title,
        "body": note,
        "draft": False,
        "prerelease": False,
    })
    print(f"Релиз создан: {rel['html_url']} (id={rel['id']})")

    up_url = f"{UPLOADS}/releases/{rel['id']}/assets?" + urllib.parse.urlencode({"name": asset_name})
    asset = req(up_url, pat, data=apk_bytes,
                content_type="application/vnd.android.package-archive", raw=True)
    print(f"Артефакт: {asset.get('browser_download_url')}")

    rels = req(f"{API}/releases?per_page=100", pat)
    rels = [r for r in rels if not r.get("draft")]
    rels.sort(key=lambda r: r.get("created_at") or "", reverse=True)
    print(f"Всего релизов: {len(rels)}; оставляем {KEEP} новейших:")
    for r in rels[:KEEP]:
        print(f"  ✓ {r['name']} ({r['created_at']})")
    for r in rels[KEEP:]:
        print(f"  ✗ удаляю старый: {r['name']} ({r['created_at']})")
        req(f"{API}/releases/{r['id']}", pat, method="DELETE")
    print("Готово.")


if __name__ == "__main__":
    main()
