# DuoBackup — Implementation Plan

This is the build plan for DuoBackup v1. Use it together with `CLAUDE.md` (rules, locked
decisions, invariants) and `info.md` (research reference, Indonesian). `CLAUDE.md` wins when the
two disagree. Raise any disagreement in the Decision Log (§10) rather than ignoring it.

---

## 0. How to use this plan

1. **Work in order.** Milestones depend on the ones before them. Do not start a milestone until
   the previous one is merged into `main`, unless the plan says the work can run in parallel.
2. **Use one branch and one PR per milestone**, named as given in the milestone header. A large
   milestone may be split into the sub-PRs it lists. Each step is one or more local commits.
3. **Tick the box** (`- [x]`) for each step in the same commit that completes it. The PR that
   finishes a milestone also updates the status table in §0.1.
4. **A milestone is done** when all of these hold:
   - Every step is ticked.
   - Every acceptance criterion is met.
   - CI is green on the PR.
   - New logic has tests (see §8).
   - No invariant from `CLAUDE.md` §4 is violated.
   - The PR body lists any manual device checks still to be done by the owner.
5. **Verify unknown APIs, don't guess.** TDLib class/field names, library versions and Google
   API details must be checked against the pinned artifact or official docs. If reality differs
   from this plan, follow reality, add an entry to the Decision Log, and mention it in the PR.
6. **No scope creep.** Anything not in a milestone goes to §9 "Deferred" with a one-line reason.
7. **Check CI from the phone.** Watch it through the GitHub REST API:
   - Runs: `GET /repos/Rosyidputrar/duobackup-apk/actions/runs?branch=<branch>`
   - Jobs: `GET /repos/Rosyidputrar/duobackup-apk/actions/runs/{id}/jobs`
   - Logs: `GET /repos/Rosyidputrar/duobackup-apk/actions/jobs/{job_id}/logs`

   Fix failures at the root cause.

### 0.1 Status

| Milestone | Title | Branch | Status |
|---|---|---|---|
| M0 | Repository, build and CI skeleton | `ci/skeleton` | todo |
| M1 | Core library: formats, math, codecs | `feat/core-formats` | todo |
| M2 | Android shell: catalog DB, settings, permissions | `feat/android-shell` | todo |
| M3 | Google Drive destination | `feat/drive-destination` | todo |
| M4 | Backup engine (scan, hash, queue, upload, snapshot) | `feat/backup-engine` | todo |
| M5 | Restore with layered verification | `feat/restore` | todo |
| M6 | Telegram destination (TDLib) | `feat/telegram-destination` | todo |
| M7 | Dual destination, reconciliation, connect-existing | `feat/dual-destination` | todo |
| M8 | Optional encryption | `feat/encryption` | todo |
| M9 | Retention, garbage collection, audit | `feat/retention` | todo |
| M10 | Web panel | `feat/web-panel` | todo |
| M11 | Hardening and v1.0 release | `chore/release-v1` | todo |

Drive (M3) comes before Telegram (M6) on purpose. Drive can be tested end to end in CI with a
mock HTTP server, so the engine (M4) and restore (M5) are proven on one destination before
TDLib's native and asynchronous complexity is added.

---

## 1. v1 scope (summary)

- **Backup:**
  - Back up the phone's **shared storage**, using All Files Access, to Google Drive and/or a
    private Telegram channel.
  - Backups are incremental. Content is deduplicated. Each destination can be switched on or
    off on its own.
- **Restore:**
  - Restore any file or folder, or everything, from either destination.
  - Every part and every file is checked with SHA-256 during restore.
  - Restore also works on a **new phone** that has no local database ("connect existing backup").
- **Encryption:** optional and **off by default**. When on, it covers file content, Telegram
  captions and index snapshots.
- **Retention:** time-based version retention, followed by garbage collection of unreferenced
  content.
- **Web panel:** a read-only browser of the latest index stored on Drive, showing files,
  status and quota.
- **Out of scope:**
  - root access
  - other apps' private data
  - Bot API transport
  - content-defined chunking
  - any server-side storage

---

## 2. Architecture

### 2.1 Gradle modules

```
:core       Kotlin/JVM library. No Android imports.
            Domain models, BackupTransport interface, engine (scan/hash/plan/upload/
            snapshot/restore/retention) written against interfaces, part math, caption codec,
            index snapshot codec, StreamCrypto, key file, HKDF, limits fallbacks.
:drive      Kotlin/JVM library. Depends on :core, OkHttp, kotlinx.serialization.
            Drive REST v3 client + resumable uploader + DriveTransport : BackupTransport.
            Auth is injected as `fun interface AccessTokenProvider` — no Android code here.
:telegram   Android library. Depends on :core and the TDLib AAR.
            TdGateway (thin wrapper around TDLib Client), auth state machine, pacer,
            TelegramTransport : BackupTransport, limit detection.
:app        Android application. Depends on all of the above.
            Room catalog (implements :core Catalog), DataStore + Tink settings/secrets,
            Google AuthorizationClient token provider, WorkManager workers, foreground
            notification, Compose UI, Hilt wiring.
```

Dependency rules:
- `:core` depends on nothing project-internal.
- `:drive` and `:telegram` depend only on `:core`, never on each other or on `:app`.
- Everything that decides *what* to do (the engine) lives in `:core` and is tested on the JVM
  with fakes. Android modules only provide implementations of `:core` interfaces.

