# CLAUDE.md — DuoBackup

Instructions for any coding agent working in this repository. Read this file fully before
making changes. The full research reference is `info.md` (Indonesian). This file holds the
rules and locked decisions. `info.md` holds the detail. Section references below (§x.y)
point into `info.md`.

## 1. What this project is

DuoBackup backs up an Android phone's **shared storage** to two destinations at once:

1. **Telegram**: a private channel, accessed through the user's own account via TDLib (MTProto).
2. **Google Drive**: the user's personal Google account, scope `drive.file`.

- **APK (`app/`)**: the engine. It scans, hashes, chunks, optionally encrypts, uploads,
  restores and runs background work.
- **Web (`web/`)**: a static panel. It reads the index snapshot from Drive to browse files and
  status and to trigger restores. It never touches phone files directly.

Build order: **APK first**. It defines the manifest format. The web panel follows once that
format is stable.

## 2. Repository layout

```
app/                     Android Gradle project (open this folder in Android Studio)
  app/                   :app        Android application (Compose UI, WorkManager, DI wiring)
  core/                  :core       pure Kotlin/JVM: models, manifest codec, chunk math,
                                     change detection, StreamCrypto format, index snapshot
  drive/                 :drive      Google Drive transport adapter
  telegram/              :telegram   Telegram (TDLib) transport adapter
  gradle/libs.versions.toml          single version catalog, no versions inline elsewhere
web/                     React + Vite + TypeScript SPA
.github/workflows/       android.yml, web.yml
info.md                  research reference (do not delete; update when facts change)
```

Keep logic that can be tested without Android in `:core` (JVM unit tests, fast in CI).
`:drive` and `:telegram` implement the transport interface from `:core`. They must not depend
on each other.

## 3. Locked technical decisions

Do not change these without the owner explicitly asking.

| Area | Decision |
|---|---|
| Language | Kotlin (latest stable 2.x), JVM toolchain 17 |
| Android | `minSdk 26`, `compileSdk`/`targetSdk` = latest stable (currently 36) |
| Package | `io.github.rosyidputrar.duobackup` |
| UI | Jetpack Compose + Material 3, single activity |
| DI | Hilt |
| DB | Room (WAL), schema per §8.1, exported schemas committed, explicit migrations only |
| Background | WorkManager driving a foreground service of type `dataSync` |
| Settings / secrets | DataStore + Tink AEAD with an Android Keystore master key (no deprecated EncryptedSharedPreferences) |
| HTTP / JSON | OkHttp + kotlinx.serialization |
| Telegram | TDLib via `com.github.tdlibx:td` (JitPack); user supplies their own `api_id`/`api_hash` |
| Drive auth (APK) | Google Identity Services `AuthorizationClient`, scope `https://www.googleapis.com/auth/drive.file` only |
| Drive upload | Hand-written resumable upload (§7.3, §12.3). No Google Java client library |
| Drive auth (web) | OAuth 2.0 Authorization Code + PKCE. No client secret in JS |
| Web | React + Vite + TypeScript, static hosting, no backend storing data |
| CI | GitHub Actions (see §7) |
| Storage access | `MANAGE_EXTERNAL_STORAGE` (All Files Access) for shared storage. **No root, no libsu, no backup of other apps' private data. Out of scope.** |
| Encryption | Optional, **off by default**. When on: StreamCrypto format (§9.1) + two-layer keys (§9.2) |

## 4. Core invariants (never violate)

1. **Record the location first.** A part counts as uploaded only after its `message_id` +
   `remote_file_id` (Telegram) or `drive_file_id` (Drive) is committed to Room.
   Existing `file_parts` rows are the resume token. Skip them on retry.
2. **Verify everything on restore.** Check SHA-256 per part, then per file. If one destination
   fails verification, fall back to the other. Never write restored data straight to the
   original path: write `<name>.part`, verify, rename atomically, restore mtime (§10.4).
3. **Treat the manifest format as an API.** Caption prefixes `td1:` / `tde1:`, `appProperties`
   keys and the index snapshot schema (§8.2–§8.4) are versioned. Any change bumps the
   version, and old versions must stay readable. Add a codec round-trip test for every version.
4. **Use the single Telegram pacer.** Every TDLib call that creates or edits a message goes
   through one global pacer. `FLOOD_WAIT_n` pauses the whole queue for `n + 1` seconds.
   Never parallelise across clients to "go faster" (§6.2, §12.2).
5. **Drive retries are classified.** 403 rate-limit / 429 / 5xx / network: exponential
   backoff (base 2 s, factor 2, cap 1 h) and keep the session. Other 4xx: drop the session.
   404/410 on a position query: restart from zero. Reset the failure counter after every
   successful chunk. Chunks are multiples of 256 KiB except the last.
6. **Use scratch files per part.** Never copy a whole file to temp. Free space needed during
   backup is about one part size.
7. **Identify Drive files by `id` / `appProperties`**, never by name.
8. **Credentials come from the user.** Never hardcode `api_id`, `api_hash`, OAuth client
   secrets or tokens. Store them encrypted. Strip tokens from logs and error messages.
9. **Never make backup files public.** No sharing permissions on Drive files.
10. **Incremental by default.** Check size, then mtime, then SHA-256 (§10.1). Skip files
    modified less than 45 s ago.

## 5. Account limits: detect at runtime, never hardcode

Telegram free vs Premium and the Drive quota differ per account and can change mid-life
(a subscription expires). The app **detects** them. Constants live only in
`core/.../Limits.kt` as conservative fallbacks, used when detection fails.

**Telegram (TDLib)**

