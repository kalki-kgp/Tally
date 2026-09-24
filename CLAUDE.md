# Tally

A personal expense tracker for one person and one phone. It watches for payment
apps, and the moment you leave one it asks what you just spent. Not a product,
not going on any store — decisions should optimise for the owner's actual habits,
not for a general audience.

## Standing rules

- **Ship release APKs**, debug-signed for sideloading. Never hand over a debug build.
- **R8 / minification stays off** until the app is feature-complete. This is a
  deliberate instruction from the owner; do not enable it to solve a size problem.
- **Every APK built gets a new version.** Bump `versionCode` (and `versionName`)
  in `version.properties` before any `assembleRelease` that is handed over or
  published — never rebuild under a version the phone may already have.
  `scripts/release.sh` does this itself; an ad-hoc build must do it by hand.
- **Zip the APK before delivering it.** A 27 MB upload times out; ~10 MB does not.
- **Releases go out with `scripts/release.sh <versionName> "<notes>"`**, built on
  this Mac. It bumps `version.properties`, builds, checks the APK is signed with
  `~/.android/debug.keystore` (the key the phone's copy has), and publishes the
  APK plus `tally-android-update.json` to github.com/kalki-kgp/Tally releases.
  APK and zip are named `tally-<versionName>-<versionCode>.apk/.zip` so no two
  builds share a filename; only the manifest's name is fixed.
  The app's updater (`update/AppUpdater.kt`) reads that manifest, verifies the
  SHA-256 and hands the APK to Android's installer. Never build releases in CI
  or with a new key: the phone would refuse the update, and uninstalling to get
  past that deletes every payment.
- **All money is a whole number of paise**, stored as `Long`. Never a float.
  Rupee-facing conversion goes through `core/Money.kt`, including Indian digit
  grouping, which `DecimalFormat` cannot express.
- **Never reintroduce an AccessibilityService.** Navi and CRED scan for enabled
  accessibility services and refuse to process a payment while one is on.
  Detection uses the Usage Access API for exactly this reason.
- **Foreground detection is read as state, not as events.** `UsageWatcherService`
  reports what is on screen on every poll, changed or not, and
  `SessionTracker.onForeground` is idempotent. An event-diffing design was tried
  and failed: one dropped event left the tracker permanently wrong. See
  `plan.md` §12.
- **The AI layer uses Haiku** (`claude-haiku-4-5-20251001`) for both fast and
  smart paths.
- **The prompt after leaving a payment app stays, and is the default.** When a
  payment notification was read, the prompt shows the expected amount and asks
  only for the category (`PromptNotifier.showLogged`, in the visit's own
  notification id, so an earlier "Did you pay?" is rewritten in place rather than
  duplicated). A payment read mid-visit waits for the visit to end before it
  prompts. The amount is saved the moment it is read (`reviewed = false`), so an
  unanswered prompt still leaves it in "To sort". "Sort later" (no prompts) is an
  opt-in switch. The owner asked for exactly this after the prompts were briefly
  made opt-in: do not remove or silence the prompt by default.
- **Notification reading is back, at the owner's request** (it was removed in v2,
  also at their request). `PaymentNotificationListener` reads **only the payment
  apps' own notifications** (watched apps + known UPI apps) — never bank texts or
  bank apps, which the owner said do not reliably arrive; never a chat
  (MessagingStyle). It is a notification listener, not an accessibility service;
  the Navi/CRED rule above is untouched.
- **Incoming amounts are offered, never saved.** Paying your own other account
  makes Navi post "Received ₹1 … deposited in your SBI account". An amount the
  payment app reports as *incoming*, during or just after a visit to that same
  app, fills the prompt (`PaymentIngestor.offer` → `askForCategory`); nothing is
  saved until a category is tapped. Only *outgoing* amounts are saved on read.