### 2.2 Key `:core` interfaces (shape, not final signatures)

```kotlin
enum class DestinationId { DRIVE, TELEGRAM }

interface BackupTransport {
    val id: DestinationId
    suspend fun prepare(): PrepareResult                 // auth ok? container exists/created? limits
    suspend fun limits(): DestinationLimits               // detected at runtime (CLAUDE.md §5)
    suspend fun uploadPart(req: PartUpload, progress: (sentBytes: Long) -> Unit): RemoteLocation
    suspend fun downloadPart(loc: RemoteLocation, target: java.io.File, progress: (Long) -> Unit)
    suspend fun checkPart(loc: RemoteLocation): PartPresence     // PRESENT / MISSING / UNKNOWN
    suspend fun deletePart(loc: RemoteLocation)
    suspend fun putIndex(file: java.io.File, meta: IndexMeta): RemoteLocation
    suspend fun listIndexes(): List<IndexRef>                     // newest first
    suspend fun getIndex(ref: IndexRef, target: java.io.File)
    suspend fun deleteIndex(ref: IndexRef)
    suspend fun putKeyFile(bytes: ByteArray): RemoteLocation      // M8
    suspend fun getKeyFile(): ByteArray?                          // M8
}

sealed class TransportException(msg: String) : Exception(msg) {
    class RateLimited(val retryAfterMs: Long) : TransportException("rate limited")
    class QuotaExceeded : TransportException("quota exceeded")      // pause destination, notify
    class AuthRequired : TransportException("auth required")        // pause destination, notify
    class NotFound : TransportException("not found")
    class Transient(cause: Throwable?) : TransportException("transient")   // backoff + retry
    class Permanent(msg: String) : TransportException(msg)                 // fail this item
}

interface Catalog { /* repository over the Room tables in §3, suspend functions, Flow queries */ }
interface SourceFileSystem { /* walk(roots, excludes): Sequence<SourceEntry>; open(path) */ }
interface Clock { fun now(): Long }                                   // testable time
interface ScratchSpace { fun newFile(name: String): java.io.File; fun freeBytes(): Long }
```

The engine classes in `:core`:
- `Scanner`
- `Hasher`
- `Planner` (decides what to upload, based on dedup)
- `Uploader` (per destination, honouring pause states)
- `SnapshotWriter`
- `RestoreEngine`
- `RetentionEngine`
- `BackupSession` (orchestrates one run with a time budget)

### 2.3 Backup session pipeline (one run)

```
1. Prepare      For each enabled destination: prepare() + limits(). Disabled or failing
                destinations are marked paused for this run with a reason (AUTH, QUOTA, OFFLINE).
2. Scan         Walk include roots, apply excludes. For each entry compare (size, mtime) with
                the catalog. Unchanged → touch last_seen. Changed/new → mark "needs hash".
                Persist progress in batches (resumable).
3. Hash         For each "needs hash" file whose mtime is older than 45 s: one read pass that
                computes the whole-file SHA-256 AND each part's SHA-256 (part layout §4.2).
                Re-stat after hashing; if size/mtime moved, discard and retry next run.
4. Plan         Content with this SHA-256 already known? → link a new version to it (dedup).
                Else create content + content_parts rows. For every enabled destination,
                every part without a location becomes a pending upload.
5. Deletions    Catalog files under scanned roots not seen in this scan → version superseded
                (deleted_at set). Only when the scan of that root completed fully.
6. Upload       Drain pending uploads per destination (§2.5) until done or budget exhausted.
7. Commit       Write the index snapshot (§4.5) to every reachable destination, record it,
                rotate old snapshots. Done even when the budget ran out (partial progress).
8. Retention    At most once per 24 h: RetentionEngine (M9).
```

The run has a **time budget**. When the budget is nearly used up, the session stops *starting*
new parts, lets running parts finish, then commits. The next run resumes from the catalog.

### 2.4 Restore pipeline

```
1. Select       Files/folders from a chosen snapshot (default: latest).
2. Per file     Content complete on ≥1 destination? Prefer the user-chosen destination,
                fall back to the other.
3. Per part     downloadPart → (decrypt if encrypted) → SHA-256 of plaintext == part_sha256?
                Mismatch or failure → try other destination → still failing → file FAILED.
4. Assemble     Append verified plaintext into "<target>.duobackup-part" in app-controlled
                temp beside the target; on completion compare whole-file SHA-256.
5. Finalize     Conflict policy (§M5) → atomic rename → setLastModified (best effort) →
                MediaScannerConnection.scanFile for media types.
6. Report       Per file: RESTORED / RESTORED_FROM_FALLBACK / SKIPPED_IDENTICAL / FAILED(reason).
```

### 2.5 Concurrency model

- **Telegram:** one upload at a time. Every message-creating or message-editing call goes
  through the global pacer. A `FLOOD_WAIT` pauses the whole Telegram queue.
- **Drive:** up to 2 concurrent part uploads, each with its own resumable session.
- **Scratch sharing:** a part's scratch file is produced once (read source, verify part
  hash, encrypt if needed) and handed to every destination that needs it.
  - The scratch file is deleted when every destination has recorded its location, or has
    been paused for this run.
  - Scratch budget: at most 2 parts on disk at once.
  - Before starting, check that `ScratchSpace.freeBytes()` is at least part size plus a
    256 MiB margin. Otherwise stop the session with reason `LOW_STORAGE`.
