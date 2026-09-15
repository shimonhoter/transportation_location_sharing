# Ride Location Share — Specification

This document describes the **current intended behavior** of the app (not a
change history). It is the source of truth for implementation. The Hebrew
version is [`SPEC_HE.md`](SPEC_HE.md); both are kept in sync on every
meaningful change.

## 1. Problem & Goal

A fixed daily carpool leaves from a fixed origin (~06:00) and arrives at a
fixed destination (~07:30), with no fixed driver — whoever is available
drives that day. The first few passengers board within minutes of departure.
Today, the first passenger is repeatedly asked to manually share their
personal location in a WhatsApp group so others know when to come down —
intrusive and unreliable.

**Goal:** an app that shows the ride's current location in real time to
waiting passengers, **without ever exposing any individual passenger's
personal location**, and **fully automatically** (no manual "share
location" action required in normal operation).

## 2. Core Design Principles

- **No single fixed broadcaster.** Every device currently inside the ride
  broadcasts its own location independently; the server simply holds the
  most recent update from whichever device sent it last. (A single
  designated broadcaster was tried and rejected: it "freezes" the shared
  location the moment that person exits mid-ride.)
- **Broadcasting requires actual movement**, not just presence inside a
  geofence — so a passenger who lives near the origin doesn't start
  broadcasting from home.
- **A safety timer (expected arrival time + margin) is the primary stop
  condition**, not route matching — routes vary day to day. Route-deviation
  detection is only a soft, secondary signal.
- **"Active days" is a precondition** gating all automatic logic, so the
  app never activates on weekends/holidays.
- **Privacy:** the server never stores route history — only the single
  latest location, overwritten on every update. The server has no concept
  of "who lives where" — personal alert-zone checks happen **exclusively
  on-device**, per user.
- **Nickname is a static label only**, never an event message like "now
  broadcasting: X" — switching broadcasters must never indirectly reveal
  where someone got off. Default is no nickname at all.
- **Manual fallback** ("Broadcast now" / "I got off") for when automation
  fails (revoked permissions, aggressive battery optimization, etc.).

## 3. Features

### 3.1 Multi-device automatic broadcasting
Every device running the app, currently inside the active ride, sends its
own location to the server on a fixed interval. The server keeps only the
latest sample, regardless of which device sent it (last-write-wins, no
per-device history).

### 3.2 Ride lifecycle
- **Start condition:** device is within the origin geofence **and** has
  moved more than a minimum displacement threshold — never movement alone,
  and never geofence presence alone.
- **Stop condition (primary):** a safety timer set to the expected trip
  duration plus a safety margin. This is what actually ends broadcasting
  for a device, since exact routes vary daily.
- **Soft secondary signal:** route-deviation detection may flag that the
  ride seems off the usual path, but never autonomously stops broadcasting
  by itself.
- **Manual stop:** "I got off" ends broadcasting for that device
  immediately.
- **Manual opt-out:** "Not riding today" (main screen) suppresses the
  automatic start condition entirely for the rest of the current day, for
  a passenger who knows in advance they're taking their own vehicle instead
  of the shared ride — the one case speed/movement alone cannot distinguish
  from actually being in the shared ride (see 4.2's corroboration note).
  Self-clears the next day; does not block the manual "Broadcast now"
  override.

### 3.3 Active days and time windows
Each user configures which weekdays the automation is allowed to run on,
plus one or more daily active time windows (e.g. a morning-commute window
AND a separate evening-return window) — ANY configured window matching is
enough to activate automation. Windows can be added, edited, and deleted
from the Settings screen; at least one must always remain. Outside the
configured days/windows, WorkManager checks are no-ops.

### 3.4 Per-user settings
- Nickname (free text) + a toggle for whether to show it at all (default:
  hidden/none).
- Personal origin and destination locations.
- Geofence radius.
- Expected trip duration / time-window margins.
- Active weekdays.

Locations can be set two ways in every picker: **"Use current location"**
or **drop a pin on the map** (map pans under a fixed center pin — the user
moves the map, not the pin).

### 3.5 Personal alert zones
A user can mark arbitrary zones on the map. When the ride's shared location
enters such a zone, the device (and only that device) shows a local
notification with sound. This check runs **only on-device**, using the
zone coordinates stored locally — the server never sees zone definitions.
An "already alerted" flag per zone prevents repeat notifications within
the same ride, and resets when the ride ends.

### 3.6 Manual overrides
- **"Broadcast now"** — force-starts broadcasting from this device
  immediately, bypassing the movement/geofence start condition.
- **"I got off"** — force-stops broadcasting from this device immediately.

Both must go through a single, well-defined service call (a known bug in
early builds fired two separate/racing service-start calls, causing
intermittent failures).

### 3.7 Permissions onboarding
On first run, the app walks the user through a full permission sequence in
the required order:
1. Foreground location.
2. Background location.
3. Notifications.

If a permission is denied, the app explains why it's needed and links
directly to the app's system settings screen.

### 3.8 Background scheduling
A WorkManager periodic job runs roughly every 15 minutes, checks whether
"now" falls inside an active day/time window, and if so starts the
broadcast service automatically — no button press required in normal
operation.

## 4. Architecture & Technology

### 4.1 Android app
- **Language:** Kotlin.
- **Map:** WebView + **MapLibre GL JS** loaded from CDN inside
  `app/src/main/assets/map.html`, communicating with native code via a
  JavaScript bridge. (The native MapLibre Android SDK was tried first and
  dropped — it renders a blank/black screen on some Mali GPUs, e.g. common
  Xiaomi/Redmi devices, despite the style loading successfully.)
- **Map tiles:** fully free, keyless sources — no signup or API key to
  manage. Streets: [OpenFreeMap](https://openfreemap.org)'s hosted
  `liberty` vector style, a MapLibre-native drop-in. Satellite: Esri's
  public World Imagery raster tiles, wired in as a minimal inline
  MapLibre style (a single raster source/layer), since OpenFreeMap is
  vector-only. A style-switcher control on the map toggles between them
  (`app/src/main/assets/map.html`); a MapTiler-based setup requiring an
  API key was tried first and dropped in favor of these keyless sources.
- **Background work:** a foreground `Service` for active broadcasting, plus
  a `WorkManager` periodic worker for the 15-minute automatic scheduling
  check.
- **Diagnostics:** an in-app log (`AppLog`) and crash log, since `adb`/
  `logcat` access from the development environment (Termux) is unreliable.

### 4.2 Realtime data backend
- **Backend:** Firebase Realtime Database. A previous iteration used a
  custom stdlib-only Python HTTP server on Render.com; it was retired in
  favor of Firebase to remove the need to run and pay attention to any
  server at all, and to get live push updates for free instead of polling.
- **Data model:** each broadcasting device writes to its own child node,
  `rideLocation/devices/<uid>` (keyed by its Firebase Anonymous Auth UID),
  holding `{lat, lon, speedKmh, nickname, updatedAt}`. There is no single
  shared value and no history — a device only ever holds its own latest
  sample, overwritten on every write, and removes its own node outright
  when it stops broadcasting.
- **Auth:** Firebase Anonymous Authentication. Every device signs in
  anonymously (no login UI, no per-user identity) before its first read or
  write; this replaces the old shared-token model with the same trust
  boundary — anyone running the app can read/write, nobody outside it can.
  The UID this produces is also the per-device key described above.
- **Aggregation (why a single flat value doesn't work):** with several
  passengers potentially broadcasting from different points at once (some
  already in the moving vehicle, others still walking toward the pickup
  spot), last-write-wins on one shared value would show whichever device
  happened to post most recently — meaningless. Instead, the displayed
  ride location is computed client-side, on every read, as the average
  position of devices plausibly inside the moving vehicle. Each device
  tracks and posts its own `lastMovingAt` timestamp — the last time its
  reported speed exceeded `RideConfig.MOVING_SPEED_THRESHOLD_KMH` (7 km/h),
  reset to none at the start of every new broadcast session — and a device
  counts toward the aggregate if it is currently over that speed **or**
  was within `RideConfig.RECENTLY_MOVING_GRACE_SECONDS` (90s). This grace
  window is what keeps the ride on the map through a brief stop (a red
  light, momentary traffic) without instantaneous 0 km/h readings kicking
  everyone out of the aggregate. If no device currently qualifies:
  - and at least one fresh device has never yet recorded a moving sample
    (e.g. broadcasting only just started, before GPS speed caught up), the
    aggregate falls back to averaging every fresh device regardless of
    speed, same as before;
  - but if every fresh device *has* moved before and all are now past the
    grace window, the aggregate is empty (no active ride) rather than
    showing a stray device's stale, jittery position — this is what
    prevents a passenger who got off (and whose device is still
    broadcasting, automatically or by mistake) from corrupting or becoming
    the sole reported ride location once their walking-pace samples age
    out of the grace window.
  Broadcasting itself is never speed-gated; a device keeps posting on
  every fix for as long as it's broadcasting, and only stops via the
  safety timer, geofence auto-stop, or the manual "I got off" (docs
  section 3.2/3.6) — the grace-window exclusion only affects whether that
  device's samples are counted in the aggregate, not whether it keeps
  transmitting.
- **Corroboration (telling the shared ride apart from a passenger's own
  car):** speed and movement alone cannot distinguish a passenger driving
  their own private vehicle from the origin from actually being in the
  shared ride — both look identical to the app ("left the origin, then
  moved fast"). So a device in the recently-moving set above only counts
  toward the aggregate if at least one *other* recently-moving device is
  within `Prefs.corroborationRadiusMeters` of it (Settings screen,
  50-1000m range, default 300m, read fresh on every aggregate computation
  so a change applies immediately) — several devices moving together
  plausibly are the same vehicle, while a lone mover isn't shown as the
  ride location at all. This is a secondary safety net with a real
  trade-off: a single genuine early rider (before a second device is also
  moving nearby) also won't show until corroborated. The reliable,
  primary fix for someone who knows in advance they're taking their own
  car is the manual "not riding today" override (section 3.2), which
  prevents their device from auto-starting a broadcast in the first place
  regardless of corroboration.
- **Staleness:** there is no server-side TTL. Each device's sample older
  than `RideConfig.STALE_AFTER_SECONDS` (90s) is excluded from the
  aggregate by the client computing it — the same effective behavior the
  old server's TTL produced, just computed from each `updatedAt` timestamp.
- **Delivery:** the Android app uses a live `ValueEventListener` over the
  whole `devices` node (Realtime Database push) instead of polling — both
  `MainActivity` (for the map) and `BroadcastService` (for alert-zone
  checks) attach their own independent listener and recompute the
  aggregate on every change, since a waiting passenger's map must stay
  live even when their own device isn't broadcasting.
- **Update cadence:** how often a broadcasting device posts its location is
  user-configurable (`Prefs.locationUpdateIntervalSeconds`, Settings
  screen, 3-30s range, default 5s) rather than fixed. Saving Settings while
  `BroadcastService` is already running applies this (and the trip
  duration/safety margin, which redefine the safety-timer deadline) to the
  live broadcast immediately, not just on the next session start.

### 4.3 Firebase config is committed, database URL is fixed in code
`app/google-services.json` is committed to the repo — it is not a secret,
only a public per-project identifier; access is governed entirely by the
Realtime Database security rules (`auth != null` on the `rideLocation`
node) and Firebase Anonymous Auth, not by hiding this file. The database
URL itself is set explicitly in `RideConfig.FIREBASE_DATABASE_URL` rather
than relying on `google-services.json` to carry it, since a config file
downloaded before the database is created won't include it. Same principle
as the old fixed server URL/token: every passenger's device must point at
the same backend regardless of local configuration, so this is not exposed
in the settings screen.

### 4.4 CI/CD
- GitHub Actions builds `assembleDebug` on every push and uploads the APK
  as a workflow artifact.
- The debug APK is signed with a **fixed, repo-committed
  `app/debug.keystore`** (explicit `signingConfig` in Gradle) — a random
  per-build debug key silently breaks update installs (Android rejects an
  update signed with a different key, without a clear error).

## 5. Privacy Model

| Data | Where it lives |
|---|---|
| Current ride location (aggregated, no history) | Firebase Realtime Database (`rideLocation/devices/<uid>` per device, client-side aggregation + staleness cutoff) |
| Route history | Nowhere — never persisted |
| Personal origin/destination/alert zones | On-device only (local prefs) |
| Which device is currently broadcasting | Not exposed to other users at all |
| Nickname | Static per-device label, not an event/activity indicator |

## 6. UI/UX Guidelines

- Full RTL support (`android:supportsRtl="true"`); Hebrew is the primary
  language.
- Visual language: Material Components adapted from a glassmorphism/soft-UI
  reference — translucent `MaterialCardView` with elevation standing in for
  glass blur, elevation standing in for soft shadows, a shared spacing
  scale in `dimens.xml`, native Material ripple instead of hover states,
  and `Theme.MaterialComponents.DayNight` with `values`/`values-night`
  color sets for light/dark mode. Centralized in `UiKit.kt` and reused
  across all screens.
- A single, clear status card communicates state ("no data yet" / "ride
  ended" / "broadcasting") — these must never be duplicated as separate
  overlapping messages.
- Full-screen permission onboarding flow shown on install (see 3.7).
- The map auto-centers on the ride's location on every update, on by
  default at every app launch; a toggle control on the map (view mode
  only) lets the user turn this off to freely pan/zoom without being
  pulled back, and back on again.

## 7. Explicit Non-Goals

- No server-side storage of route history.
- No per-user credentials — anonymous auth for every device is intentional.
- No in-app editing of the backend (Firebase database URL).
- No reliance on exact route matching to decide when to stop broadcasting.
- No broadcaster-switch notifications that could leak who got off where.
