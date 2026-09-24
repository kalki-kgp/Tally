# Tally — Personal Expense Capture & Insight

A single-user Android app for me (the owner of this repo). It notices when I've just used a UPI app, asks "did you pay something?", lets me log it in one or two taps, and then turns that log into honest, sometimes uncomfortable insights about where my money goes. No accounts, no backend, no Play Store. Data lives on my phone.

**Why this exists:** I'm spending more than I think I am. Every existing tracker fails at the same point — I forget to log. Tally fixes the *capture* problem first (log at the moment of payment, near-zero friction), then makes the *awareness* problem impossible to ignore (a dashboard that tells me the truth, plus AI that explains the "why" behind the numbers).

---

## 1. Decisions already made (do not re-litigate)

These follow from this being a **personal, sideloaded app**. They change everything vs. a Play Store product:

| Decision | Choice | Why |
|---|---|---|
| App-detection mechanism | **AccessibilityService** (primary) | Instant, event-driven foreground-app detection. Play Store policy on accessibility doesn't apply to a sideloaded personal app. UsageStats polling is laggy and battery-hungry — keep it only as a documented fallback, don't build it in v1. |
| Notification parsing | **NotificationListenerService, on by default** | GPay/PhonePe/Paytm/bank apps post notifications containing amount + payee. Parsing them pre-fills the capture sheet → most entries become **1 tap**. This is the killer feature, not an optional extra. |
| Backend | None | Room DB only. Backup = local JSON auto-export + manual CSV. |
| Auth / multi-user | None | Single user. No login screen, ever. |
| AI provider | Claude API, key stored locally | `claude-haiku-4-5-20251001` for categorization (cheap, frequent), `claude-sonnet-5` for weekly digests and chat. Key in EncryptedSharedPreferences, entered once in Settings. |
| Language/UI | Kotlin, Jetpack Compose, Material 3 (dynamic color) | Per PRD. |
| DB | Room + Flow | Per PRD. |
| DI | Hilt | Standard. |
| Charts | Vico for line/bar; custom Compose Canvas for donut, heatmap, sparklines | Vico is Compose-native; the custom ones are simple to draw and let us animate freely. |
| Min SDK | 29 (Android 10) | My phone is recent; don't waste effort on old APIs. Target latest stable SDK. |
| Currency | INR only, `₹`, no decimals shown unless present | Personal app. Store amount as **paise (Long)** to avoid float bugs. |

---

## 2. The core loop

```
Payment app opened
      │  (AccessibilityService sees package in watchlist)
      ▼
Session starts (timestamp, package)
      │  (user pays… meanwhile NotificationListener may catch
      │   "₹450 paid to Blinkit" → parsed & attached to session)
      ▼
Payment app closed / another app foregrounded
      │  debounce 3s (app-switch flicker), ignore sessions < 4s
      ▼
High-priority notification: "Log ₹450 to Blinkit?"
      ├── [Save] → saved with AI/learned category. 1 tap. Done.
      ├── [Edit] → capture bottom sheet (amount prefilled, category chips)
      └── [Dismiss / "No payment"] → session marked no-payment (this is data too)
```

Fallbacks:
- No parsed notification → capture notification says "Log a payment from GPay?" → tapping opens the sheet with numpad focused.
- Missed prompt entirely → manual **+** on Home screen and on the home-screen widget opens the same sheet.

Target: parsed path = 1 tap; unparsed path = tap → amount → category chip → done (≤ 5 s).

---

## 3. Detection engine — implementation notes

### 3.1 Watched packages (seed list, editable in Settings)

| App | Package |
|---|---|
| Google Pay | `com.google.android.apps.nbu.paisa.user` |
| PhonePe | `com.phonepe.app` |
| Paytm | `net.one97.paytm` |
| CRED | `com.dreamplug.androidapp` |
| BHIM | `in.org.npci.upiapp` |
| Amazon (Pay) | `in.amazon.mShop.android.shopping` |
| Navi | verify on device (`adb shell pm list packages | grep -i navi`) |

Store the list in a Room table (`watched_apps`) with a Settings screen listing all installed apps (toggleable), so adding a new UPI app never needs a code change. Verify every package name on the actual device before shipping — implementer must run the `adb` check above.

### 3.2 AccessibilityService