- **Paused destinations:** if one destination is paused (QUOTA, AUTH, long FLOOD_WAIT), the
  other keeps going. The paused one gets the part in a later run by producing a fresh scratch
  file, verified against `part_sha256`.
- **Engine scope:** all engine work runs on `Dispatchers.IO` inside the worker's coroutine
  scope. Cancelling the worker cancels everything. The invariants make cancellation safe at
  any point.

---

## 3. Data model (Room, database `catalog.db`, version 1)

This refines `info.md` §8.1. Content is addressed by its plaintext SHA-256, which gives
deduplication and versioning without extra machinery. Columns are `snake_case`, and all
timestamps are epoch milliseconds.

**`files`** — one row per path ever seen

| column | type | notes |
|---|---|---|
| `id` | INTEGER PK autoincrement | |
| `volume` | TEXT NOT NULL | `primary` or storage volume UUID |
| `path` | TEXT NOT NULL | relative to volume root, `/` separated |
| `size` | INTEGER NOT NULL | last observed |
| `mtime` | INTEGER NOT NULL | last observed |
| `current_sha256` | TEXT NULL | NULL until hashed |
| `state` | TEXT NOT NULL | `NEEDS_HASH` `HASHED` `DELETED` `ERROR` |
| `error` | TEXT NULL | last error code (no paths) |
| `last_seen_scan` | INTEGER NOT NULL | scan id |

Unique index `(volume, path)`.

**`contents`** — one row per distinct plaintext content

| column | type | notes |
|---|---|---|
| `sha256` | TEXT PK | 64 lowercase hex |
| `oid` | TEXT NOT NULL UNIQUE | remote object id (§4.1) |
| `size` | INTEGER NOT NULL | plaintext bytes |
| `part_size` | INTEGER NOT NULL | plaintext bytes per part (last may be smaller) |
| `part_count` | INTEGER NOT NULL | 0 for empty files |
| `encrypted` | INTEGER NOT NULL | 0/1, decided when the content is first planned |
| `mime` | TEXT NULL | |
| `created_at` | INTEGER NOT NULL | |
| `gc_state` | TEXT NOT NULL | `LIVE` `DELETING` `GONE` |

**`content_parts`** — PK `(sha256, part_index)`

| column | type | notes |
|---|---|---|
| `sha256` | TEXT | FK contents |
| `part_index` | INTEGER | 0-based |
| `plain_offset` | INTEGER | |
| `plain_size` | INTEGER | |
| `part_sha256` | TEXT | SHA-256 of this part's plaintext |

**`part_locations`** — PK `(sha256, part_index, dest)`

| column | type | notes |
|---|---|---|
| `sha256`, `part_index` | | FK content_parts |
| `dest` | TEXT | `DRIVE` / `TELEGRAM` |
| `stored_size` | INTEGER | bytes as uploaded (after encryption) |
| `tg_chat_id` | INTEGER NULL | |
| `tg_message_id` | INTEGER NULL | final (server) id, never the temporary one |
| `tg_remote_file_id` | TEXT NULL | |
| `tg_remote_unique_id` | TEXT NULL | |
| `drive_file_id` | TEXT NULL | |
| `uploaded_at` | INTEGER | |
| `verified_at` | INTEGER NULL | last successful audit/restore verification |
| `missing` | INTEGER NOT NULL DEFAULT 0 | set when checkPart says MISSING |

**`file_versions`** — PK `(file_id, sha256, first_seen)`

| column | type | notes |
|---|---|---|
| `file_id` | INTEGER | FK files |
| `sha256` | TEXT | FK contents |
| `mtime` | INTEGER | mtime of this version |
| `first_seen` | INTEGER | |
| `superseded_at` | INTEGER NULL | NULL = current; set on change or deletion |

A version is live in the half-open interval `[first_seen, superseded_at)`. Snapshot and
retention logic rely on this interval (§M9).

**`drive_sessions`** — PK `(sha256, part_index)`

Persisted resumable sessions. Columns: `session_uri`, `total_bytes`, `created_at`,
`failures`, `scratch_path`.
- A session is reusable only if its scratch file still exists with the same size.
- A session is stale after 6 days. Stale sessions are deleted.

**`snapshots`**

Columns: `id` PK, `created_at`, `file_count`, `total_bytes`, `pending_parts`,
`drive_file_id` NULL, `tg_message_id` NULL, `format_version`.

**`runs`**

Session log. Columns: `id`, `started_at`, `ended_at`, `trigger` (`SCHEDULED`/`MANUAL`),
`result` (`OK`/`PARTIAL`/`FAILED`), `stop_reason`, `bytes_uploaded_drive`,
`bytes_uploaded_tg`, `files_hashed`, `errors`.

**`scans`**

Columns: `id`, `started_at`, `completed_at` NULL, `root`. Deletions are only applied for
roots whose scan completed.

Room rules:
- Export schemas to `app/app/schemas/` and commit them.
- Write explicit `Migration` objects only. No `fallbackToDestructiveMigration`.
- Write a migration test for every bump.

---

## 4. Remote formats, version 1

These are DuoBackup's own formats. They are inspired by `info.md` §8–§9 but are not
TeleDrive's. Every format carries a version. Readers must reject unknown major versions with
a clear error. Golden test vectors for every format live in `core/src/test/resources/golden/`.

### 4.1 Identifiers

