# Last Asylum: Plague (com.phs.global) — разбор XAPK v1.0.121 (versionCode 121)

Источник: https://d.apkpure.com/b/XAPK/com.phs.global?versionCode=121 (507 712 136 байт)

## Структура XAPK

| Файл | Размер | Содержимое |
|---|---|---|
| com.phs.global.apk | 47,9 МБ | базовый APK: Unity IL2CPP-обвязка, 4 DEX, global-metadata.dat |
| abPack1.apk | 408 МБ | Unity AssetBundles (графика, Lua-скрипты, таблицы локализаций) |
| config.arm64_v8a.apk | 51,4 МБ | native-библиотеки arm64 |
| config.en.apk | 66 КБ | английские ресурсы |
| manifest.json | — | метаданные XAPK |

- Пакет: `com.phs.global`, versionName 1.0.121, versionCode 121
- minSdk 24 (Android 7.0), targetSdk 36, installLocation=2 (external)
- SDK Unity + **IL2CPP** (`libil2cpp.so` 98 МБ) + **xlua** (`libxlua.so`)
- Звук: Wwise (`libAkUnitySoundEngine.so` 41,8 МБ), A* Pathfinding, Burst (`lib_burst_generated.so`)
- Прочие .so: libRSDKNative, libjndcrash, libmerit-trace-canary, libPenInkSDK, libBlueDoveMediaRender, libucrop

## Разрешения (манифест)

Интернет и сеть; **Google Play Billing + CHECK_LICENSE**; SYSTEM_ALERT_WINDOW (оконный оверлей); уведомления; вибро; WAKE_LOCK; RECEIVE_BOOT_COMPLETED; GET_TASKS; **READ_LOGS**; WRITE_SETTINGS; USE_BIOMETRIC/FINGERPRINT; AD_ID + AdServices (Topics/CustomAudience/Attribution); push-разрешения Getui, MiPush (com.phs.global.permission.MIPUSH_RECEIVE), C2DM; Samsung/Huawei-специфичные; установка/замена пакетов (BROADCAST_PACKAGE_*), RESTART_PACKAGES.

## Ключевые компоненты из манифеста

**Активности (23):** SDK-обвязки — `com.games37.sdk` (37Games), Getui push, Google Play Billing proxy, Facebook login, ucrop/PicCropper, Play Games, `com.lapslg.sdk` (LapSLG: login, Google Play purchase, WebView, floatview GameHelperActivity).

**Сервисы (25):** Getui (GTPushService, FcmPushService), Firebase Messaging, Firebase components, AppMeasurement, Play Core asset packs, LapSLG Firebase.

**Ресиверы (19):** ThinkingData (`cn.thinkingdata.analytics`), AppsFlyer (`MultipleInstallBroadcastReceiver`), Firebase IID, Facebook, Unity notifications.

## SDK / аналитика / реклама (из DEX)

- **Аналитика:** ThinkingData (TDReceiver), Firebase/Google Analytics (app-measurement.com), AppsFlyer (attrIBUTION, LVL), TikTok Business SDK (business-api.tiktok.com)
- **Push:** Getui (c-hzgt2/s-gt.getui.com), Firebase Cloud Messaging
- **Соц./логин:** Facebook Login/AppLinks (fb.gg), Google Sign-In, Play Games
- **Платежи:** Google Play Billing V3/V5
- **Краш-логи:** jndcrash, merit-trace-canary; SDK-логи: logcenter.octlib.com
- **Внутренний SDK:** lapslg (логин, покупки, floatview-помощник), games37 (37Games), OpenUDID
- Ссылки на RuStore: https://www.rustore.ru/catalog/app/ (сборка мультиплатформенная)

## Домены

getui.com (push), app-measurement.com, business-api.tiktok.com, graph.facebook.com, appsflyersdk.com, logcenter.octlib.com, 37games.com, baidu.com, DNS: 223.5.5.5 / 8.8.8.8 / cloudflare-dns.com.

## Game data (abPack1)

- **404 Lua-бандла** (`luascript_*.assetbundles`) — логика игры на xLua, включая activity-модули (eliminatemaster, minicardmaster, luckyseven, crystalriddle и др.)
- Таблицы данных `gamedata_basedb_f_<locale>` на 20+ языков (ru, en, zh-cn, ja, ko, de, fr, es, pt, it, pl, nl, tr, vi, th, id, ms, ar, zh-tw)
- Локализации `gamedata_language_f_<locale>`
- Аудио-банки Wwise, иллюстрации CG, префабы эффектов

## Важно про ивент Thanksgiving 2026 («Двойная радость» / Joy Multiplied)

В собранном APK **нет** строк «Двойная радость», «Joy Multiplied», «Cranberry» (кроме нерелевантного контекста) и нет Lua-бандла мини-игры. Игра при запуске показывает экран «Загрузка файла версии игры» — контент ивента (Lua, таблицы, локализация) доставляется **серверным хот-обновлением**, а не входит в XAPK. Поэтому правила/конфиги мини-игры в этом билде прочитать нельзя — только обвязка.

## global-metadata.dat (IL2CPP)

15,17 МБ, ~141 590 строк: имена классов/методов C#, пути `\Assets\Script\...` (1591 игровой .cs), строки Unity/XLua/NativeSDK. Полезен для навигации по коду, но тело методов — в `libil2cpp.so` (нужен Il2CppDumper). Тяжёлые файлы после анализа удалены (перекачиваются по ссылке за ~25 с); сохранены только отчёт, `xapk/manifest_decoded.xml`, `xapk/manifest.json`, `xapk/icon.png`.

## Как это читалось

1. curl (с браузерным UA — Cloudflare без него отдаёт 403) → game.xapk
2. unzip → 4 APK
3. androguard (pip) → бинарный AndroidManifest.xml → manifest_decoded.xml
4. UnityPy (pip) → TextAsset внутри AssetBundles
5. strings/regex по DEX и global-metadata.dat