- Read option `is_premium`. Listen to `updateOption` so a mid-session change is caught.
- Call `getApplicationConfig` and read `upload_max_fileparts_premium` or
  `upload_max_fileparts_default`. Max file bytes = fileparts × 512 KiB.
  Fallback when missing: 4000 parts (2,097,152,000 bytes).
- Read option `message_caption_length_max` to get the caption budget for manifests
  (fallback: 1024). The manifest codec must fit the detected budget and fail loudly, never
  truncate silently.
- Part size = `min(512 MiB target, detected max file bytes)`. Re-detect at the start of every
  backup session. Parts already uploaded stay valid when limits shrink.
- Throttling after tens of GB on free accounts is expected. Handle it with the pacer and
  backoff, not with special cases.

**Google Drive**

- Read `GET /drive/v3/about?fields=storageQuota` at session start. The quota pool is shared
  with Gmail/Photos, so never assume a size such as 400 GB.
- Show free space vs queued bytes before a session. Stop starting new uploads when the
  remaining quota is below the next part. Treat `storageQuotaExceeded` / `quotaExceeded`
  as "pause and notify", not as a crash.

## 6. Coding conventions

- **Language:** all code, identifiers, comments, commit messages, PR titles/bodies and new
  docs are in **English**. User-facing app strings go in `strings.xml`, with English as the
  default (other locales may be added later).
- **Kotlin:**
  - Use coroutines + Flow. No blocking I/O on the main thread.
  - Use `suspend` transport APIs and structured concurrency. No `GlobalScope`.
- **Errors:**
  - Model them as sealed results at module boundaries.
  - Do not swallow exceptions.
  - Log with a tag, and never log secrets or full file paths at info level.
- **Tests:**
  - Unit-test `:core` heavily: manifest codec, chunk math, StreamCrypto round-trip and
    tamper detection, change detection, snapshot merge.
  - Write crypto and manifest code test-first.
  - Never delete, skip or weaken a test to make CI pass.
- **Formatting:** ktlint (via Gradle plugin). Run `./gradlew ktlintCheck` before committing.
  Web uses ESLint + Prettier.
- **Dependencies:** add them through `gradle/libs.versions.toml` only. Prefer AndroidX/Google
  libraries. Justify any new third-party dependency in the PR body.
- **Keep it small:** no speculative abstractions beyond the `BackupTransport` interface (§1.3).

## 7. Build, test, CI

The owner works from a phone, and there is no local Android SDK. **GitHub Actions is the
build machine.**

- `.github/workflows/android.yml` triggers on changes under `app/**`. It runs (working
  directory `app/`) `./gradlew ktlintCheck lint test assembleDebug`, then uploads the debug
  APK as an artifact.
- `.github/workflows/web.yml` triggers on changes under `web/**`. It runs
  `npm ci && npm run lint && npm test && npm run build`.
- Every PR must be green before it is considered done. If CI fails, read the logs through the
  GitHub REST API and fix the cause.
- Local commands, when an SDK is available:
  - `cd app && ./gradlew test`
  - `cd app && ./gradlew assembleDebug`
  - `cd web && npm run dev`

## 8. Git workflow (mandatory)

- **Commit locally often.** Make one logical step per commit, so progress is never lost.
- **Never commit or push to `main`.** Start each task on a new branch from an up-to-date `main`:
  `feat/<short-name>`, `fix/<short-name>`, `docs/<short-name>`, `ci/<short-name>`,
  `refactor/<short-name>`.
- **Commit messages:** Conventional Commits in English, e.g.
  `feat(drive): resume upload from server-reported offset`.
- When a task is done:
  1. Push the branch.
  2. Open a pull request against `main` through the GitHub REST API (`$GITHUB_TOKEN`).
     The PR body covers: what changed, why, how it was tested, and any follow-ups.
  3. Do not merge the PR. The owner reviews and merges.
- **No AI attribution anywhere. This is strict.**
  - No `Co-Authored-By:` trailers.
  - No "Generated with …" lines.
  - No mention of Claude, Anthropic, AI or any agent in commits, PR titles/bodies, code
    comments, docs or file headers.
  - Commits must be authored only by the repository owner's existing git identity.
  - Never change `git config user.name` / `user.email`. Never add bot or agent accounts as
    authors, committers or co-authors.
- Never force-push to `main`. Never rewrite published history unless the owner asks.
- Never commit secrets, keystores, `local.properties`, `google-services.json` with real
  values, or session files.

## 9. Scope guardrails

- **In scope (v1):**
  - shared storage scan
  - incremental backup to both destinations
  - optional encryption
  - index snapshots on both destinations
  - verified restore
  - retention
  - two-destination reconciliation
  - web panel (read-only browse + status)
- **Out of scope:**
  - root
  - other apps' private data
  - Bot API as a backup transport (20 MB download limit, §2.2)
  - content-defined chunking (maybe later)
  - any server that stores user data
- If a request conflicts with a locked decision or invariant, stop and ask the owner before
  proceeding.

## 10. Where to look in `info.md`

| Need | Section |
|---|---|
| Platform limits and their sources | §2 |
| TDLib methods, auth state machine, FLOOD_WAIT | §6.2 |
| Drive OAuth, v3 operations, resumable protocol | §7 |
| Room schema, caption manifest, appProperties, snapshot | §8 |
| StreamCrypto frame format, key management | §9 |
| Change detection, dedup, queue, restore, retention | §10 |
| Android permissions, battery/data defaults | §11 |
| Reference code patterns | §12 |
| Anti-patterns to avoid | §14 |
| Build checklist (implementation order) | §15 |
