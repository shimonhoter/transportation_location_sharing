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
- **Broadcasting requires actual movement**, gated only by the active
  day/time window — no origin geofence requirement; broadcasting can
  start anywhere once the device is moving fast enough.
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
- **No manual broadcast control.** Starting and stopping broadcasting is
  entirely automatic (active window/speed condition, safety timer, "stay
  home" opt-out); the main screen's round button is a status indicator only
  (see 3.6), not a way to force a start or stop.

## 3. Features

### 3.1 Multi-device automatic broadcasting
Every device running the app, currently inside the active ride, sends its
own location to the server on a fixed interval. The server keeps only the
latest sample, regardless of which device sent it (last-write-wins, no
per-device history).

### 3.2 Ride lifecycle
- **Start condition:** the active day/time window (3.3) is currently in
  effect **and** the device's speed exceeds
  `RideConfig.MOVING_SPEED_THRESHOLD_KMH` — no origin/geofence requirement;
  broadcasting can start from anywhere once moving fast enough.
- **Stop condition (primary):** a safety timer set to the expected trip
  duration plus a safety margin. This is what actually ends broadcasting
  for a device, since exact routes vary daily.
- **Soft secondary signal:** route-deviation detection may flag that the
  ride seems off the usual path, but never autonomously stops broadcasting
  by itself.
- **Manual opt-out:** "Stay home" (main screen) blocks the automatic
  start condition entirely, for a passenger who isn't on the shared ride
  today (or just found out they aren't) instead of taking it as usual —
  the one case speed/movement alone cannot distinguish from actually being
  in the shared ride (see 4.2's corroboration note). See 3.6 for the full
  behavior.
- **Immediate check on app open:** rather than only re-evaluating on the
  next ~15-minute `RideCheckWorker` tick (3.8), `MainActivity` also starts
  automatic monitoring the moment the app is opened, if the active
  day/window condition (3.3) currently allows it.

### 3.3 Active days and time window
Each user configures which weekdays the automation is allowed to run on,
plus a single daily active time window (start and end time) — outside the
configured days/window, WorkManager checks are no-ops.

### 3.4 Per-user settings
- Ride code (free text, e.g. the bus line number) — scopes which shared
  Firebase node this device belongs to; see 4.2. Every rider on the same
  ride enters the same code themselves, no admin or shared invite needed.
- Nickname (free text) + a toggle for whether to show it at all (default:
  hidden/none).
- Personal origin and destination locations.
- Geofence radius.
- Expected trip duration / time-window margins.
- Active weekdays and the single daily active time window.
- Local ETA history toggle (`Prefs.historyEnabled`), off by default; see
  4.5.

Locations can be set two ways in every picker: **"Use current location"**
or **drop a pin on the map** (map pans under a fixed center pin — the user
moves the map, not the pin).

### 3.5 Personal alert zones
A user can mark arbitrary circular zones directly on the main map — there is
no separate screen for this. A 📍 control in the map's control bar (top
corner, alongside the style switcher, auto-center and rotation toggles)
enters "edit zones" mode: auto-centering is forced off for the duration, and
tapping an empty spot on the map places a new zone there (default radius
`RideConfig.DEFAULT_ALERT_ZONE_RADIUS_METERS`, 150m). While in this mode,
every zone shows three draggable handles — a center handle (✛) to move it,
an edge handle (↔) to resize it, and a delete handle (🗑️) — with every
change (add/move/resize/delete) applied to `Prefs.alertZones` immediately
via the `MapBridge` JS-to-Kotlin callbacks (`onZoneAdded`/`onZoneMoved`/
`onZoneResized`/`onZoneDeleted`). Double-tapping the map, or toggling the
📍 control off, exits edit mode and resumes auto-centering.

When the ride's shared location enters a zone, the device (and only that
device) is alerted: a full-screen popup (`AlertZoneAlarmActivity`) is
brought to the foreground via the notification's full-screen intent — even
over the lock screen — and an alarm sound loops until the user dismisses
it, capped at `Prefs.alertSoundDurationSeconds` (Settings screen, default
30s) as a safety net in case it's never dismissed. If `Prefs.historyEnabled`
(4.5) is on and an origin is configured, the popup and notification also
show the estimated arrival time at the user's stop, from the same
local-history estimate used on the main screen. This check runs **only
on-device**, using the zone coordinates stored locally — the server never
sees zone definitions. An "already alerted" flag per zone prevents repeat
notifications within the same ride, and resets when the ride ends.

### 3.6 Broadcast indicator and manual opt-out
Two round elements float directly on the map, bottom-center (no card
background — see 6):

- **Broadcast indicator (center, larger)** — a status indicator only, not a
  control (not clickable, no tap handler). Always shows an antenna icon,
  green while `RideSessionState.isThisDeviceBroadcasting` is true. Whenever
  it's false — for any reason: stay-home mode, the movement/geofence
  start condition not yet met, or an automatic stop (safety timer, outside
  the active window) — a red "blocked" badge is overlaid on the antenna, so
  a single glance always answers "is my location going out right now",
  regardless of why it isn't. A short toast ("Broadcasting started"/"Not
  broadcasting location") fires on every actual transition.
- **"Stay home"** — a 🏠 icon button, orange when active, off by default.
  Tapping it while off stops `BroadcastService` outright — not just any
  broadcast already in progress, but idle background GPS monitoring too —
  so "stay home" genuinely turns the app off in the background instead of
  just blocking the outcome of a check that keeps running. The periodic
  `RideCheckWorker` (3.8) also skips restarting monitoring while the mode
  is active. Tapping again to deactivate immediately restarts automatic
  monitoring if still inside today's active window, rather than waiting for
  the next periodic check. Auto-reverts to off at the end of today's active
  time window (3.3); falls back to
  `RideConfig.DEFAULT_PRIVATE_CAR_DURATION_MINUTES` (120 minutes) if today
  isn't an active day or the window has already ended when activated.
  Covers both a passenger who knows in advance they're taking their own
  vehicle instead of the shared ride, and one who only realizes it in the
  moment.

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
operation. In addition, `BootReceiver` listens for the system's
`BOOT_COMPLETED` broadcast and runs the same check immediately when the
device finishes starting up, so the app resumes automatic background
monitoring right after a phone reboot instead of waiting for the next
periodic check or for the user to open the app manually.

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
  `rideLocation/rides/<rideCode>/devices/<uid>` (keyed by its Firebase
  Anonymous Auth UID, under a group-chosen ride code — see "Ride code"
  below), holding `{lat, lon, speedKmh, nickname, updatedAt, lastMovingAt}`.
  There is no single shared value and no history — a device only ever
  holds its own latest sample, overwritten on every write, and removes its
  own node outright when it stops broadcasting.
- **Ride code (isolating unrelated groups):** every device using the app
  shares the same Firebase project, so nothing stops a second, unrelated
  group — a different bus line, say — from also running it. `Prefs.rideCode`
  (Settings screen, free text, e.g. the bus line number) scopes which
  `rides/<rideCode>` node a device reads and writes; everyone in the same
  ride simply enters the same, already publicly-known code (a line number
  needs no coordination or secrecy, unlike a generated invite code), so two
  different lines naturally land in two different, non-overlapping nodes.
  Blank falls back to a shared `"default"` node (pre-existing installs, or
  a group that hasn't set one). `FirebaseLocationRepository` sanitizes the
  code for the characters Realtime Database keys forbid (`. $ # [ ] /`,
  replaced with `_`). Because a live Firebase listener can't be re-pointed
  at a different reference in place, `BroadcastService` and `MainActivity`
  detach and re-attach their shared-location listener whenever the code
  they last attached with differs from the current `Prefs.rideCode` (the
  same "apply immediately" pattern as every other Settings value).
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
  Once broadcasting has started it is never speed-gated again; a device
  keeps posting on every fix for as long as it's broadcasting, and only
  stops via the safety timer or leaving the active time window (docs
  section 3.2) — the grace-window exclusion only affects whether that
  device's samples are counted in the aggregate, not whether it keeps
  transmitting.
- **Direction of travel:** the map's direction arrow and rotation follow the
  reported (aggregated) location's own progression between updates —
  `map.html`'s `bearingDegrees()` computed from consecutive fixes of
  `RideLocation`, the same value every screen renders. The aggregate's
  lat/lon is an average across whichever devices currently qualify, and
  that set can change between updates; with two or more broadcasters, the
  averaged point can shift sideways by a few meters purely because of that,
  unrelated to the ride's actual heading. Recomputing the heading on every
  such shift made the arrow erratic once more than one device was moving
  (it pointed correctly with a single broadcaster but became jittery as
  soon as a second one joined), so `map.html` only updates the heading once
  the reported location has moved at least `HEADING_MIN_MOVEMENT_METERS`
  (8m) from the last point used to compute one — small sideways jitter is
  ignored while genuine forward progress still steers the arrow.
- **Corroboration (telling the shared ride apart from a passenger's own
  car):** speed and movement alone cannot distinguish a passenger driving
  their own private vehicle from the origin from actually being in the
  shared ride — both look identical to the app ("left the origin, then
  moved fast"). So whenever **two or more** devices are in the
  recently-moving set above, each one only counts toward the aggregate if
  at least one *other* recently-moving device is within
  `Prefs.corroborationRadiusMeters` of it (Settings screen, 50-1000m
  range, default 300m, read fresh on every aggregate computation so a
  change applies immediately) — several devices moving together plausibly
  are the same vehicle, while one of several movers off on its own isn't
  shown. A single, lone mover is always trusted directly, with no
  corroboration requirement — it has nothing to corroborate against by
  definition, and the single most common case (exactly one person
  currently broadcasting) must still show something (a bug where this
  requirement applied even when solo made a lone broadcaster's own
  location disappear a few seconds after it started moving). This makes
  corroboration a secondary safety net that only ever engages when a
  second broadcaster is also active; it cannot, by itself, catch a
  passenger who takes their own car while otherwise being the only device
  broadcasting for that ride code. The reliable, primary fix for someone
  who knows they're taking their own car is the manual "stay home"
  override (section 3.2), which blocks their device from broadcasting at
  all, automatic or manual.
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

### 4.5 Local ETA estimation
- **Opt-in, on-device only** (`Prefs.historyEnabled`, off by default,
  Settings screen): while enabled, every ride-location update this device
  observes (via `RideSessionState.currentLocation`, regardless of whether
  `MainActivity` or `BroadcastService` is what received it — a single
  `observeForever` in `RideApplication` covers both) is appended to
  `RideHistoryStore`, a local SQLite table of `{timestamp, lat, lon,
  speedKmh}`, pruned to the last `RideConfig.HISTORY_RETENTION_DAYS` (30)
  on every write. This is unrelated to the "no server-side route history"
  principle in section 5 — it never leaves the device.
- **Estimate (`RideEtaEstimator`)**, recomputed on every location update
  and shown on the map (section 6):
  - **Live**: once the ride is visible and moving faster than
    `RideConfig.MIN_SPEED_FOR_LIVE_ETA_KMH` (5 km/h), straight-line
    distance from its current position to the configured origin (the
    user's stop) divided by its current average speed.
  - **Historical fallback**: before the ride is moving (or whenever a live
    estimate isn't available), the median clock time, across recorded
    days, of the first sample each day found within the configured
    geofence radius of the origin — i.e. "what time does the ride
    typically reach my stop."
- Deliberately simple: a straight-line-distance/current-speed model, not a
  route-aware prediction — appropriate given how small and short-lived the
  local dataset is (opt-in, 30 days, one device's own observations).

## 5. Privacy Model

| Data | Where it lives |
|---|---|
| Current ride location (aggregated, no history) | Firebase Realtime Database (`rideLocation/devices/<uid>` per device, client-side aggregation + staleness cutoff) |
| Route history | Nowhere — never persisted |
| Personal origin/destination/alert zones | On-device only (local prefs) |
| Which device is currently broadcasting | Not exposed to other users at all |
| Nickname | Static per-device label, not an event/activity indicator |
| Local ETA history (`Prefs.historyEnabled`) | On-device only (local SQLite, `RideHistoryStore`), opt-in, 30-day rolling window, never sent anywhere |

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
- No status card or text badge on the main screen — state is communicated
  entirely through the round buttons themselves (icon + color), never
  duplicated as separate overlapping messages.
- This device's own broadcasting state has exactly one indicator: the
  round broadcast indicator, a non-interactive status display (not a
  button) always showing an antenna icon, colored `status_active` green
  while broadcasting or `status_idle` gray with a red "blocked" badge
  overlaid whenever it isn't, for any reason — no separate text badge
  duplicates it. The "stay home" button follows the same round-icon
  language: `brand_accent` (highlighted) when active, `status_idle`
  (faded) when off.
- Full-screen permission onboarding flow shown on install (see 3.7).
- The map auto-centers on the ride's location on every update, on by
  default at every app launch; a toggle control on the map (view mode
  only) lets the user turn this off to freely pan/zoom without being
  pulled back, and back on again. Auto-centering only ever changes the map
  center, never the zoom — whatever zoom level the user is currently at is
  passed straight back into the same `easeTo()` call, so it's never reset.
- The ride's location is drawn as a triangular direction arrow (in the
  style of Waze/Google Maps) rather than a plain dot; with only one fix so
  far it points north by default.
- Map rotation (Waze-style "heading up"), on by default: a toggle control
  on the map (view mode only) switches between the map itself rotating to
  face the ride's direction of travel while it's being auto-centered (the
  arrow then stays pointing straight up on screen, since the map underneath
  it is what turns) and a fixed north-up map with the arrow itself rotating
  to the bearing between the ride's last two fixes. Only meaningful while
  auto-centering is also on.
- When enabled (Prefs.historyEnabled, see 4.5), an estimated arrival time
  at the user's stop is shown in a small card on the map's top-left
  corner, directly below the native Settings/Help buttons (which sit at
  that same physical corner in this RTL app despite using
  `layout_constraintEnd_toEndOf`).

## 7. Explicit Non-Goals

- No server-side storage of route history.
- No per-user credentials — anonymous auth for every device is intentional.
- No in-app editing of the backend (Firebase database URL).
- No reliance on exact route matching to decide when to stop broadcasting.
- No broadcaster-switch notifications that could leak who got off where.
- No route-aware ETA — the local arrival estimate (4.5) is a straight-line
  distance/current-speed and historical-median model, not a routing engine.
