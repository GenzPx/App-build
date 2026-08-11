# Monitored Check

An all-in-one Android system monitor, hardware inspector and diagnostics toolkit — built native in Kotlin with Jetpack Compose and Material 3.

Monitored Check combines the roles of a task manager, DevCheck, CPU-Z, AIDA64, HWiNFO, a battery monitor, a storage analyzer, a network analyzer and a set of developer tools into one privacy-first application that runs entirely on-device and works without root.

---

## The one rule that shapes this app

**No value in Monitored Check is ever invented.**

There is no random CPU load, no simulated GPU usage, no estimated battery health, no fabricated FPS, no fake SMART data. Every number is read from a real Android API, a real kernel interface, or real hardware.

When the platform will not provide something, the app says so explicitly and explains why. This is enforced structurally: every value flows through a `Reading<T>` type that carries an availability status, and a non-available reading physically cannot expose a value.

| State | Meaning |
|---|---|
| **Available** | Read successfully from a real source |
| **Limited** | Real value, but scope-restricted by the platform |
| **Unavailable** | The source exists but returned nothing on this device |
| **Unsupported** | No API or hardware path exists for this value |
| **Permission Required** | A runtime or special permission must be granted |
| **Restricted by Android** | Blocked by platform security policy |
| **Requires Root** | Only readable on a rooted device (this app never uses root) |
| **Hardware Not Supported** | The device has no such hardware |
| **Temporary Error** | The read failed this time and will be retried |

Each of these states appears in the UI as a coloured chip alongside a plain-language explanation of the limitation.

---

## Performance

The monitoring engine was rebuilt around three rules that keep the UI at a steady frame rate even on low-end hardware:

1. **No blocking I/O in composition.** Every repository touches procfs, sysfs, PackageManager or SQLite. All of it now loads through `rememberAsync` / `rememberPolled`, which run on `Dispatchers.IO` and show a loading state instead of freezing the main thread.
2. **Per-series graph flows.** Each chart observes its own `StateFlow`, so it recomposes only when its own data changes. Previously a single global counter invalidated every card on screen at once — the main source of dashboard jank.
3. **Tiered polling.** Expensive sysfs sources (thermal zones, GPU nodes) refresh every 3rd tick (5th in Low Resource Mode) and reuse the last *real* reading in between — never an interpolated one. The task list runs on its own 5-second timer instead of re-enumerating `/proc` on every sample.

`MonitorSample` is `@Immutable` so Compose can skip unchanged subtrees.

## Features

### Dashboard
Material You dashboard with live cards for CPU usage, CPU frequency, GPU, memory, storage, battery, battery temperature, device temperature, network throughput, display/FPS and processes. Every card is tappable, and every card can be toggled on/off and reordered from Settings.

### CPU
SoC identity, ARM implementer/part decoding, architecture, ABIs, instruction features, cluster topology (big/LITTLE detected via max-frequency grouping), per-core current/min/max frequency, per-core utilisation, governors, available frequencies, scaling driver, load average and CPU temperature. Utilisation is computed from `/proc/stat` jiffy deltas — the same method `top` uses.

### GPU
Vendor, renderer, OpenGL ES version, GLSL version and the full extension list, queried through a real off-screen EGL + OpenGL ES context. Vulkan support level, API version and compute level from the platform feature flags. GPU utilisation and clock are read from vendor kernel nodes (Adreno KGSL, Mali, devfreq) when the device exposes them, and reported as **Unsupported** when it does not — Android has no official GPU load API.

### Memory
Total/used/available/free RAM, cached, buffers, swap, zRAM, low-memory state and threshold, memory class, plus kernel memory detail from `/proc/meminfo` and this process's own PSS.

### Storage
Volume capacities via StorageManager/StatFs, mount points and filesystems from `/proc/mounts`, block-device I/O from `/proc/diskstats`, and genuine eMMC/UFS wear indicators (`life_time`, `pre_eol_info`) where the kernel exports them. SMART is correctly reported as unsupported — it does not exist on Android storage.

Plus a full **Storage Analyzer**: recursive folder analysis, largest files, largest folders, file type distribution, extension statistics, cache and temp detection, a duplicate finder (size grouping then SHA-256 of head+tail+size), and a directory browser. Scans are cancellable and depth-capped. **Nothing is ever deleted without an explicit confirmation dialog.**

