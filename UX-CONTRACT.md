# Attention Guard UX Contract

This contract describes observable Android behavior. Visual tokens live in
`DESIGN.md`; runtime owners live in `GuardUi`, `GuardMotion`, `EventStore`, and the
activities, `MessageArchive`, and `HistorySession`.

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Table Selection | Not applicable: no table widget in the native app | Android navigation and event-list contract | Event row tap only | Unit filtering + manual device check |
| Select/Listbox | Not applicable: no select/listbox is exposed | Settings contract | Toggle, text field, seek bar | Unit validation + manual device check |
| Date | Native Android `DatePickerDialog` in `CaptureActivity`; `ChatDateParser` for separators | Inclusive date-only `HistoryRange`, device timezone | Platform calendar for start/end; event labels remain display-only | ChatDateParser/HistorySession tests; manual calendar check pending |
| Form | `GuardUi.field` + settings explicit-save + capture explicit-prepare | `DESIGN.md` and settings section below | DeepSeek save; history prepare then in-chat confirmation; OCR consent | ActivityFlow/CaptureActivity tests + lint |
| Scrollbar | Android `ScrollView` platform owner | `GuardUi.scroll` and system theme | One vertical scroll surface per page | Build/lint + manual device check |
| Toast | `GuardUi.feedback` Snackbar and overlay Toast | Shared feedback wording below | Snackbar in app, Toast over other apps | Unit/message review + manual device check |
| CRUD | `EventStore` and `MessageArchive` | Separate event and raw-message lifecycles | Events complete/restore; raw messages paginate/clear with confirmation | EventStore/MessageArchive/Activity tests |

## Surface ownership

- `MainActivity` owns the four-tab shell, attention summary, ledger, sources, and
  profile/permission entry points.
- `SettingsActivity` owns DeepSeek-only configuration and explicit save/cancel.
- `CaptureActivity` owns diagnosis, raw-message paging, local OCR opt-in, and history preparation.
- `AttentionOverlayController` owns the read-only cross-app card. It may open the
  app, expand a stored event, reset its position, or hide itself. It also confirms,
  pauses and cancels an explicitly prepared history session; dismiss pauses it.
- `EventStore` is the only owner of persisted event JSON. `MainActivity` never
  writes event JSON directly.
- `ChatCaptureService` is the only owner of capture/debounce/cancellation. It reads
  the current visible accessibility window and never edits or sends a message.

## Event lifecycle

1. A visible chat snapshot passes the local Attention Gate.
2. If it is actionable, due, or high priority, the app may call DeepSeek when the
   user has enabled cloud enhancement and a usable encrypted key exists.
3. The resulting event is merged by stable ID and stored locally with a bounded
   timeline and original evidence.
4. The user can open detail, expand evidence, mark complete, or restore the prior
   status. Completion never invents a new status.
5. A failed DeepSeek request keeps the local event and shows a degraded-mode notice.
   A failed local read or save never overwrites the previous record.

## Navigation and state

- Back from detail returns to the previous list; back from another tab returns to
  注意力; back from 注意力 exits the activity.
- The overlay opens its stored event in the ledger on cold launch or through an
  existing activity. Missing event IDs fall back to the ledger. This entry leaves
  demo mode so real notifications never display an unrelated demo event.
- Tab, filter, query, detail ID, evidence disclosure, list limit, and each tab's
  scroll position survive activity recreation.
- The search clear affordance clears immediately and resets the result list.
- Empty states distinguish “no events” from “no matching events” and offer a path
  to settings or demo mode where appropriate.

## Settings and sensitive data

- The only visible provider is DeepSeek Official at `api.deepseek.com`.
- Cloud enhancement is opt-in. It sends the current visible conversation name,
  up to 12 visible messages (body capped at 2,000 characters each), associated
  sender/side/mention metadata, up to 2,000 characters of user-entered context,
  the current time, and a preliminary local event title/priority/score.
- API keys are encrypted with an Android Keystore AES-GCM key. Legacy plaintext is
  read only for one migration and removed after a successful encrypted commit.
- The current Attention Guard request path does not log keys, raw chat text, or
  provider response bodies. The DeepSeek client is packaged and called only by
  the product flow; it is outside this guarantee. The connection test sends a
  built-in sample only. A key is sent to DeepSeek as an HTTPS authorization header.
- Event evidence is private local JSON, not application-encrypted. Android backup
  is disabled. Completing an event does not delete its original evidence.
- Leaving unsaved settings requires an app-owned confirmation dialog.

## Capture and privacy boundary

The service accepts only the foreground supported chat package and the visible
conversation tree. It does not scan background groups, query a chat database, use
Xposed/hooking, touch the input field, or click send. If the accessibility tree is
empty, the previous snapshot and pending request are invalidated. The current
WeChat adapter reads visible bubble nodes and returns no event when it cannot prove
the needed text. A changed-ID fallback needs a bottom composer, message list,
same-row avatar and long-clickable body. A proven but unreadable bubble may use
on-device OCR only after an app-owned consent dialog; screenshots are not stored
or uploaded by the capture pipeline. OCR outputs are labeled and never sent to
DeepSeek or used for automatic event creation. No geometry evidence means no OCR.