- `sha256`: plaintext SHA-256 of the whole file, lowercase hex.
- `oid` (remote object id):
  - `sha256` when the content is **not** encrypted.
  - `HMAC-SHA256(idKey, sha256-bytes)` hex when it is encrypted (§4.6). This keeps the
    plaintext hash, and therefore content equality, off the server.
- Remote part name: `<oid[0..16)>.<part_index>.dbp`, for example `9f2c0a1b33d4e5f6.0.dbp`.
  It is used as the TDLib document file name (the scratch file is named this way) and as the
  Drive file name. **Never** use original file names remotely.

### 4.2 Part layout and sizing

- `TARGET_PART_SIZE = 512 MiB` (plaintext).
- Effective part size for a new content:
  - Start from `min(TARGET_PART_SIZE, maxPlainPartSize(limits))`.
  - `maxPlainPartSize` is the largest multiple of 1 MiB whose stored size, including the
    encryption overhead from §4.6, is at most the smallest detected per-file limit across
    enabled destinations, minus a 1 MiB margin.
  - Drive's limit is effectively unbounded (5 TB).
- A file of size `S` gets `ceil(S / partSize)` parts. Empty files get 0 parts and are
  restored by creating an empty file.
- The layout is fixed when the content is planned and stored in `contents.part_size`. Later
  limit changes never alter existing layouts.
- One read pass produces the whole-file hash and all part hashes (`Hasher`).

### 4.3 Telegram layout

- **Storage channel:**
  - A private channel created with `createNewSupergroupChat(isChannel = true)`, titled
    `DuoBackup`.
  - Its description holds the marker line `#duobackup-storage v1 <install-uuid>`.
  - Discovery: find chats titled `DuoBackup` (local chat list first, then search), then
    confirm the marker through the supergroup full info. If several match, ask the user.
- **Part message:**
  - `InputMessageDocument(InputFileLocal(scratch), caption)`, with
    `disableContentTypeDetection = true` and silent sending.
  - The caption is the part manifest:
    - Plain mode: `db1:` + compact JSON.
    - Encrypted mode: `dbe1:` + Base64url(no padding)(nonce(12) ‖ AES-256-GCM(metaKey,
      json, aad = "duobackup/caption/v1")).
  - JSON keys:

    | key | meaning |
    |---|---|
    | `v` | 1 |
    | `o` | oid |
    | `h` | content sha256 (plain mode only; inside the encrypted blob in encrypted mode) |
    | `s` | content size |
    | `ps0` | content part size |
    | `pc` | part count |
    | `pi` | part index |
    | `po` | plain offset |
    | `pl` | plain size |
    | `ph` | part sha256 |
    | `e` | 0/1 |
    | `p` | relative path of the first file that produced this content |
    | `vol` | volume |
    | `mt` | mtime |
    | `m` | mime (optional) |
    | `pt` | 1 if `p` was truncated to fit |

  - Budget: the encoded caption must fit `message_caption_length_max` (detected). Only `p`
    may be shortened, keeping its tail, and then `pt` is set to 1. If it still does not fit,
    throw. Never truncate silently.
- **Index message:**
  - Document `duobackup-index-<yyyyMMdd'T'HHmmss'Z'>.json.gz`, or `….json.gz.dbe` when
    encrypted.
  - Caption `#duobackup-index v1 <epochMs>`.
  - Discovery: search the channel's messages for `#duobackup-index` (document filter) and
    take the newest.
- **Key message (M8):**
  - Document `duobackup-key.json` with caption `#duobackup-key v1 <keyId>`.
  - A new key file is a new message. Old key messages are kept, never deleted.
- **Sending is asynchronous in TDLib.** `sendMessage` returns a *temporary* message. The
  final message id comes from `updateMessageSendSucceeded(message, oldMessageId)`, and
  failure comes from `updateMessageSendFailed`. Record `tg_message_id` only from the
  succeeded update. Keep the scratch file until then.

### 4.4 Google Drive layout

```
DuoBackup/                      folder, appProperties {db: "root", v: "1", install: <uuid>}
  data/                         folder, appProperties {db: "data"}
    <oid16>.<pi>.dbp            appProperties {db: "part", v: "1", oid: <oid 64 hex>,
                                               pi: "<n>", ph: <part sha256 or HMAC when enc>}
  index/                        folder, appProperties {db: "index-dir"}
    duobackup-index-<ts>.json.gz[.dbe]   appProperties {db: "index", v: "1", ts: "<epochMs>"}
  key/                          folder, appProperties {db: "key-dir"}
    duobackup-key.json          appProperties {db: "key", v: "1", kid: <keyId>}
```

- Folders are found by `appProperties has { key='db' and value='…' } and trashed = false`
  and are never looked up by name.
- Folder ids are cached in settings and re-resolved on 404.
- Every key+value pair stays under 120 UTF-8 bytes. Assert this in code; there is a unit test.
- In encrypted mode, `ph` holds `HMAC-SHA256(idKey, part_sha256)` so plaintext hashes do
  not leak.
- Upload verification: after the final chunk, the response must include `size` and
  `md5Checksum`. Both must equal the scratch file's size and MD5, which is computed while
  streaming. Any mismatch deletes the remote file and the upload is retried.

### 4.5 Index snapshot schema (`format: "duobackup-index"`, `v: 1`)

This is gzip-compressed JSON. In encrypted mode, the gzip bytes are wrapped in a DBE1
stream (§4.6) keyed by metaKey.