- **Amounts are extracted by Haiku (`ai/PaymentExtractor`), never by patterns.**
  The owner asked for this explicitly after a regex parser missed their real bank
  wording ("Debited Rs:237.00", "A transaction of Rs. 138.43 was made using your
  card"). Do not reintroduce hand-written extraction rules. Gates on *which*
  notifications may be sent (sender, a digit present) are fine; rules about what
  the text says are not. It needs AI on and a key; Settings says so when not.
- **Nothing stops prompting on its own.** An automatic mute after repeated "no
  payment" answers was removed: it was invisible, and a muted app looked exactly
  like broken detection. If prompting should stop, it stops because a switch the
  owner can see was turned off.
- Diagnostics earn their place or get deleted. A long debugging run left
  instruments all over Settings; they were removed once detection worked. Add one
  when you are chasing something, remove it when you are done.

## Payment context, and the ranker that uses it

**Collection is built (schema v3), and `ai/CategoryRanker` uses it (schema v5).**

`MetadataCollector` samples once per payment, at the moment the payment app is
closed — not when anything is typed. That is the moment the phone is still where
the money was spent. The sample is held against the session id, in memory and in
`payment_sessions.contextJson`, because a visit can be sorted hours later, long
after the process died. A payment notification that lands while the app is open
samples right then. Sampling again after a miss is allowed only within 30 minutes
of the visit; past that, location is recorded as `unavailable` rather than wrong.

Stored on `transactions`: `latitude`, `longitude`, `locationAccuracyM`,
`locationAt`, `placeName`, `placeAddress`, `locationSource`, `networkName`,
`networkOperator`, `roaming`, `precedingApp`, `calendarEvent`.

**`locationSource` must always be written.** Values are `live`, `last_known`,
`cached`, `unavailable`, `denied`. Without it a day-old cached position looks
exactly like one taken at the till, and any analysis would trust both equally.
`locationAt` is the age of the *fix*, never the time of the payment.

Location has a three-step fallback because the phone's location switch is often
off: a live fix, then the system's newest last-known position, then Tally's own
remembered fix from a `SharedPreferences` cache. Anything older than 24 hours is
dropped rather than stored as if it meant something.

`precedingApp` is the app that was in front before the payment app opened, taken
from the watcher's own foreground readings — free, no permission, and the
strongest cheap signal there is: arriving from a food app and arriving from the
launcher are very different payments.

Deliberately not stored, because they are already derivable from the row: time of
day, day of week, the payment app, and the gap since the previous payment. A
second copy can only disagree with the first.

Not collected: activity recognition (walking / in vehicle). It needs Play
Services, which is a dependency this app does not otherwise have. Bluetooth
device name would be a cheaper proxy for "in a car" and needs `BLUETOOTH_CONNECT`
— not built, worth considering.

Two things to be careful of when touching this:

- `UsageWatcherService` computes its foreground-service type at runtime. The
  manifest declares `specialUse|location`, but from Android 14 a service claiming
  `location` without the permission **does not start**, which would kill
  detection. Never hard-code the type back into `startForeground`.
- Location must stay a once-per-payment sample. The foreground service exists to
  watch app switches, not to track anyone.

### The ranker that picks the categories (built)

The notification has room for two category buttons and an "Other…" field. Two
buttons chosen by overall frequency are usually wrong for the payment in hand, so
`CategoryRanker` chooses them per payment, in two layers:

- **On-device** (`LocalRanking`, pure, tested): scores every category by past
  *sorted* payments that resemble this one — same payee, nearby (only between
  `live`/`last_known` fixes), same preceding app, same Wi-Fi, hour, amount, note
  words. Per category the closest match counts fully and each further one half as
  much, so a common category cannot outvote one exact payee match.
- **Haiku** re-orders it with the same evidence plus the note, using structured
  output with the category names as an enum. Coordinates are never sent — only
  place names and distances. Cached by prompt hash in `ai_cache`.

An unsorted payment's guessed category is never taught to the merchant table; that
happens in `TallyRepository.sort`, when a person picks it.

### The note is the most important field

Whatever else is collected, **the note must always be sent to the agent.** That is
where the owner writes where they were and what the money was for, in their own
words. It carries more signal than every automatic field combined, and any prompt
or context-assembly that drops it is wrong.

### What it returns

A ranked list of every category with confidence, a merchant name and a need/want
call when it can tell. The top two fill the notification's buttons; the rest fill
the "Other…" choices in that order; the "To sort" chips use the same order.

### Constraints

- Location needs a runtime permission and must be sampled only at prompt time,
  never continuously. The foreground service already exists; do not turn it into
  a location tracker.
- Everything stays on the device except what is deliberately sent to the model,
  and that call is already capped by the monthly AI spend limit in Settings.
- The feature must degrade to today's behaviour when permissions are refused or
  a fix is unavailable. Detection working is worth more than metadata.

## Where things are

- `plan.md` — the full spec, and §12 is the running record of what was rebuilt
  against reality and why. Read it before changing detection.
- `capture/` — the watcher, the session tracker, the notification flow, the
  notification listener and
  `PaymentIngestor` (dedup by UPI ref, then same amount within 10 minutes).
- `ui/inbox/` — "To sort".
- `capture/ui/` — the half-screen sheet behind the notification.
- `ai/` — Claude client, categoriser, digest generation.
- `insights/` — metrics and anomaly detection, all local SQL.