- `accessibilityEventTypes="typeWindowStateChanged"`, no `canRetrieveWindowContent` needed — we only read the event's `packageName`. We are **not** scraping screen content; keep it that minimal.
- State machine in a singleton (`SessionTracker`): `IDLE → IN_PAYMENT_APP (record start) → exited (record end) → debounce 3 s → emit PaymentSession`.
- Ignore transient windows: keyboard (`com.google.android.inputmethod.*`), system UI, the session's own overlays. If user returns to the same payment app within the debounce window, resume the session instead of emitting.
- Discard sessions shorter than 4 s (opened by accident) — but count them (see metrics: friction/impulse data).
- Persist every session to a `payment_sessions` table (start, end, package, outcome: `logged | dismissed | no_payment | ignored`). This table powers the capture-rate metric and costs nothing.

### 3.3 NotificationListenerService

- Listen only to watched packages + bank apps (separate `notification_sources` list).
- Regex bank, per source. Seed patterns (implementer: log raw notification text for a week via a debug screen, then tighten):
  - GPay: `(?:You paid|Paid)\s+₹\s?([\d,]+(?:\.\d{1,2})?)\s+to\s+(.+)`
  - PhonePe: `Paid\s+₹\s?([\d,]+(?:\.\d{1,2})?)\s+to\s+(.+)`
  - Generic debit: `(?:debited|paid|sent).{0,40}?₹\s?([\d,]+(?:\.\d{1,2})?)` + payee heuristics
- Parsed result → `PendingCapture(amount, payeeRaw, source, time)`, attached to the open/most-recent session (±90 s window). Never auto-save without a tap: the confirm tap is what keeps garbage out and doubles as the "was this real?" check.
- **Debug screen** (Settings → "Notification lab"): shows last 50 raw notifications from watched sources and whether/what the parser extracted. This is how I'll fix parsing for apps I actually use, on-device, without guessing.

### 3.4 Robustness

- Both services must survive reboot (`BOOT_COMPLETED` no-op receiver just to warm process; services are re-bound by the system, but detect-and-nag if permissions got revoked).
- Home screen shows a persistent red banner if accessibility or notification access is off, deep-linking to the exact settings page.
- Ask for battery-optimization exemption on first run.
- Duplicate guard: same amount + same payee within 3 minutes → warn before saving a second entry (double-notification from app + bank is common).

---

## 4. Data model (Room)

```kotlin
// amounts always in paise (Long)

@Entity transactions:
  id: Long (PK autogen)
  amountPaise: Long                 // negative = refund/credit
  timestamp: Long                   // epoch millis, when payment happened
  loggedAt: Long                    // when I saved it (friction metric)
  categoryId: Long (FK)
  merchantId: Long? (FK)
  note: String?
  sourceApp: String?                // package name
  entryMethod: Enum { AUTO_PARSED, PROMPT_MANUAL, FULLY_MANUAL, IMPORTED }
  necessity: Enum { NEED, WANT, UNSORTED }   // one extra chip on the sheet; powers the best metric in §6
  excluded: Boolean = false         // rent to flatmate etc. — keep the record, exclude from "spending"

@Entity categories:
  id, name, emoji, colorArgb, isEssentialDefault: Boolean, sortOrder, archived
  // Seed: Food & Dining 🍔, Groceries 🛒, Transport 🚗, Shopping 🛍,
  // Bills & Utilities 💡, Entertainment 🎬, Health 💊, Subscriptions 🔁,
  // Friends/Transfers 🤝, Travel ✈️, Other 📦

@Entity merchants:
  id, canonicalName, rawAliases: List<String> (json), defaultCategoryId?,
  defaultNecessity?, visitCount, totalPaise
  // "BLINKIT*ORDER8823" and "Blinkit" collapse to one merchant.
  // Learned mappings mean the AI/user only categorizes a merchant ONCE.

@Entity payment_sessions:
  id, packageName, startedAt, endedAt, outcome, linkedTransactionId?

@Entity budgets:
  id, categoryId? (null = overall), monthlyLimitPaise, active

@Entity recurring_rules:            // detected or user-confirmed subscriptions
  id, merchantId, expectedAmountPaise, cadence (MONTHLY/WEEKLY/YEARLY),
  nextExpectedDate, confirmed: Boolean

@Entity ai_cache:                   // categorization + digest cache
  key (hash of input), response, model, createdAt
```

