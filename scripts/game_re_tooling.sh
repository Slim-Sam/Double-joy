#!/usr/bin/env bash
# Восстановление реверс-инструментов игры (Last Asylum / com.phs.global) ПОСЛЕ чистки бюджета.
# Всё тяжёлое удалено из рабочего пространства; этот скрипт пересоздаёт его (~2-3 мин, нужен интернет).
# Использовалось для: расшифровки Lua (cm/dec), libil2cpp.so, dump.cs (см. ИЗУЧЕНИЕ_СЕРВЕР_РУК.md).
set -e
W=/home/user
CM=$W/cm
mkdir -p "$CM/ab" "$CM/dec" "$CM/dumper"

# 1. XAPK игры (507 МБ; UA+Referer обязательны — Cloudflare)
[ -f "$W/game.xapk" ] || curl -L -s -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36" \
  -e "https://apkpure.com/" -o "$W/game.xapk" "https://d.apkpure.com/b/XAPK/com.phs.global?versionCode=121"

# 2. Распаковка компонентов
mkdir -p "$W/xapk" && unzip -o -q "$W/game.xapk" -d "$W/xapk"
unzip -o -q "$W/xapk/abPack1.apk" "assets/ABAsset/luascript*" -d "$CM/ab/"
unzip -o -q "$W/xapk/com.phs.global.apk" "assets/bin/Data/Managed/Metadata/global-metadata.dat" -d /tmp/

# 3. Расшифровка Lua: XOR eyugame_lua_zombiesrpg, признак \x1bLuaT; КОЛЛИЗИИ ИМЁН сохранять (__path_id)!
pip install -q UnityPy pillow capstone 2>/dev/null || true
python3 - <<'PY'
import os, glob, re, UnityPy
KEY = b"eyugame_lua_zombiesrpg"
files = sorted(glob.glob('/home/user/cm/ab/assets/ABAsset/luascript*.assetbundles'))
ok = coll = 0
for fp in files:
    env = UnityPy.load(fp)
    for obj in env.objects:
        if obj.type.name != "TextAsset":
            continue
        raw = obj.read().m_Script
        if isinstance(raw, str):
            raw = raw.encode('utf-8', 'ignore')
        dec = bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(raw))
        if dec[:5] != b'\x1bLuaT':
            continue
        name = re.sub(r'[^A-Za-z0-9_]', '_', os.path.basename(fp).split('.')[0])
        out = f'/home/user/cm/dec/{name}.luac'
        if os.path.exists(out):
            out = f'/home/user/cm/dec/{name}__{obj.path_id}.luac'
            coll += 1
        open(out, 'wb').write(dec)
        ok += 1
print(f"decrypted={ok} collisions_renamed={coll}")
PY

# 4. Извлечение нативного кода + дампер C# (dump.cs с RVA всех функций)
unzip -o -q "$W/xapk/config.arm64_v8a.apk" "lib/arm64-v8a/libil2cpp.so" -d "$CM/"
[ -d "$W/.dotnet" ] || { curl -sSL https://dot.net/v1/dotnet-install.sh -o /tmp/di.sh \
  && bash /tmp/di.sh --channel 8.0 --runtime dotnet --install-dir "$W/.dotnet" >/dev/null; }
cd "$CM/dumper"
if [ ! -f Il2CppDumper.dll ]; then
  curl -sL -o d.zip "https://github.com/Perfare/Il2CppDumper/releases/download/v6.7.46/Il2CppDumper-net7-v6.7.46.zip"
  unzip -o -q d.zip && rm -f d.zip
fi
export DOTNET_ROLL_FORWARD=Major
printf "1\n" | "$W/.dotnet/dotnet" Il2CppDumper.dll \
  "$CM/lib/arm64-v8a/libil2cpp.so" /tmp/global-metadata.dat "$CM/dump" || true
# dump.cs и stringliteral.json появятся в $CM/dumper/ (даже при ERROR: protected)
ls -la dump.cs stringliteral.json

# Готово: cm/dec (Lua), cm/lib (libil2cpp.so), cm/dumper/dump.cs — как и до чистки.
# Тяжёлое можно снова удалить: git-архив dump.cs → см. маркер cm_УДАЛЕНО_СМОТРИ_GITHUB.txt