### Battery
Level, status, charging source, voltage, current (now and average), computed power, temperature, technology, charge counter, design capacity, full-charge capacity, capacity-vs-design and cycle count — each shown only when the device actually reports it. Local history in SQLite with graphs over 1 hour, 6 hours, 24 hours, 7 days and 30 days.

### Thermal
Every readable thermal zone from `/sys/class/thermal`, grouped into CPU / GPU / battery / skin / SoC / modem / camera / display / charger, with vendor encodings (milli-, centi-, deci-Celsius) normalised. Official throttling status via `PowerManager.getCurrentThermalStatus()`.

### Sensors
Complete sensor inventory with name, vendor, type, version, resolution, maximum range, power draw, minimum delay, maximum sampling rate, reporting mode, wake-up and dynamic flags — plus live values with a realtime graph. Listeners are registered only for the expanded sensor and torn down immediately on collapse.

### Task Manager
Running processes with PID, UID, importance, thread count, state and PSS memory; running services; and recent app activity from UsageStatsManager. Honest about the Android 8+ restriction that limits the process list to the calling app.

### Applications
Every installed package with version, version code, target/min SDK, install and update dates, UID, APK size, app/data/cache sizes (with Usage Access), last-used time and requested permissions. Filters for user/system/recent/large and six sort modes.

### Permission Inspector
Which apps hold which permissions, grouped by camera, microphone, location, storage, contacts, phone, sensors, notifications, network, calendar and special access, with protection levels. Permission *usage history* is correctly reported as system-only, with a shortcut to the platform Privacy Dashboard.

### Network Analyzer
Connection type and capabilities, IPv4/IPv6 addresses, subnet, gateway, routes, DNS, private DNS, MTU, Wi-Fi details (SSID/BSSID when location is granted, RSSI, link speed, frequency, band, Wi-Fi standard), mobile network and carrier info, interface enumeration with kernel byte counters, and traffic totals.

Tools — all strictly user-initiated: ping, TCP latency test with jitter, DNS lookup, reverse DNS, HTTP connectivity test with headers, TCP port check, traceroute, download test and upload test. Realtime download/upload monitoring with graphs.

### Display & FPS
Resolution, density, DPI, physical screen size, refresh rate, all supported modes, HDR types, wide colour gamut, brightness and display state. FPS is measured with Choreographer vsync callbacks on this app's own render loop — and the UI states exactly that, because Android does not expose other apps' frame rates to a normal app.

### Benchmark
On-device micro-benchmarks that measure real work and divide it by real elapsed time: dependent integer chains (MOPS), sqrt-heavy floating point (MFLOPS), parallel scaling across every core, large memory copies (MB/s), SHA-256 throughput, and sequential storage write/read with `fsync`. There is no hidden reference table and no synthetic "points" — each result states exactly what was measured, and the UI notes that figures are comparable between runs on the same device rather than against other phones' marketing numbers.

### About & support
Creator profile, links to the source, donation links, full build metadata, tech stack, the privacy policy, the data-integrity pledge, the MIT licence and the disclaimer.

### Kernel, SELinux, Binder, Drivers
Kernel version and build, compiler, command line, uptime, deep sleep, boot time and reason, verified boot state and bootloader lock status. SELinux enforcement state, policy version and this process's security context (read-only — the app never attempts to change it). Binder diagnostics with an honest explanation of the debugfs restriction. Driver/subsystem information for graphics, display, audio, camera, wireless, USB, storage, input and kernel modules.

### Pattern Scanner
A transparent, local, heuristic inspector for APKs, files, folders and installed apps. Computes real SHA-256 and MD5 hashes, parses real package metadata, and flags dangerous permission combinations, debuggable builds, outdated target SDKs, sideloading, native libraries, excessive DEX files, executable code hidden in assets, and behavioural strings such as `DexClassLoader` and root-shell references.

Risk levels are Safe / Low Risk / Suspicious / High Risk / Unknown, and **every point of the score is itemised with its reason**. It is explicitly **not an antivirus** — it has no signature database, no cloud reputation and no sandbox, and the UI says so prominently. All analysis is local; nothing is uploaded.

### Logs & Crash Reports
Logcat viewer with priority filters, search and export — with the Android 4.1+ reality clearly stated: a normal app can only read its own UID's log entries. Local crash reporting captures full stack traces and device context for Monitored Check's own crashes, stored privately on-device with no upload path in the code at all.