DAO layer exposes Flows; every aggregate in §6 is a SQL query, not in-memory Kotlin, so the dashboard is fast with years of data. Write the aggregate queries as `@Dao` functions with unit tests using an in-memory DB and a fixture of ~500 generated transactions.

---

## 5. Capture UX (the 5-second promise)

**Prompt notification** (high priority, sound off, vibrate short):
- Title: `₹450 to Blinkit?` (parsed) or `Log a payment from PhonePe?`
- Actions: **Save** (parsed only) · **Edit** · **No payment**
- Auto-dismisses after 30 min → session outcome `ignored`.

**Capture bottom sheet** (single Compose screen, opened from notification/widget/Home `+`):
- Big amount display + custom numpad (system keyboard is too slow to appear). Prefilled if parsed.
- Merchant line (prefilled from parse; tappable to edit; autocompletes from `merchants`).
- Category: single row of horizontally scrolling chips, ordered by **my** frequency-for-this-merchant, then overall frequency. Learned merchant → chip pre-selected, sheet shows "Save" enabled immediately.
- Need/Want toggle (two chips, default from merchant/category, one optional tap).
- Note field collapsed behind an icon (rarely used, shouldn't cost screen space).
- Save → haptic tick + the amount visibly "flies" into the day total → sheet closes. No confirmation dialog, ever. Undo via snackbar (5 s).
- Backdated entry: long-press `+` opens the same sheet with a date field.

---

## 6. Metrics catalog — what the app shows me and why

This is the heart of the request. Grouped by the question they answer. Each is a defined formula so the implementing agent can build them without product judgment. "Spending" always excludes `excluded=true` and negative amounts unless stated.

### A. "How much am I spending?" (awareness)

| Metric | Definition | Surface |
|---|---|---|
| Today | Σ today | Home, huge animated ticker; widget |
| This week / month | Σ current ISO week / calendar month | Home cards |
| **Safe-to-spend today** | `(monthly budget − month-to-date) / days remaining in month` | Home, the single most actionable number; goes amber < ₹x, red when negative |
| **Projected month-end** | `MTD ÷ days elapsed × days in month`, plus a second estimate that excludes one-off txns > 3× median (so one flight doesn't wreck the projection) | Home + Trends |
| Burn rate | 7-day rolling daily average | Trends sparkline |
| Month vs last month, same-day | `MTD` vs `last month through same day-of-month`, as % and ₹ | Home delta chip ("↑ 22% vs July at this point") |

### B. "Where is it going?" (allocation)

| Metric | Definition | Surface |
|---|---|---|
| Category breakdown | Σ by category, month | Donut + ranked list with per-category Δ vs last month |
| **Biggest movers** | Top 3 categories by absolute ₹ increase MoM | "Why is this month expensive?" card — this answers the question directly |
| Merchant leaderboard | Top merchants by ₹ and by count, month/quarter | Merchants tab. Count matters: 26 × Zepto is a different problem than 1 × flight |
| **Need vs Want split** | Σ by `necessity`, month, trended | One stacked bar per month, 6-month history. The Want line is the "how do I reduce it" answer — it's the compressible part |
| Subscriptions total | Σ confirmed `recurring_rules` monthly-equivalent | "₹2,340/mo is committed before you wake up" card |

### C. "When and how do I spend?" (behavior — this is where cutting happens)

| Metric | Definition | Surface |
|---|---|---|
| **Calendar heatmap** | GitHub-style month grid, cell intensity = day's spend, tap → day detail | Its own tab; the most visceral view of a bad week |
| Day-of-week profile | Avg spend per weekday (last 12 weeks) | Bar chart — "Saturdays cost me 3×" |
| Hour-of-day profile | Histogram of txn amounts by hour | Finds the 11 pm Swiggy pattern |
| **Small-leak report** | Σ and count of txns < ₹200 (threshold configurable), month | "142 small payments = ₹9,870" — death by a thousand chai |
| No-spend days | Count + current/best streak | Home chip with a 🔥; the only gamification in the app |
| Transaction frequency | Txns/day rolling avg | Trends; frequency rising while avg size flat = habit spending |
| **Impulse window** | Spend within sessions where app-open → payment < 30 s, as % of Want spend | Experimental; powered by `payment_sessions` join. Fast payments skew impulsive |
| Median txn size | Median, by month | Rising median = lifestyle creep |

### D. "Is something unusual happening?" (anomalies)

| Metric | Definition | Surface |
|---|---|---|
| Category z-score alert | This week's category spend vs mean/σ of previous 8 weeks; alert at z > 2 | Insight card: "Transport is 2.4× your normal week" |
| Big-txn callout | Any txn > 3× 90-day median | Listed in weekly digest |
| New merchant | First-ever payment to a merchant > ₹500 | Insight card |
| Duplicate suspect | Same merchant+amount within 3 min | Warned at capture (§3.4) |
| Recurring detection | Same merchant, amount within ±10%, interval 28–32 d (or 6–8 d), ≥ 2 occurrences → propose a `recurring_rule` for one-tap confirm | Insight card + Subscriptions screen |

### E. "Am I improving?" (control — v1.5, needs a month of data)

- Budget pace per category: `spent ÷ (limit × dayOfMonth/daysInMonth)` — a pace bar, green ≤ 1.0. Notify once at 80% and at 100%, not daily nagging.
- Want-spend trend, 3-month slope: the single "am I fixing it" number.
- Capture rate: `% of payment_sessions with outcome=logged` — the PRD's 80% metric, now measurable for real because we store every session.
- Median time-to-log (`loggedAt − timestamp`) — the 5-second metric, measured, shown in Settings → About, mostly for fun.

---

## 7. AI layer (Claude API)

Priorities in order; each independently shippable. All calls opt-in via API key in Settings; no key → app fully works, AI cards hide.

1. **Auto-categorization** (haiku): on first sight of a new merchant string, send `merchant raw text + amount + hour + source app` → `{canonicalName, categoryFromList, necessityGuess}`. Cache in `ai_cache`, write to `merchants` as the learned default. User's manual correction always overwrites and is never re-asked. Cost: fractions of a paisa per new merchant; batch offline via WorkManager if network is down at capture time.
2. **Weekly digest** (sonnet, WorkManager, Sunday 8 pm): send a compact JSON of aggregates (category totals ±Δ, top merchants, anomalies from §6D, streaks, budget pace) — *not* raw transactions. Prompt: "You are reviewing MY week; be specific, cite numbers, max 5 bullets, one concrete suggestion, no praise-padding." Rendered as a card on Home + a notification. Store history.
3. **Chat with my data** (sonnet + tool use): a chat screen where Claude gets *tools*, not data: `sum(category?, merchant?, dateRange)`, `list_transactions(filter, limit)`, `top_merchants(n, range)`, `compare(rangeA, rangeB)`. Tools execute locally against Room; only results go to the API. This keeps full history off the wire and makes answers exact instead of hallucinated. Questions I actually want: "how much on food delivery this month vs last?", "what did I buy at Amazon in July?", "if I cap Wants at ₹15k what changes?"
4. **Monthly deep-dive** (sonnet, 1st of month): same as digest but month-scope + a "3 things to cut, with projected monthly savings" section.

Guardrails: hard monthly API budget in Settings (default ₹100) with a running cost estimate; all prompts logged to a debug screen; every AI output labeled with model + timestamp.

---

## 8. Frontend — screens & the "crazy" bar

Material 3, dynamic color (wallpaper-derived), dark-mode first (I check spending at night). Every number animates: count-up tickers (`AnimatedContent`/custom), charts draw in with 300–500 ms eased sweeps, chips spring. Haptics on save/toggle. But: **Home must answer "am I okay this month?" in one glance, zero scrolls** — flashy never beats glanceable.

1. **Home** — Safe-to-spend hero number (color-coded) · today ticker · month progress vs budget pace bar · MoM delta chip · no-spend streak chip · latest insight/digest card · last 5 txns · FAB `+`.
2. **Analytics** (tabs):
   - *Trends*: daily bars (month), burn-rate line, projection band, median txn line.
   - *Categories*: animated donut → tap slice → that category's trend + its merchants + its txns.
   - *Patterns*: weekday bars, hour histogram, small-leak card, impulse card.
   - *Merchants*: leaderboard, sortable ₹/count, tap → merchant detail with full history sparkline.
3. **Calendar** — heatmap months, vertically paged; tap day → bottom sheet with day's txns.
4. **Transactions** — search (merchant/note/amount), filter chips (category, range, necessity, source app), swipe-to-edit/delete, multi-select → bulk re-categorize.
5. **Budgets & Subscriptions** — per-category pace bars; detected-recurring confirm list.
6. **Chat** — the §7.3 screen.
7. **Settings** — watched apps, notification lab, categories editor, budgets, API key + spend cap, export (CSV via share sheet / SAF), auto-backup toggle, permissions health.
8. **Widget** (Glance): today + safe-to-spend + `+` button deep-linking to capture sheet. This is v1, not nice-to-have — half my manual entries will start here.

CSV export columns: `date,time,amount,category,merchant,necessity,note,source_app,entry_method`. Auto-backup: nightly JSON dump (full DB) to `Documents/Tally/`, keep last 14, restore-from-file in Settings.

---

## 9. Architecture & project structure

Single Gradle module (`app`) — multi-module is ceremony a solo app doesn't need. MVVM + repository. Packages:

```
dev.pixelchutney.tally
├── capture/        # AccessibilityService, NotificationListener, SessionTracker,
│                   # parsers/ (per-app regex banks), PromptNotifier, CaptureSheet UI
├── data/           # Room: entities, DAOs (incl. every §6 aggregate query), db,
│                   # repositories, backup/ (json + csv)
├── insights/       # metric calculators that aren't pure SQL (streaks, projections,
│                   # anomaly z-scores, recurring detector) — pure Kotlin, unit-tested
├── ai/             # ClaudeClient (ktor/okhttp), tool registry for chat,
│                   # categorizer, digest worker, cost meter
├── ui/             # theme/, home/, analytics/, calendar/, transactions/,
│                   # budgets/, chat/, settings/, widget/ (Glance), components/
│                   # (charts: Donut, Heatmap, Sparkline, TickerText, PaceBar)
└── di/
```

Libraries: Compose BOM, Material3, Hilt, Room, DataStore (settings), WorkManager, Vico, Glance, kotlinx-serialization, OkHttp (Claude API — call Messages API directly, no SDK dependency needed on Android).

Testing bar (pragmatic, not dogmatic): unit tests for **all §6 formulas**, all notification parsers (fixture strings per app), the recurring detector, and the session state machine. UI tests skipped except one capture-sheet flow test.

---

## 10. Build phases (each ends runnable on my phone)

**Phase 1 — Capture works (the bet):** project scaffold, theme, Room core (`transactions/categories/merchants/payment_sessions`), AccessibilityService + SessionTracker, prompt notification, capture sheet, manual `+`, transactions list, plain today/month totals on Home, CSV export. *Exit: a week of real use where logging feels ≤ 5 s.*

**Phase 2 — Parsing + widget (1-tap):** NotificationListener, GPay/PhonePe/Paytm parsers, notification lab, Save-from-notification, duplicate guard, merchant learning/autocomplete, Glance widget, JSON auto-backup.

**Phase 3 — Metrics:** all of §6 A–D, Analytics tabs, calendar heatmap, donut/heatmap/sparkline components, animations, budgets + pace + the two budget notifications.

**Phase 4 — AI:** categorizer, weekly digest, chat-with-tools, monthly deep-dive, cost meter.

**Phase 5 — Control loop (after ~1 month of data):** §6E, recurring detection UI, insight-card ranking, digest tuning based on what I actually read.

**Explicitly out of scope for now:** bank-account linking, SMS reading, multi-currency, shared/group expenses, iOS/web, Play Store release, income tracking (Tally tracks outflow; salary is not the problem).

---

## 11. Risks & edge cases the implementer must handle

- **Accessibility service silently killed** by OEM battery managers → permissions-health banner (§3.4) + a daily WorkManager self-check that re-nags via notification if either service is dead.
- **Payment made but app never opened** (web checkout, autopay) → notification parser still catches most bank/UPI notifications with no session; attach to a synthetic session.
- **Checking balance ≠ paying** → that's exactly why nothing auto-saves; "No payment" must be one tap and guilt-free, and `no_payment` sessions must NOT trigger a nag pattern (if 3 consecutive sessions for an app are `no_payment`, back off prompting that app for 2 h).
- **Refunds** → negative amount via `+/−` toggle on the numpad; excluded from spend, shown netted in merchant detail.
- **Amount edge cases** → paise storage; parser must handle `1,234.50`, `1234`, `₹ 1,234`.
- **Notification text format changes** after app updates → parsers are data (regex bank per package, editable expectation), notification lab exists precisely to repair them in minutes.
- **DB migrations** → destructive migration is fine until Phase 2 ships; from then on, real migrations + the JSON backup as the safety net.

---

## 12. Build status — updated 2026-08-28 (v3)

### Detection was rebuilt, twice, against reality

The plan assumed AccessibilityService with notification parsing as the killer
feature. Both are gone. What actually happened on the device:

1. **Navi (and CRED) scan for enabled accessibility services and refuse to let you
   pay until you disable them.** They cannot distinguish Tally from screen-scraping
   malware, and they are not wrong to be strict. Accessibility is therefore not
   available on the one phone this app exists for.
2. With accessibility off, notification parsing was the only remaining detector,
   and it was removed at the owner's request.

Detection now uses the **Usage Access API** (`UsageStatsManager.queryEvents`),
polled every 1.5s by a foreground service. It reads the same fact — which app came
to the front — is not an accessibility service, and does not trip the bank-app
scanners. The cost is a permanent low-priority notification, which Android
requires of any foreground service. That is the trade and it is not avoidable.

### Foreground is read as a state, not as a stream of events (v10)

The first Usage Access implementation consumed events: each poll asked for
everything since the previous poll and fed opens and closes to `SessionTracker`
one at a time. Two bugs came out of that, and together they meant detection
worked roughly one time in three.

1. **Late events fell into a hole.** The poll advanced its cursor to *now* and
   skipped any event stamped before it. Usage events are written with a second or
   two of lag, so an event that arrived after the cursor had moved past its
   timestamp was discarded permanently. Symptom: opening a payment app three times
   and having one visit recorded.
2. **A dropped close was unrecoverable.** With state held only from events, one
   missed close left the tracker convinced the app was still on screen. Every
   later visit to that app hit the "already open" branch and was swallowed. That
   is why rows read `still open` forever and why re-opening the same app produced
   nothing at all.

`poll()` now reads a 12-second window every 1.5s, keeps the most recent
`ACTIVITY_RESUMED`, and reports that package as the current foreground —
unconditionally, every tick, changed or not. `SessionTracker.onForeground` is
idempotent. A missed reading now costs one poll interval instead of the session,
because the next poll re-establishes ground truth from scratch.

`KEYGUARD_SHOWN` / `SCREEN_NON_INTERACTIVE` are read in the same window, so
locking the phone reports a foreground of `null` rather than leaving the payment
app apparently on top.

### The scaffolding built to find that bug has been removed

Nine rounds of debugging left instruments all over the app. With detection
working they are dead weight, and each one implied a failure mode that no longer
exists. Removed:

- **Delivery report** (`PromptNotifier.report`, `DeliveryReport`, `lastPostError`,
  the channel deep link, "Send a test prompt"). Built to explain why prompts
  never appeared; the answer was `FLAG_MUTABLE` on RemoteInput actions, which is
  now simply correct in the code.
- **Detection log** — the list of recent visits with `asked` / `still open` /
  `closed` labels, plus `SessionLine` and `outcomeLabel`. It existed to show that
  sessions were not closing. They close.
- **"Ending a visit failed" panel** and `SessionTracker.lastCloseError`. The
  failure it reported was the self-cancelling coroutine, now fixed.
- **`sweepStaleSession`** — the 180-second backstop that force-closed a visit that
  had stayed open too long. Under state-based reading it is not just redundant but
  wrong: a genuinely long payment (OTP, bank redirect) would trip it and fire the
  prompt while you were still mid-payment. Orphaned rows are handled by the daily
  `MaintenanceWorker`, which already expires them.
- **"Ignore visits under"** (`minSessionSeconds`) — a filter for phantom short
  sessions that the dropped-event bug was manufacturing.
- **Poll counters** (`lastEventCount`, `lastUsefulEventAt`) and the on-screen
  package readout.

What stays is one row in the Detection card: running or stopped, with a dot, tap
to restart. That one is not scaffolding — OEM battery managers kill foreground
services silently, and a dead watcher is indistinguishable from a quiet week.

### Chats are saved, and location degrades honestly (v16)

**Saved chats.** Schema v4 adds `chats` and `chat_messages`. A chat row is created
when the first question is asked, not when the screen opens, so it can be named
after that question — an empty chat has nothing to be named after, and any left
behind by a crash are swept on next open. The Ask header gains a "Chats (n)"
panel: tap a row to carry on where it stopped, "Delete" to remove one (messages
cascade). Nothing prunes chats automatically.

**Location fallback.** The switch being off is the common case, not an edge case,
and the previous code stored nothing at all when it happened. Three steps now: a
live fix, then the system's newest last-known position, then Tally's own
remembered fix cached from the last live reading. Anything older than 24 hours is
dropped rather than stored as though it meant something.

The important part is `locationSource` — `live`, `last_known`, `cached`,
`unavailable`, `denied` — recorded on every payment. Without it a day-old cached
position is indistinguishable from one taken at the till, and later analysis would
trust both equally. `locationAt` is the age of the fix, never the time of payment.
The cache stores coordinates as raw `Double` bits; a `Float` latitude rounds the
position by enough to matter.

**Three more fields.** `precedingApp` — the app in front before the payment app,
taken from the watcher's own readings, free and permission-free, and the strongest
cheap signal available: arriving from a food app and arriving from the launcher
are very different payments. `networkOperator` and `roaming` — both survive
location being off, and both change when travelling, which is exactly when
spending stops resembling the baseline.

### Payment context captured with every payment (v15)

Schema v3 adds eight columns to `transactions`: `latitude`, `longitude`,
`locationAccuracyM`, `locationAt`, `placeName`, `placeAddress`, `networkName`,
`calendarEvent`. Nothing reads them yet. They exist so that when categories start
being suggested there is a history to reason over, rather than an empty table on
the day the feature ships.

**The sample is taken when the prompt goes out, not when the amount is typed.**
That is the moment the payment app was closed, so it is the moment the phone is
still at the place where the money was spent — and a notification can sit
unanswered for half an hour. The reading is held in memory against the session id
and consumed at save. A miss (process death in between) falls back to sampling
again, which is worse but beats nothing.

`MetadataCollector` uses `LocationManager.getCurrentLocation` with a ten-second
ceiling, falling back to the newest last-known fix, then reverse-geocodes it.
Wi-Fi SSID comes from `WifiManager` — readable only with location permission, and
a good home-versus-out signal indoors where a fix is poor. Calendar is read only
if that permission happens to be granted; it is never requested at startup.

Activity recognition was in the shortlist and is not built: it needs Play
Services, which this app does not otherwise depend on.

One trap worth naming. The manifest now declares
`foregroundServiceType="specialUse|location"`, and from Android 14 a service that
claims the `location` type without the permission **fails to start at all**. That
would have killed detection outright for a refused permission. `enterForeground`
therefore computes the type from what has actually been granted, and re-asserts it
on every `onStartCommand` so granting location later upgrades the running service.

### Keeping the watcher alive (v14)

The foreground service died and stayed dead until it was restarted by hand.
Recovery was the daily maintenance pass or opening the app, so detection could be
down for hours with no sign of it.

Four layers now, because no single one is sufficient:

1. `WatchdogWorker`, every 15 minutes — WorkManager's floor for periodic work.
   Starting a service that is already running is a no-op, so it is cheap.
2. `onTaskRemoved` — swiping Tally out of recents takes the service with it on
   most OEM builds, foreground service or not. This asks for it back.
3. `START_STICKY` and the boot receiver, both of which already existed.
4. Opening Home or Settings, which already existed.

**None of this can be a guarantee, and the code no longer pretends otherwise.**
Android 12+ refuses background foreground-service starts in most situations, so
`UsageWatcherService.start` returns a Boolean and the watchdog reports a refusal
as a notification rather than assuming detection came back. Exempting Tally from
battery optimisation is the real fix, and the message says so.

### Auto-muting removed (v13)

A watched app was muted for two hours after three consecutive "no payment"
answers, with the streak resetting only when a payment was actually logged.
During the detection debugging, Navi collected enough of those answers to trip
it. The result: visits were detected and closed correctly, recorded as
`DISMISSED`, and no notification was ever posted — indistinguishable from broken
detection, and reported as such.

Two things were wrong with it. It was invisible: nothing in the UI said an app
was muted or when it would come back. And the trade was bad on its own terms —
this app exists to catch spending, so silently skipping two hours of a payment
app to save three notifications loses exactly what it is for.

`recordNoPayment` now only counts. Settings shows the streak on the app's row
once it reaches four, and turning the app off is the only thing that stops it
asking. `mutedUntil` stays as a dead column so no migration is needed; nothing
reads it, so values left over from older builds are already inert.

### Amount entry is in rupees (v12)

The numpad collected paise, so ₹240 was typed 2-4-0-0-0 and read as ₹2.40 on the
way through. The bottom-left key was `00` to bridge the gap. Since almost every
payment is a whole number of rupees, that put the cost on the common case to
serve the rare one.

Entry is now rupees, and the `00` key is a decimal point pressed only when paise
are actually needed. `CaptureState.amountEntry` holds what was typed — `"240"`,
`"240."`, `"240.50"` — and `Money.fromEntry` / `toEntry` / `formatEntry` convert.
`formatEntry` exists separately from `format` because the display has to show the
decimal point the instant it is pressed; formatting from paise would swallow the
tap until a paise digit arrived.

Note the notification's inline amount field already read rupees via
`Money.fromRupeeString`. The sheet and the shade now agree.

### Entry happens entirely in the notification

Three steps against one notification id:
1. "Did you pay in PhonePe?" → type the amount inline (`RemoteInput`).
2. "₹450" → two most-used categories as buttons, plus "Other…" with every category
   offered as a typed choice.
3. "Saved ₹450 · Food" → optional note inline, or Undo.

Saving is never blocked on the note. The full capture sheet still exists behind
the notification body and the `+` button for anything the shade cannot express
(backdating, merchant, need/want, refunds).

### Removed
- `TallyAccessibilityService` and its config.
- `TallyNotificationListenerService`, `PaymentNotificationParser`, and its tests.
- The `notification_log` table and the Notification lab screen (schema v2 drops it).
- Bank package list in `Seed`.

### Install
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
Debug-signed for sideloading; minification off; ~28 MB.

### First run, in order
1. Open Tally. Grant the notification permission when asked.
2. Settings → **Usage access** → allow Tally. The foreground service starts itself.
3. Settings → **Send a test prompt** to confirm notifications arrive.
4. Settings → **Add a payment app** if Navi's real package differs from the seeded
   `com.navi.android` guess.

### Still not built
- No category editor (add / rename / recolour).
- Bulk multi-select in Activity exists in the view model, not in the UI.
- No onboarding flow; the Home permission banner does that job.

### Sort later, and amounts read from notifications (v17, schema v5)

The owner stopped using Tally after three or four days. The prompt after every
payment was the reason: the question arrived at the least convenient moment, and
answering it every time was tiring. What they will reliably remember later is
*what* they paid for, not *how much*.

So the order is reversed. `PaymentNotificationListener` reads the amount from the
UPI app's notification or the bank's debit text, and `PaymentIngestor` saves it at
once with `reviewed = false`. It counts in every total from that moment. The
category starts as a guess and the payment waits in **To sort** (Home banner, and
one silent notification with the two best-ranked categories as buttons).

- **Visits with no amount.** A finished visit waits 45 s for a notification (bank
  texts lag). If none comes it goes to To sort as "Opened PhonePe — no amount
  found", with Enter amount / No payment. Nothing is lost, nothing interrupts.
- **Duplicates.** One payment usually produces two notifications (app + bank
  text). A matching UPI reference settles it; otherwise the same amount within 10
  minutes, unless both carry different references.
- **What is refused.** Credits, refunds, requests, OTPs, failed payments, "will be
  debited" reminders, offers. Chat notifications from payment apps (WhatsApp).
  SMS from anything that is not a bank sender ID.
- **Sort later** is a visible switch in Settings; off restores the heads-up flow,
  now with the amount already filled when a notification was read.
- **Restricted settings.** Android hides notification access for sideloaded apps
  until App info → ⋮ → Allow restricted settings is tapped once.

`CategoryRanker` is the category agent described in CLAUDE.md: on-device scoring
first, Haiku with the note second.

## 13. v2 parking lot

Savings goals ("skip 4 delivery orders = new keyboard"), receipt photo attach, voice entry, on-device weekly report as shareable image, Wear OS tile, "spending personality" quarterly report, geofenced context (merchant guess from location), cash-spend quick log, export to Google Sheets.