```json
{
  "format": "duobackup-index", "v": 1,
  "generatedAt": 1762758000000, "device": "<Build.MODEL>", "install": "<uuid>",
  "encryption": {"enabled": true, "keyId": "<kid>"},
  "session": {"runId": 42, "result": "PARTIAL", "pendingParts": 12,
              "bytesUploaded": {"DRIVE": 123, "TELEGRAM": 456}},
  "quota": {"DRIVE": {"limit": 429496729600, "usage": 123}},
  "contents": [
    {"h": "<sha256>", "o": "<oid>", "s": 734003200, "ps": 536870912, "e": 1,
     "parts": [{"i": 0, "ph": "<sha256>",
                "tg": {"c": -1001234567890, "m": 456, "f": "<remote_file_id>"},
                "gd": "<drive file id>"}]}
  ],
  "files": [
    {"vol": "primary", "p": "DCIM/Camera/a.jpg", "h": "<sha256>", "s": 12345,
     "mt": 1762700000000, "m": "image/jpeg"}
  ]
}
```

- `files` lists, for every non-deleted path, the newest version whose content is complete on
  at least one destination.
- A content's part entry carries only the destinations where it is located.
- Contents appear once even when several files share them (dedup).
- Rotation keeps the newest 10 snapshots that are also inside the retention window (§M9).
- Merge rule when reading both destinations: newest `generatedAt` wins as the base. Contents
  are unioned by `h`, so part locations from either side add up.

### 4.6 Encryption formats (used from M8; built and tested in M1)

**Keys**
- `masterKey`: 32 random bytes, generated once.
- Subkeys come from HKDF-SHA256 (RFC 5869) with `ikm = masterKey` and an empty salt:
  - `contentKey = HKDF(info = "duobackup/content/v1", 32)`
  - `metaKey = HKDF(info = "duobackup/meta/v1", 32)`
  - `idKey = HKDF(info = "duobackup/id/v1", 32)`
- `keyId` = first 16 hex chars of `SHA-256("duobackup/kid/v1" ‖ masterKey)`.
- Local storage: `masterKey` is wrapped by a Tink AEAD keyset whose master key is in the
  Android Keystore.

**Key file `duobackup-key.json`** (stored on every enabled destination)

```json
{"format": "duobackup-key", "v": 1, "keyId": "<kid>",
 "kdf": {"alg": "PBKDF2-HMAC-SHA256", "iter": 600000, "salt": "<b64 16 bytes>"},
 "wrap": {"alg": "AES-256-GCM", "nonce": "<b64 12>", "ct": "<b64 masterKey+tag>"},
 "createdAt": 1762758000000}
```

- The wrapping key is `PBKDF2(passphrase, salt, iter, 32 bytes)`. Use AAD
  `"duobackup/key/v1" ‖ keyId` when wrapping.
- PBKDF2 is chosen over scrypt so the web panel can unwrap with WebCrypto. 600k iterations
  follows current OWASP guidance.
- Changing the passphrase means writing a new key file. Data is never re-encrypted.

**DBE1 stream** (one per part file, and for encrypted index files)

```
Header : "DBE1" (4 bytes ASCII) ‖ version = 0x01 (1 byte) ‖ salt (16 random bytes)
partKey: HKDF-SHA256(ikm = contentKey (or metaKey for index), salt = salt,
                     info = "duobackup/stream/v1", 32 bytes)
Frame* : len (uint32 BE; bit 31 = final flag; bits 0..30 = ciphertext length without tag)
         ‖ ciphertext ‖ tag (16 bytes)
Frame plaintext: exactly 1 MiB, except the final frame (0..1 MiB). Exactly one final frame.
Nonce  : 8 zero bytes ‖ frameIndex (uint32 BE)       // unique because partKey is per-stream
AAD    : version (1 byte) ‖ frameIndex (uint32 BE) ‖ final (1 byte 0x00/0x01)
```

- Stored size of a stream for plaintext length `L`: `21 + L + 20 × max(1, ceil(L / 1 MiB))`.
  This is used for part sizing (§4.2).
- The reader must reject:
  - a bad magic or version,
  - a frame after the final frame,
  - end-of-file before the final frame,
  - any length field above 1 MiB,
  - any tag failure.

  On rejection, the reader stops without emitting the unauthenticated plaintext of that frame.
- Seek support: the frame for plaintext offset `x` starts at `21 + (x / 1 MiB) × (1 MiB + 20)`.

---

## 5. Owner setup (human-only tasks)

The agent cannot do these. Each milestone that needs one says so. The agent should write
precise click-by-click instructions in the PR body when a task becomes relevant.

| # | Task | Needed by |
|---|---|---|
| S1 | **Signing keystore.** Create one release keystore (Android Studio, or `keytool` in a Linux container). Add GitHub repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Never commit it. The repo is **public**. | M2 (CI-signed APKs, a stable SHA-1 for Google auth) |
| S2 | **Google Cloud project.** Create a project, enable the Google Drive API, and configure the OAuth consent screen (External). Add the owner as a test user, then set publishing status to **In production** (`drive.file` is a non-sensitive scope, so no verification review is expected). Testing mode makes grants expire after 7 days. | M3 |
| S3 | **Android OAuth client.** Type Android, package `io.github.rosyidputrar.duobackup`, SHA-1 of the S1 keystore (the agent prints the command and the CI job prints the SHA-1). No client id is embedded in the APK. | M3 |
| S4 | **Telegram API credentials.** Create an app at my.telegram.org to get `api_id` and `api_hash`. They are typed into the app at runtime and never put in the repo or CI. | M6 |
| S5 | **Web OAuth client.** Type Web application, **in the same Cloud project as S3** (required for `drive.file` visibility of files the APK created). Authorised JS origin: `https://rosyidputrar.github.io`. Add the client id (not a secret) as GitHub Actions variable `VITE_GOOGLE_CLIENT_ID`. | M10 |
| S6 | **GitHub Pages.** Settings → Pages → Source: GitHub Actions. | M10 |
| S7 | **Device checks.** Run the manual checklist in each PR on a real phone and comment the results. | M3 onward |