### TXT Report Export
Generates a complete structured plain-text report covering device, Android build, kernel, SELinux, integrity, CPU, GPU, memory, storage, battery, thermal, display, sensors, network, interfaces, drivers, Binder, applications, permissions and a live monitoring snapshot — with a legend of availability states and unavailable data marked honestly.

---

## Architecture

Modular MVVM with a repository layer, coroutines and `StateFlow`.

```
com.monitorcheck/
├── core/          Reading<T> + DataStatus, SysFs reader, Fmt, Permissions, Settings
├── hardware/
│   ├── cpu/       /proc/stat deltas, cpufreq, topology
│   ├── gpu/       EGL + GLES context, vendor sysfs
│   ├── memory/    ActivityManager + /proc/meminfo
│   ├── thermal/   /sys/class/thermal + PowerManager
│   ├── sensor/    SensorManager with lifecycle-scoped listeners
│   └── display/   DisplayManager, Choreographer FPS
├── data/battery/  BatteryManager + local SQLite history
├── storage/       Volumes, analyzer, duplicates
├── network/       ConnectivityManager, WifiManager, TrafficStats, tools
├── apps/          PackageManager, UsageStats, process enumeration
├── security/      Pattern Scanner, Permission Inspector
├── logs/          Logcat reader, crash reporter
├── system/        Build/kernel/SELinux/Binder/drivers
├── monitor/       Central engine, ring buffers, foreground service
├── reports/       TXT report writer
└── ui/            Compose screens, Material 3 theme, shared components
```

**One central monitoring engine.** Every screen observes a single sampling loop rather than starting its own timer, so cost stays bounded no matter how many cards are visible.

---

## Resource efficiency

Designed to run comfortably on low-end hardware:

- One shared sampling coroutine for the whole app
- Polling automatically throttles to ≥10 s when the app is backgrounded
- **Low Resource Mode** skips the most expensive reads (thermal zones, GPU sysfs) and enforces a 2 s floor
- Fixed-capacity ring buffers — graph memory can never grow unbounded
- Graphs drawn as a single `Path` on Canvas; no chart library, no per-point composables
- Negative caching on unreadable sysfs paths so failed reads are not retried every tick
- Sensor listeners exist only while their detail view is open
- Storage scans are depth- and entry-capped, and fully cancellable

---

## Privacy

Monitored Check is privacy-first by construction:

- **No telemetry, no analytics, no tracking, no ads, no accounts**
- **No automatic uploads and no cloud storage** — the crash reporter contains no network code whatsoever
- All monitoring history, reports and crash logs stay in app-private storage
- `allowBackup="false"`; cloud backup and device transfer are excluded
- The network is touched **only** when you press a button in Network Tools
- The public IP lookup is the single feature that contacts a third party, and it tells you before it runs

---

## Permissions

Nothing is requested at first launch. Each permission is requested only when you open the feature that needs it, with an explanation first, and denial always degrades gracefully.

| Permission | Used for |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Network analyzer and user-run tools |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Wi-Fi SSID and BSSID — Android requires location for these fields |
| `POST_NOTIFICATIONS` | The ongoing notification for optional background monitoring |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Optional background monitoring service |
| `PACKAGE_USAGE_STATS` | Per-app storage sizes, last-used times, app activity (special access) |
| `QUERY_ALL_PACKAGES` | Installed application inventory on Android 11+ |
| `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | Storage analyzer (optional; limited scope without it) |
| `READ_PHONE_STATE` | Mobile data network type on Android 11+ |
| `RECEIVE_BOOT_COMPLETED` | Restart background monitoring after reboot **only if you enabled it** |

---

## Android restrictions this app respects

Monitored Check never uses exploits, hidden APIs for restricted data, or security bypasses. These limits are real, and the app explains each one where it applies:

- **Process list** — Android 8+ restricts `getRunningAppProcesses()` to the caller's own processes; `/proc` is hidden for other UIDs by `hidepid`
- **Force stop** — requires the system-only `FORCE_STOP_PACKAGES`; the app offers the App Info screen instead
- **Logcat** — `READ_LOGS` is signature/privileged; apps see only their own UID's entries
- **MAC addresses** — randomised/withheld since Android 6
- **Device identifiers** (IMEI, serial) — blocked for third-party apps since Android 10
- **GPU load and clock** — no public API exists; only vendor sysfs, where readable
- **Battery cycle count and design capacity** — no public API before Android 14; kernel-dependent
- **Binder statistics** — live in debugfs, unmounted or SELinux-protected for apps
- **Kernel modules / full driver list** — `/proc/modules` typically unreadable without root
- **Permission usage history** — Privacy Dashboard APIs are system-only
- **System-wide FPS** — not exposed; only the app's own render loop can be measured
- **SMART storage health** — does not exist on Android eMMC/UFS

---

## Requirements

- **Minimum:** Android 7.0 (API 24)
- **Target / compile:** Android 15 (API 35)
- **Root:** not required, not requested, not used
- **Build:** JDK 17, Android SDK 35, Gradle 8.9 (wrapper included)

---

## Build instructions

### Android Studio
Open the project folder and press Run. Android Studio provisions the SDK automatically.

### Command line

```bash
git clone <your-repository-url>
cd MonitoredCheck

