# SocaTV Nova — Claude Context

## FIRST THING TO DO AT SESSION START
```
cd C:\SocaTvNova
git pull origin main
```
Then read this file top to bottom before touching any code.

---

## Project Identity
- **App name:** SocaTV Nova
- **Package:** `com.socatv.nova`
- **Source:** `C:\SocaTvNova\` (this directory)
- **Git remote:** `https://github.com/CrestronDude/socatv-releases` (main branch)
- **GitHub token:** stored in `C:\apktools\admin-panel\.env` as `GITHUB_TOKEN`
- **Current version:** Check `app/build.gradle` → `versionCode` / `versionName`
- **APK output:** `C:\apktools\SocaTvNova.apk` (signed, ready to distribute)
- **Bit.ly:** `https://bit.ly/SocaTvNova_app` → always resolves to latest GitHub release APK

---

## Build Commands
```bat
:: Debug build
cd C:\SocaTvNova
.\gradlew.bat assembleDebug

:: Release build
.\gradlew.bat assembleRelease

:: Sign release (run after assembleRelease)
:: Keystore: C:\apktools\socatv.keystore  alias=socatv  pass=socatv123
set BT=C:\Android\Sdk\build-tools\36.0.0
%BT%\zipalign.exe -f -v 4 app\build\outputs\apk\release\app-release-unsigned.apk C:\apktools\SocaTvNova_aligned.apk
%BT%\apksigner.bat sign --ks C:\apktools\socatv.keystore --ks-key-alias socatv --ks-pass pass:socatv123 --key-pass pass:socatv123 --out C:\apktools\SocaTvNova.apk C:\apktools\SocaTvNova_aligned.apk

:: Or just run:
build_nova.bat release
```

---

## Release a New Version (full workflow)
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle`
2. Build + sign → `C:\apktools\SocaTvNova.apk`
3. `git add -A && git commit -m "vX.X.X — changes" && git push origin main`
4. Create GitHub release tag `vN` (N = new versionCode) + upload `SocaTvNova.apk`
5. Run `node C:\apktools\push_config.js` (recreate with updated latestVersionCode) to sign and push Gist
6. Existing devices get update prompt within 24h automatically

---

## Key Files

| File | Purpose |
|------|---------|
| `app/build.gradle` | versionCode, versionName, dependencies |
| `app/src/main/AndroidManifest.xml` | permissions, activities |
| `utils/RemoteConfigManager.kt` | HMAC-signed Gist config fetch + cache |
| `utils/AppUpdater.kt` | OTA update check + install |
| `utils/UpdateCheckWorker.kt` | Daily background update check (WorkManager) |
| `utils/PanelAvailability.kt` | Probes live/vod/series counts after login |
| `utils/DeviceReporter.kt` | Check-in to admin panel, reads API key from RemoteConfig |
| `utils/SecurityWorker.kt` | Background blacklist check every 15 min |
| `utils/AppSecurity.kt` | Cert fingerprint check on launch |
| `utils/LicenseManager.kt` | Offline HMAC license key validation |
| `utils/TrialManager.kt` | Trial period tracking |
| `utils/ServerAutoSelector.kt` | Tries multiple panel servers silently on login |
| `utils/Prefs.kt` | SharedPreferences wrapper |
| `ui/splash/SplashActivity.kt` | Single coroutine: config fetch → update check → navigate |
| `ui/home/PanelPickerActivity.kt` | Main hub, 13-panel grid, availability dimming |
| `ui/login/LoginActivity.kt` | Multi-server login + triggers PanelAvailability probe |
| `build_nova.bat` | Build + sign + deploy script |
| `sync.bat` | Git pull → build → commit → push (one command) |

---

## Remote Config (Gist)
- **URL:** `https://gist.githubusercontent.com/CrestronDude/01b5363984ee9c951219ff8feaafae19/raw/nova_config.json`
- **HMAC signing secret:** `N0v@C0nfigSig#2024!K3y` (split in code: `"N0v@C0nf"+"igSig#20"+"24!K3y"`)
- **Signed fields (sorted):** `appEnabled`, `checkinApiKey`, `forceUpdate`, `latestVersionCode`, `trialDays`
- **Payload format:** `appEnabled=true|checkinApiKey=VALUE|forceUpdate=false|latestVersionCode=5|trialDays=10`
- To push a new signed config: write and run `C:\apktools\push_config.js` (see template below)