---

## 6. Milestones

General rules for every milestone:

- **No local SDK or JDK on the owner's phone.** CI is the compiler. Push early and often to
  the milestone branch so CI catches errors. A draft PR is fine and can be opened from the
  first push.
- **Versions:** use the latest *stable* release of each library at implementation time.
  - Check it against Maven Central (`repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml`),
    Google Maven (`dl.google.com/dl/android/maven2/...`) or the Gradle Plugin Portal.
  - Never use alpha, beta or RC versions.
  - Record the chosen versions in `libs.versions.toml` only.
  - Make sure Kotlin, KSP, AGP, Compose compiler and Hilt versions are mutually compatible
    (check each project's compatibility table).
- **Tests** are listed per step. Every listed test is mandatory.

---

### M0 — Repository, build and CI skeleton

**Branch:** `ci/skeleton`

**Goal:** an empty but correctly structured multi-module Android project that CI builds,
lints and tests on every PR, and that produces a debug APK artifact.

**Steps**

- [ ] **M0.1 Repo hygiene.**
  - Add `.gitignore`. It must cover:
    - Android/Gradle (`.gradle/`, `build/`, `local.properties`, `*.keystore`, `*.jks`,
      `captures/`, `.idea/` except shared run configs)
    - Node (`node_modules/`, `dist/`)
    - OS junk
    - `*.session`
    - `google-services.json`
  - Add `.editorconfig`: 4 spaces for Kotlin/KTS, 2 for TS/JSON/YAML/MD, final newline, LF.
    Add `ktlint_code_style = android_studio`.
  - Add `.gitattributes`: `* text=auto eol=lf`, and `*.jar binary`.
- [ ] **M0.2 Gradle project in `app/`.**
  - Create these files:
    - `app/settings.gradle.kts`: `rootProject.name = "duobackup"`, include `:app`, `:core`,
      `:drive`, `:telegram`. Add `pluginManagement` and `dependencyResolutionManagement`
      with repositories `google()` and `mavenCentral()`. Add `maven("https://jitpack.io")`
      **restricted with `content { includeGroup("com.github.tdlibx") }`**.
    - `app/build.gradle.kts`: plugins declared with `apply false`.
    - `app/gradle.properties`: `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`,
      `android.useAndroidX=true`, `kotlin.code.style=official`,
      `org.gradle.configuration-cache=true`, `org.gradle.caching=true`.
    - `app/gradle/libs.versions.toml` covering: AGP, Kotlin, KSP, Compose BOM, Activity
      Compose, Lifecycle, Navigation Compose, Hilt (+ hilt-work, hilt-navigation-compose),
      Room, WorkManager, DataStore, Tink, OkHttp (+ mockwebserver),
      kotlinx-serialization-json, kotlinx-coroutines (+ test), Google Identity /
      play-services-auth (for `AuthorizationClient`), JUnit 5 (core/drive) and JUnit 4 +
      Robolectric (android modules), Turbine, Truth or kotlin-test, ktlint plugin, Kover.
      The TDLib entry is added in M6.
  - **Gradle wrapper without a local Gradle:**
    1. Write `gradle/wrapper/gradle-wrapper.properties` pointing at the latest stable
       Gradle `-bin.zip`, with `distributionSha256Sum` taken from
       `https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`.
    2. Fetch `gradle-wrapper.jar` from the Gradle GitHub repository at the matching tag
       (`https://raw.githubusercontent.com/gradle/gradle/v<v>/gradle/wrapper/gradle-wrapper.jar`).
       Verify it against `https://services.gradle.org/distributions/gradle-<v>-wrapper.jar.sha256`
       before committing.
    3. Copy `gradlew` and `gradlew.bat` from the same tag, and keep `gradlew` executable
       (`git update-index --chmod=+x`).
    4. CI runs `gradle/actions/wrapper-validation` on every build.
- [ ] **M0.3 Convention for module config.**
  - Keep it simple: no `build-logic` included build in v1. Each module's
    `build.gradle.kts` sets `jvmToolchain(17)` and its plugins directly.
  - Android modules: `compileSdk`/`targetSdk` = latest stable API level supported by the
    pinned AGP, and `minSdk = 26`.
- [ ] **M0.4 Module stubs**, each with one trivial passing test so the test tasks are wired:
  - `:core`: `kotlin("jvm")` + `kotlinx-serialization`, JUnit 5, Kover. Contains
    `DestinationId` only.
  - `:drive`: `kotlin("jvm")`, depends on `:core`.
  - `:telegram`: `com.android.library`, namespace `io.github.rosyidputrar.duobackup.telegram`,
    depends on `:core`.
  - `:app`: `com.android.application`, Compose, Hilt, KSP.
    - `applicationId = "io.github.rosyidputrar.duobackup"`, `versionCode = 1`,
      `versionName = "0.1.0"`.
    - `debug` build type uses `applicationIdSuffix = ".debug"`.
    - `@HiltAndroidApp class DuoBackupApp`, plus `MainActivity` showing a Material 3
      scaffold with the text "DuoBackup".
- [ ] **M0.5 Static checks.**
  - Apply the ktlint Gradle plugin to all modules.
  - Android lint: `abortOnError = true`, `checkDependencies = true`, no baseline file.
  - Kover on `:core` and `:drive` with a verify rule of **≥ 80 % line coverage** (enforced
    from M1; set the rule now with `minBound(0)` and raise it in M1.6).
- [ ] **M0.6 `.github/workflows/android.yml`.**
  - Triggers: `pull_request` and `push` to `main`, with paths `app/**` and
    `.github/workflows/android.yml`; also `workflow_dispatch`.
  - `concurrency: android-${{ github.ref }}` with `cancel-in-progress: true`.
  - Permissions: `contents: read`.
  - Job `build` on `ubuntu-latest`, `defaults.run.working-directory: app`, steps:
    1. checkout
    2. `actions/setup-java` (temurin, 17)
    3. `gradle/actions/setup-gradle` (with caching)
    4. `gradle/actions/wrapper-validation`
    5. `./gradlew ktlintCheck`
    6. `./gradlew :core:test :drive:test :core:koverVerify :drive:koverVerify`
    7. `./gradlew testDebugUnitTest`
    8. `./gradlew lintDebug`
    9. `./gradlew assembleDebug`
    10. upload `app/app/build/outputs/apk/debug/*.apk` as artifact `duobackup-debug-apk`
        (retention 14 days)
    11. on failure: upload `**/build/reports/**` as artifact `reports`.
  - Pin actions to major versions (`@v4`, etc.).
- [ ] **M0.7 Dependabot.** Add `.github/dependabot.yml`: ecosystems `gradle` (directory
  `/app`) and `github-actions` (directory `/`), weekly, grouped minor/patch updates.
- [ ] **M0.8 README.md.** One paragraph on what the project is, the repo layout, how to get
  the debug APK from the Actions artifacts, and a link to `plan.md`.

**Acceptance**

- The PR's `android` workflow is green.
- The artifact `duobackup-debug-apk` exists and installs on a phone (S7).
- The app opens and shows "DuoBackup".
- The `gradlew` file mode is 100755 in git (`git ls-files -s app/gradlew`).

---

### M1 — Core library: formats, math, codecs

**Branch:** `feat/core-formats`. Pure JVM, so it is fully testable in CI.

**Goal:** every format from §4, plus the part math, hashing, backoff and path utilities,
implemented and locked by golden tests. M1 has no I/O except streams and files passed in.

Package root: `io.github.rosyidputrar.duobackup.core`, sub-packages `model`, `transport`,
`format`, `crypto`, `engine` (filled in M4), `util`.

**Steps**

- [ ] **M1.1 Models and interfaces** (§2.2):
  - `DestinationId`, `BackupTransport`, `TransportException`, `RemoteLocation`
    (a sealed class with `Telegram(chatId, messageId, remoteFileId, remoteUniqueId)` and
    `Drive(fileId)`), `PartUpload(file, oid, partIndex, partSha256OrMac, storedSize, caption
    material)`, `PartPresence`, `IndexRef`, `IndexMeta`, `PrepareResult`,
    `DestinationLimits(maxFileBytes, captionMax, quotaFreeBytes?, isPremium?)`.
  - `Clock`, `ScratchSpace`, `SecureRandomSource` (injectable so tests are deterministic).
- [ ] **M1.2 `Limits.kt` and `PartSizing`.**
  - Fallback constants, used only when detection fails:
    - `TG_FALLBACK_MAX_PARTS = 4000`
    - `TG_UPLOAD_PART_BYTES = 512 * 1024`, which gives `2_097_152_000` bytes
    - `TG_FALLBACK_CAPTION_MAX = 1024`
    - `DRIVE_MAX_FILE_BYTES = 5 TB`
    - `TARGET_PART_SIZE = 512 MiB`
    - `FRAME = 1 MiB`
  - Functions:
    - `PartSizing.storedSize(plain: Long, encrypted: Boolean)` (§4.6 formula)
    - `PartSizing.maxPlainPartSize(limitBytes: Long, encrypted: Boolean)` (largest multiple
      of 1 MiB fitting `limitBytes - 1 MiB`)
    - `PartSizing.choose(limits: List<DestinationLimits>, encrypted)`
    - `PartSizing.layout(size, partSize): List<PartSpan(index, offset, length)>`
  - Tests:
    - size 0 gives 0 parts
    - size 1
    - exactly one part
    - one part + 1 byte
    - a 20 GiB file with 512 MiB parts gives 40 parts
    - stored size with and without encryption at 0, 1, 1 MiB, 1 MiB + 1, 512 MiB
    - `choose` with a free-tier limit gives 512 MiB
    - `choose` with a hypothetical 100 MiB limit gives 99 MiB plain unencrypted, and the
      correct encrypted value
    - the stored size of the chosen part never exceeds the limit (property test over random
      limits)
- [ ] **M1.3 `Hasher`.**
  - `hash(input: InputStream, partSize: Long): HashResult(sha256, size, partHashes: List<String>)`.
  - Single pass with a 1 MiB buffer and two `MessageDigest` instances (whole + current part).
  - Supports cancellation by checking `coroutineContext.ensureActive()` per buffer when
    called from the suspend variant.
  - Tests:
    - empty input
    - known vectors (`"abc"` gives the RFC 6234 value)
    - part hashes equal the hashes of the sliced ranges
    - sizes not aligned to the buffer
    - the concatenated part ranges cover the input exactly
- [ ] **M1.4 Crypto** (`crypto` package, JCA only, no Tink in `:core`):
  - `Hkdf.sha256(ikm, salt, info, len)` with RFC 5869 test cases 1–3 as tests.
  - `KeyMaterial(masterKey)` exposing `contentKey`, `metaKey`, `idKey`, `keyId`.
  - `Oid.of(sha256Hex, idKey?)`, with tests for both modes.
  - `Dbe1.newWriter(out: OutputStream, key, random): OutputStream`, which buffers to 1 MiB
    frames and writes the final frame on `close()`.
  - `Dbe1.newReader(input: InputStream, key): InputStream`, which verifies each frame
    before releasing its bytes.
  - Tests:
    - round-trip at 0, 1, 1 MiB − 1, 1 MiB, 1 MiB + 1, and 5 MiB + 7 bytes
    - stored length equals `PartSizing.storedSize`
    - tamper detection, each case giving a typed `CryptoException`:
      - flip one byte in a header, ciphertext or tag
      - swap two frames
      - truncate the last frame
      - drop the final frame
      - append an extra frame after the final one
      - final-flag cleared
      - length field > 1 MiB
    - wrong key gives an auth failure
    - `seekOffset(x)` math
  - `KeyFile.create(masterKey, passphrase, random, iterations = 600_000)` /
    `KeyFile.unlock(json, passphrase)`.
    - Tests: round-trip, wrong passphrase gives `WrongPassphrase`, tampered `ct` gives an
      auth failure, and the JSON schema matches the golden file.
    - Use `iterations = 1000` in tests through a parameter; the default stays 600k.
  - `CaptionCipher` (AES-256-GCM, 12-byte nonce, fixed AAD), with round-trip and tamper tests.
- [ ] **M1.5 Format codecs** (`format` package, kotlinx.serialization, explicit
  `@SerialName` for every short key):
  - `CaptionCodec.encode(meta: PartCaption, budget: Int, cipher: CaptionCipher?)` and
    `decode(text)`. Rules from §4.3: the only allowed shortening is the tail-preserving
    shortening of `p` with `pt = 1`; anything else throws `CaptionTooLong`.
    - Tests:
      - plain and encrypted round-trip
      - budget truncation keeps the file name
      - budgets of 1024 and 2048
      - a non-ASCII path (budget measured in UTF-16 code units, as Telegram counts)
      - a non-`db1:`/`dbe1:` caption gives `null` (foreign message), not an exception
      - unknown `v` gives `UnsupportedVersion`
  - `IndexSnapshotCodec.write(snapshot, out, cipher?)` / `read(input, cipher?)` with gzip.
    - Unknown JSON fields are ignored (`ignoreUnknownKeys = true`); an unknown `v` major
      throws.
    - `IndexSnapshot.merge(a, b)` (§4.5 rule).
    - Tests: round-trip, merge unions part locations, newest base wins, and the encrypted
      round-trip.
  - `DriveProps.part(...)`, `DriveProps.index(...)` and friends. Each returns
    `Map<String, String>` and asserts that every key+value pair is ≤ 120 UTF-8 bytes. Tests
    cover the worst case: a 64-hex oid, a large part index, and a 64-hex ph.
- [ ] **M1.6 Golden vectors.**
  - `core/src/test/resources/golden/` holds:
    - `dbe1-*.bin` (fixed key and salt) with their `.plain` inputs
    - `keyfile-v1.json` (fixed passphrase "correct horse battery staple", fixed salt and
      nonce, iterations 1000)
    - `caption-plain.txt`
    - `caption-enc.txt`
    - `index-v1.json.gz` and `index-v1-enc.json.gz.dbe`
  - Tests decode each one and re-encode it with the same injected randomness, then assert
    byte equality.
  - A `GoldenGenerator` test (`@Disabled` by default) regenerates them, and its use is
    documented in a comment. The same files are consumed by the web tests in M10, which keeps
    the two implementations interoperable.
  - Raise Kover's bound to 80 %.
- [ ] **M1.7 `Backoff` and retry policy.**
  - `Backoff(base = 2 s, factor = 2.0, cap = 1 h, jitter = FULL)` with
    `nextDelay(attempt, random)`.
  - `RetryPolicy.classify(TransportException)` gives RETRY_AFTER(ms), BACKOFF, PAUSE_DEST or
    FAIL_ITEM.
  - Tests: monotone caps, jitter within bounds, and classification for each exception.
- [ ] **M1.8 Path utilities.**
  - `RelPath.normalize` (rejects `..`, absolute paths and empty segments; normalises
    separators).
  - `ExcludeMatcher(globs)` supports `**`, `*` and `?`, matching on relative paths.
    Tests cover the default exclude set (§M2.5).
  - `MimeGuess.fromName` (extension table, small).

**Acceptance**

- CI is green, and Kover on `:core` is at least 80 %.
- Every format in §4 has a golden test.
- No Android imports appear in `:core` (CI step: `! grep -r "import android" core/src`).

<!-- plan:continue -->