## Raw Messages And History

- Raw text is saved to app-private SQLite before event selection. It is not
  application-encrypted. Backup remains disabled; retention is until the user
  confirms a clear or uninstalls. Clearing pauses the master switch, serializes
  against queued writes, and does not erase separate event evidence.
- History start/end are inclusive device-local dates, validated in both the UI
  and service. The native calendar is intentionally platform-owned. Draft target,
  dates and automatic/manual selection survive activity recreation.
- Preparing a history task does not scroll. The exact displayed conversation
  name must match and the user must press Start on the in-chat overlay. Identical
  names cannot establish unique chat identity and remain a compatibility limit.
- Manual scrolling is the default. Auto scrolling uses only the identified list's
  `ACTION_SCROLL_BACKWARD`, at least 4 seconds apart after storage settles. There
  is no gesture, editable-field action, paste, send, or automatic group switching.
- Leaving WeChat, lock screen, unconfirmed chat, changed settings, unavailable
  text, save/scroll failure, three unchanged pages, or task limits pause scrolling.
  Resume is explicit. Limits are 200 observed screens/scroll attempts or 15 minutes
  from initial start; pausing cannot reset the budget. Process death never resumes.
- Dates come from visible whole separator labels, not dates inside message text.
  Missing dates remain unknown and are counted separately, not claimed as in-range.
  An all-known screen older than the start ends traversal but never proves full
  coverage. Undated repeats can be updated when neighboring overlap exposes a day.
- Deduplication aligns adjacent screens with ordered, nonambiguous overlap; it is
  not global text hashing. Single repeated words, title collisions, content changes,
  restart, or nonoverlapping screens can produce duplicates/gaps. Preserve evidence
  rather than silently discard it. Session progress reports gaps, not a fake percent.
- A ready/running/paused history target is isolated from live AI analysis; history
  scans and OCR results do not trigger DeepSeek. No archive-wide AI batch is added.
- Raw records load 50 at a time, newest captured records first. History scope is
  separate from all captured records. Read failure offers retry without mutation.
- Diagnostic copy contains status, counts, timestamps, OS/device and WeChat version;
  never conversation titles, message bodies, screenshots or API keys.

## Async, failure, and recovery contract

- Tree reads are throttled at 180ms with a 1.5-second recovery check. Raw messages
  persist independently; local events need not wait for cloud. Cloud bursts are
  debounced for roughly 2.2 seconds.
- The service uses an accessibility overlay; no separate application-overlay grant
  is required for this path. Unreadable states still show a diagnostic entry. Mount
  failures are recorded; a dismissed overlay stays hidden until chat/window changes.
- OCR waits 250ms after hiding the overlay, rechecks foreground and bubble bounds,
  and ignores results after scene changes/cancel. Shots are at least 8 seconds apart;
  at most three failed attempts per scene, a 20-second timeout, and one success per
  unchanged layout. Scrolling or explicit retry rearms it; no idle capture loop.
- Switching package, conversation, settings, or service lifecycle cancels the old
  request and increments a generation token. Late responses are ignored.
- DeepSeek timeouts and HTTP 401/402/429/model errors map to short Chinese recovery
  messages. Raw response bodies are not shown.
- The connection test has a visible busy state, a cancel action, and a result live
  region. Leaving the screen cancels the request.
- Event storage writes a same-directory temporary JSON file, syncs it, and replaces
  the prior file atomically. Corrupt existing JSON blocks writes instead of
  reverting to stale preferences. Legacy preferences migrate on first mutation.
  The merged timeline is capped at 8 entries and evidence at 6 entries; the total
  event count is not capped. Host filesystem failures are covered by tests;
  device process-death and low-storage testing remain pending.
- DeepSeek responses must finish normally, contain a complete typed event object,
  and stay within 65,536 characters. Invalid responses fall back to local rules;
  only the user may mark an event completed. Valid empty optional fields clear
  heuristic due/action/consequence guesses.

## Accessibility and localization

- All interactive targets are at least 48dp and icon-only controls have Chinese
  content descriptions.
- Event rows expose one semantic action instead of exposing decorative child text.
- Important result/status text uses a polite live region where it changes in place.
- System bars, display cutouts, and the IME are applied through window insets.
- `zh-CN` is the release locale; the brand and API model names stay unchanged.
- Reduced-motion behavior follows `ValueAnimator.areAnimatorsEnabled()`.

## Android-only verification boundary

Browser E2E, CSS scrollbar assertions, HTML select popup tests, and web pixel
regression are not applicable to this traditional Android View project. The
replacement evidence is Gradle build, pure JVM and Robolectric tests, Android lint,
APK metadata and resource inspection, plus a required future real-device pass for
accessibility, overlay geometry, WeChat node compatibility, and touch behavior.
Robolectric layout measurements do not constitute screenshot or pixel verification.