### push_config.js template
```js
const crypto = require("crypto");
const https  = require("https");
const SECRET   = "N0v@C0nf" + "igSig#20" + "24!K3y";
const API_KEY  = "NovaTv-Checkin-K3y-2024";
const GIST_ID  = "01b5363984ee9c951219ff8feaafae19";
const GH_TOKEN = process.env.GITHUB_TOKEN; // from C:\apktools\admin-panel\.env
const config = {
  trialDays: 10, priceLabel: "$10 / year", paymentUrl: "", activationEnabled: true,
  appEnabled: true, announcement: "Welcome to SocaTV Nova!", minVersionCode: 1,
  forceUpdateUrl: "", reportingUrl: "", downloadUrl: "https://bit.ly/SocaTvNova_app",
  latestVersionCode: 5,   // <-- UPDATE THIS
  latestVersionName: "1.2.0",  // <-- UPDATE THIS
  latestApkUrl: "https://github.com/CrestronDude/socatv-releases/releases/download/v5/SocaTvNova.apk",
  forceUpdate: false, panelServers: [], checkinApiKey: API_KEY,
  features: { qrLogin: true, m3uImport: true, xtreamImport: true, multiscreen: true, epg: true }
};
const sf = { appEnabled: config.appEnabled, checkinApiKey: config.checkinApiKey,
  forceUpdate: config.forceUpdate, latestVersionCode: config.latestVersionCode, trialDays: config.trialDays };
const payload = Object.keys(sf).sort().map(k => `${k}=${sf[k]}`).join("|");
config.sig = crypto.createHmac("sha256", SECRET).update(payload,"utf8").digest("hex");
const body = JSON.stringify({ files: { "nova_config.json": { content: JSON.stringify(config, null, 2) } } });
const req = https.request({ hostname:"api.github.com", path:`/gists/${GIST_ID}`, method:"PATCH",
  headers:{ Authorization:`token ${GH_TOKEN}`, "User-Agent":"SocaTvAdmin/1.0",
    "Content-Type":"application/json", "Content-Length":Buffer.byteLength(body) }},
  res => { let d=""; res.on("data",c=>d+=c); res.on("end",()=>{ const r=JSON.parse(d);
    console.log("Gist updated:", r.html_url); }); });
req.write(body); req.end();
```

---

## Admin Panel
- **Location:** `C:\apktools\admin-panel\`
- **URL:** `http://localhost:7890`
- **Start:** PM2 auto-starts it on Windows login (Task Scheduler → "SocaTV Admin Panel")
- **Manual start:** `pm2 start socatv-admin` or `pm2 resurrect`
- **Status check:** `pm2 list`
- **Logs:** `pm2 logs socatv-admin`
- **Secrets:** `C:\apktools\admin-panel\.env` (GITHUB_TOKEN, BITLY_TOKEN — never commit this)
- **Database:** `C:\apktools\admin-panel\nova_data.json`
  - `settings.checkinApiKey` = `NovaTv-Checkin-K3y-2024`
  - `settings.configSigningSecret` = `N0v@C0nfigSig#2024!K3y`
  - `settings.licenseSecret` = `pShzo7G5Y1zoFPfwuUC3KEn6ixzqWycz`

---

## Security Architecture
| Secret | Location | How protected |
|--------|----------|---------------|
| Signing cert fingerprint | `AppSecurity.kt` | Split string, kills non-genuine APKs |
| HMAC config signing key | `RemoteConfigManager.kt` | Split string (`N0v@C0nf`+`igSig#20`+`24!K3y`) |
| License HMAC secret | `LicenseManager.kt` | Split string (`pShzo7G5`+`Y1zoFPfw`+...) |
| Check-in API key | Remote Config (Gist) | Signed — never hardcoded in APK |
| GitHub token | `.env` only | Gitignored, never in source |
| Bitly token | `.env` only | stored as `BITLY_TOKEN` in `C:\apktools\admin-panel\.env` |
| Keystore | `C:\apktools\socatv.keystore` | alias=socatv, pass=socatv123 |

---

## Device / ADB
- **TV IP:** `10.0.0.42:5555`
- **ADB:** `C:\Android\Sdk\platform-tools\adb.exe`
- **Package on device:** `fyeo.customized.xsiptv.socatv` (original) and `com.socatv.nova` (Nova)
- **Install:** `adb connect 10.0.0.42:5555 && adb install -r C:\apktools\SocaTvNova.apk`

---

## GitHub Releases (tag format: vN where N = versionCode)
| Tag | Version | Notes |
|-----|---------|-------|
| v1 | 1.0.0 | Initial release |
| v2 | 1.1.0 | Security update |
| v3 | 1.1.1 | Download link fix |
| v4 | 1.1.2 | Login server list fix |
| v5 | 1.2.0 | Auto-update + smart panel picker + HMAC config |

---

## Git Workflow
```bat
:: Always pull before starting work
git pull origin main

:: After making changes
git add -A
git commit -m "vX.X.X — description of change"
git push origin main

:: Or use sync.bat which does it all
sync.bat "your commit message"
```

---

## What NOT to do
- Never change `versionCode` without also updating the Gist `latestVersionCode` and creating a GitHub release
- Never hardcode API keys, tokens, or signing secrets — they live in split strings or `.env`
- Never commit `.env` or `*.jks` / `*.keystore` files
- Never use `git push --force` on main
- The cert pins were removed from `RemoteConfigManager.kt` intentionally — wrong pins silently break all config fetches
