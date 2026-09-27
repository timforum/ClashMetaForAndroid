# Clash Meta for Android (Meta-Alpha fork)

A graphical user interface of [mihomo](https://github.com/MetaCubeX/mihomo) (the community fork of Clash.Meta) for Android.

This repository is a fork of [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) that:

1. Tracks the **mihomo `Alpha`** kernel (via git submodule) instead of the older `android-real` branch.
2. Adds **ProbTest** — a candidate-node screening engine that turns your phone into a probe: it periodically downloads candidate subscriptions, tests every node over multiple rounds, keeps only the nodes that survive all rounds, and publishes the clean result to your subscription endpoint or a GitHub repository.

## Features

Everything from upstream ClashMetaForAndroid / mihomo:

- TUN (VPN) mode and system HTTP-proxy mode
- Full mihomo protocol support: Shadowsocks, VMess, VLESS (+ Reality), Trojan, Hysteria2, SSH, WireGuard, Snell, Tuic, etc.
- Rule providers, geosite / geoip matching, override (config patching)
- Periodic subscription update, profile management, app-based rules
- Health check, latency test, selector / group management
- Log viewer (kernel logcat)

Plus this fork:

- **mihomo Alpha kernel** — latest in-development kernel features (AnyTLS, new protocols, etc.)
- **ProbTest candidate-node screening** — see [ProbTest](#probtest-candidate-node-screening) below

## Requirements

- Android 5.0+ (minimum), Android 7.0+ (recommended)
- `armeabi-v7a`, `arm64-v8a`, `x86` or `x86_64` architecture

## Architecture

### Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | application | Main UI: all `Activity`, launcher, quick-settings tile, deep-link / external-intent handling |
| `:service` | library | `:background` process: foreground `ClashService`, kernel lifecycle, profile management, **ProbTest pipeline** |
| `:core` | library | **Core**: JNI bridge + Golang native (mihomo wrapper + ProbTest engine); contains the mihomo git submodule |
| `:design` | library | UI design system: settings-page DSL, preference components, string resources |
| `:common` | library | Cross-process shared: constants (`Intents`), `Global`, multi-process store, log, util |
| `:hideapi` | library | Android hidden-API reflection stubs (`compileOnly`) |

The app uses a **two-process** model: `:app` (UI process) and `:service` (`:background` process). They talk over AIDL/binder plus multi-process `SharedPreferences`. `MainApplication` dispatches by process name — the UI process launches the remote service, the service process reports its recreation.

### Native stack (3 layers)

```
Kotlin   (core/.../Clash.kt, bridge/Bridge.kt)
   |  JSON over JNI
C        (core/src/main/cpp/main.c, JNIEXPORT wrappers)
   |  cgo //export
Golang   (core/src/main/golang/native/*.go)
   |  direct calls
mihomo   (core/src/foss/golang/clash  =  MetaCubeX/mihomo @ Alpha, git submodule)
```

The `golang-android` Gradle plugin compiles `core/src/foss/golang` into `libclash.so` (build tags `foss,with_gvisor,cmfa`); CMake then links `libclash.so` + `main.c` into **`libbridge.so`**, which `Bridge.kt` loads. The mihomo submodule is wired in through `go.mod`:

```
require github.com/metacubex/mihomo v1.7.0
replace github.com/metacubex/mihomo => ../../foss/golang/clash
```

so the kernel that actually gets compiled is the local submodule checkout (the `Alpha` branch).

### How the kernel runs

1. Toggling the switch in the UI process binds to `ClashService` in the `:background` process.
2. `ClashRuntime` calls `Clash.reset()` (JNI `nativeReset` → golang `reset()` → mihomo default-config reload + cleanup).
3. `ConfigurationModule` runs `Clash.load(path)` (JNI `nativeLoad` → golang `load()` → mihomo `ParseRawConfig` → executor start).
4. **TUN mode**: `TunService` (Android VPN) creates the tun fd, then `Clash.startTun(...)` hands it to mihomo; golang calls back into the JVM for socket marking / UID lookup.
5. **HTTP-proxy mode**: `Clash.startHttp(listenAt)` starts a local listener; the system proxy can also be set.

### Subscriptions / profiles

- `ProfileManager` stores profiles in Room and writes each one to `files/{pending,imported}/<uuid>/config.yaml`.
- URL profiles are downloaded and validated by golang `fetchAndValid`; processed ones are moved to `imported/`.
- `ProfileWorker` / `ProfileReceiver` refresh subscriptions on a schedule (AlarmManager).
- Deep links `clash://install-config?url=` / `clashmeta://install-config?url=` import a profile via `ExternalControlActivity`.
- Geo data: `geoip.metadb` / `geosite.dat` (extracted from assets at startup) + an embedded `Country.mmdb` (Go `//go:embed`) + optional sideload override.

## ProbTest: candidate-node screening

ProbTest continuously maintains a working set of free nodes. It downloads a set of candidate subscriptions, probes every node over multiple rounds, drops any node that fails even one round, rewrites the config so it only contains the survivors, and publishes the result.

### How a round works

```
ProbTestSettingsActivity (UI process)
   |  "Run a round now" -> startForegroundService(ProbTestWorker)
ProbTestWorker / ProbTestReceiver (background process, foreground Service)
   |  ProbTestPipeline.run(onProgress)
   |
   |  1. DOWNLOADING   OkHttp fetches each candidate subscription
   |                   (base64-decode fallback, 8 MB cap)
   |  2. SCREENING     Clash.probTest(...)  ->  JNI -> golang
   |                   probtest.Merge(docs) + probtest.Run(3 rounds)
   |  3. PUBLISHING    either:
   |                   a) POST {proxies:[...]} to your upload endpoint (Bearer token)
   |                   b) PUT the full config to a GitHub repo via Contents API
   |
   |  progress + result: broadcast + persisted state + foreground notification
   |
ProbTestReceiver.scheduleNext() re-arms the AlarmManager for the next round
```

Key design points:

- **Multi-round elimination** — 3 rounds (fixed), 20 s between rounds, 10 s per-node timeout, 16 concurrent probes. A node must pass *every* round to survive.
- **Does not touch the live tunnel** — probes build proxy objects directly and call `URLTest`; the running configuration is never affected.
- **Merges multiple subscriptions** — same-named nodes are renamed (not silently dropped); `proxy-groups` / `rules` are taken from the first candidate that declares them; `proxy-providers` are always removed so untested nodes are never published.
- **Safety** — dangerous VLESS flows and SSH private-key paths are rejected up front (they would make mihomo `os.Exit` or read arbitrary files); downloads are capped at 8 MB; `http://` URLs are auto-upgraded to `https://`; sensitive keys (`secret`, `external-controller*`, `authentication`, …) are stripped before publishing.
- **Exclusive replacement** — the upload endpoint receives only `{proxies:[...]}` and replaces/merges its own pool; the GitHub target receives the full rewritten config (PUT with `sha` to avoid conflicts) with a commit message like `chore: publish 42/120 nodes passing 3/3 rounds from 2 candidate subscriptions`.

### Configuration

Open **Settings → ProbTest** (the "Enable screening" switch). All values are stored encrypted in the service's `ServiceStore`.

| Setting | Default | Notes |
|---|---|---|
| **Enable screening** | off | Master switch; disables all other items when off |
| **Test imported subscriptions** | on | Include every imported URL profile as a candidate |
| **Extra subscriptions** | empty | One URL per line, added to the candidates |
| **Interval in minutes** | 30 | Schedule interval (minimum 5) |
| **Seconds between rounds** | 20 | Gap between probe rounds |
| **Test URL** | `http://cp.cloudflare.com/generate_204` | Each node's exit must be able to reach it |
| **GitHub Token / Repository / Branch / Path** | — / — / `main` / `clash/config.yaml` | Token needs `contents:write`; publishes the **full** config |
| **Upload URL** | empty | **Takes priority over GitHub**; POSTs only `{proxies:[...]}` to an endpoint (e.g. a Cloudflare Worker) |
| **Upload token** | empty | Bearer token for the upload endpoint |
| **Run a round now** | — | Triggers a single round immediately (works even when the schedule is disabled) |

Publish target: if **Upload URL** is set it wins; otherwise the **GitHub** target is used. At least one of the two must be configured.

### Running it

- **Manually**: tap **Run a round now**. A foreground notification tracks progress (download → round x/3 alive/failed → publishing), and the result is shown on the page.
- **On a schedule**: enable the switch and set the interval. The `AlarmManager` re-arms after every round (minimum 5-minute gap) and re-schedules on boot / package update / timezone change.

## Build

### Prerequisites

- **OpenJDK 17** (CI uses 17; AGP 7.2.1 also accepts 11)
- **Android SDK**, **CMake**, **Golang 1.22**
- NDK `23.0.7599858`

### Steps

1. Update submodules (fetches the mihomo `Alpha` kernel)

   ```bash
   git submodule update --init --recursive
   ```

2. Create `local.properties` in the project root

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

3. *(Optional, for a signed release)* create `signing.properties` in the project root

   ```properties
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

   The keystore file itself (`release.keystore`) is committed to the repository. Without `signing.properties` the release build is produced unsigned.

4. Build

   ```bash
   ./gradlew app:assembleMeta-AlphaDebug      # debug, default flavor (meta-alpha)
   ./gradlew app:assembleMeta-AlphaRelease    # release, meta-alpha flavor
   ./gradlew app:assembleMetaRelease          # release, meta flavor
   ```

   Flavors (dimension `feature`): `meta-alpha` (default) and `meta`. Both append `applicationIdSuffix = ".meta"`, so the final package name is **`com.github.metacubex.clash.meta`**.

   Output: `app/build/outputs/apk/meta-alpha/release/*-{universal,arm64-v8a,armeabi-v7a,x86_64,x86}-*.apk` (ABI splits + universal).

### Geo-data tasks

- `:app:downloadGeoFiles` — downloads `geoip.metadb` / `geosite.dat` from `MetaCubeX/meta-rules-dat` into `app/src/main/assets`. Every `assemble*` task depends on it.
- `:core:downloadGeoipDatabase` — downloads `Country.mmdb` from `Loyalsoldier/geoip`, embedded into the binary via Go `//go:embed` (7-day cache). Run before a Golang build.

## Automation

The package name is `com.github.metacubex.clash.meta`.

- Toggle the service: send intent to `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.TOGGLE_CLASH`
- Start the service: action `com.github.metacubex.clash.meta.action.START_CLASH`
- Stop the service: action `com.github.metacubex.clash.meta.action.STOP_CLASH`
- Import a profile: URL scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

## Continuous integration

| Workflow | Trigger | What it does |
|---|---|---|
| **Build Debug** | PR opened/sync + manual | `submodule update --remote --force`, Java 17 + Go 1.22, `app:assembleMeta-AlphaRelease` (unsigned), uploads the 5 ABI APKs as artifacts |
| **Build Pre-Release** | manual + PR merged to main | signs, `assembleMeta-AlphaRelease`, deletes the old `Prerelease-alpha` release and re-publishes it |
| **Build Release** | manual (fill `release-tag` e.g. `v2.10.2`) | converts the tag to `versionName`/`versionCode`, rewrites `build.gradle.kts` + commit + tag, signs, `app:assembleMetaRelease`, publishes a formal Release + changelog |
| **Update Dependencies** | `repository_dispatch: core-updated` (fired by the upstream kernel) + manual | pulls the latest mihomo, runs `:core:downloadGeoipDatabase`, runs `update-go-mod-replace` + `go mod tidy`, opens an "Update Dependencies" PR |

## Relationship to upstream

- **Upstream**: [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) (itself the Meta fork of kr328/ClashForAndroid). This repository is a fork of it.
- **Kernel source**: upstream pointed at the `android-real` branch of `MetaCubeX/Clash.Meta`; this fork points the submodule directly at **`MetaCubeX/mihomo` `Alpha`**.
- **Fork-specific commits**:
  - `chore(core): bump mihomo to Alpha, adapt native bridge APIs`
  - `feat(core): add ProbTest engine + robust proxy deserialization`
  - `feat(probtest): candidate node screening with upload-to-subscription`
  - `add update-go-mod-replace`
- **Companion server**: the ProbTest upload endpoint is provided by a separate (private) Cloudflare Worker that accepts `{proxies:[...]}`, merges it into its own node pool, and serves the resulting subscription.

## Contribution

- Kernel changes: open PRs against the `Alpha` branch of [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo).
- When the mihomo kernel is updated, the **Update Dependencies** workflow is triggered automatically and opens a PR pulling the new kernel and bumping the Golang dependencies. If that PR has compile errors, fix them before merging (or merge as-is if the errors are unrelated).
- **Build Pre-Release** publishes a pre-release; **Build Release** tags and publishes a formal release (fill `release-tag` as `v1.2.3`).