# Point at your SDK (or set ANDROID_HOME / ANDROID_SDK_ROOT)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # run unit tests
./gradlew lintDebug            # static analysis
./gradlew assembleRelease      # unsigned release APK
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Building on a low-memory machine
`gradle.properties` is already tuned conservatively (1 GB heap, serial GC, in-process Kotlin compilation, daemon disabled) so the project builds on small CI runners and 2 GB containers. On a workstation you can raise `org.gradle.jvmargs` and re-enable the daemon for faster builds.

---

## Tests

```bash
./gradlew testDebugUnitTest
```

Covers the availability/`Reading` contract, all formatters, CPU utilisation delta arithmetic (including rollover clamping and no-movement cases), thermal encoding normalisation, ring buffer wrap-around and bounded memory, and Pattern Scanner risk thresholds and monotonicity.

The HTML report lands in `app/build/reports/tests/testDebugUnitTest/index.html`.

---

## GitHub Actions

`.github/workflows/build.yml` runs on every push, pull request and manual dispatch:

1. Checks out the repository
2. Sets up JDK 17 (Temurin) and the Android SDK
3. Configures Gradle with dependency caching
4. Runs unit tests and uploads the test report
5. Runs Android Lint and uploads the lint report
6. Builds the debug APK
7. Builds an unsigned release APK
8. Writes an APK size summary to the job summary
9. Uploads **`app-debug`** (and `app-release-unsigned`) as downloadable artifacts

Download the APK from the **Artifacts** section of the workflow run.

### Signing

The release build is signed when — and only when — the environment supplies credentials. Gradle reads them from environment variables, never from a file in the repository:

| Variable | GitHub Secret | Meaning |
|---|---|---|
| `MC_KEYSTORE_PATH` | derived from `KEYSTORE_BASE64` | path to the decoded keystore |
| `MC_KEYSTORE_PASSWORD` | `KEYSTORE_PASSWORD` | keystore password |
| `MC_KEY_ALIAS` | `KEY_ALIAS` | key alias |
| `MC_KEY_PASSWORD` | `KEY_PASSWORD` | key password |

CI decodes the keystore from `KEYSTORE_BASE64` into the runner's temp directory, builds, then deletes it in an `if: always()` step. If the secrets are absent the release APK is simply produced unsigned rather than failing the build.

**The keystore and its passwords are never committed.** `.gitignore` blocks `*.jks`, `*.keystore` and `keystore.properties`. Losing the keystore means you can no longer ship updates to an already-installed app, so back it up somewhere safe and private.

Build a signed release locally:

```bash
export MC_KEYSTORE_PATH=/absolute/path/to/release.jks
export MC_KEYSTORE_PASSWORD='...'
export MC_KEY_ALIAS=monitoredcheck
export MC_KEY_PASSWORD='...'
./gradlew assembleRelease
```

---

## License

MIT — see [LICENSE](LICENSE).

Created by **Genz** ([@GenzPx](https://github.com/GenzPx)). If the app is useful to you, support is welcome via [Saweria](https://saweria.co/Genzsenpai) or [Trakteer](https://trakteer.id/Genzsenpai) — entirely optional, and no feature is ever paywalled.

Monitored Check is a diagnostic and informational tool. Pattern Scanner is a heuristic inspector, not an antivirus, and it cannot certify any file as safe or malicious. Storage deletion is irreversible and always requires your explicit confirmation.
