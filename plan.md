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
| M0 | Repository, build and CI skeleton | `ci/skeleton` | done (device check S7 pending) |
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
| `hold` | INTEGER NOT NULL DEFAULT 0 | 1 = imported from a remote index and not yet confirmed on this device; never marked deleted by scans, never expired by retention (§M7.4) |

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
  - Caption `#duobackup-index v1 <epochMs>`, followed by ` kid=<keyId>` when encrypted.
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
    duobackup-index-<ts>.json.gz[.dbe]   appProperties {db: "index", v: "1", ts: "<epochMs>",
                                                    kid: <keyId, only when encrypted>}
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

- [x] **M0.1 Repo hygiene.**
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
- [x] **M0.2 Gradle project in `app/`.**
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
- [x] **M0.3 Convention for module config.**
  - Keep it simple: no `build-logic` included build in v1. Each module's
    `build.gradle.kts` sets `jvmToolchain(17)` and its plugins directly.
  - Android modules: `compileSdk`/`targetSdk` = latest stable API level supported by the
    pinned AGP, and `minSdk = 26`.
- [x] **M0.4 Module stubs**, each with one trivial passing test so the test tasks are wired:
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
- [x] **M0.5 Static checks.**
  - Apply the ktlint Gradle plugin to all modules.
  - Android lint: `abortOnError = true`, `checkDependencies = true`, no baseline file.
  - Kover on `:core` and `:drive` with a verify rule of **≥ 80 % line coverage** (enforced
    from M1; set the rule now with `minBound(0)` and raise it in M1.6).
- [x] **M0.6 `.github/workflows/android.yml`.**
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
- [x] **M0.7 Dependabot.** Add `.github/dependabot.yml`: ecosystems `gradle` (directory
  `/app`) and `github-actions` (directory `/`), weekly, grouped minor/patch updates.
- [x] **M0.8 README.md.** One paragraph on what the project is, the repo layout, how to get
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

---

### M2 — Android shell: catalog DB, settings, permissions

**Branch:** `feat/android-shell`. **Needs:** S1 (signing secrets) for step M2.8.

**Goal:** the app can obtain All Files Access, persists the catalog and settings, and has the
screen skeleton plus a worker scaffold that later milestones fill in.

**Steps**

- [ ] **M2.1 Room catalog** in `:app` (`data.db` package).
  - Write entities and DAOs exactly per §3, with `@Database(version = 1, exportSchema = true)`
    and the KSP arg `room.schemaLocation`.
  - `RoomCatalog : Catalog` is the only class the engine sees.
  - Batch inserts happen inside transactions.
  - DAO queries needed later:
    - files by state
    - pending parts per destination
    - content completeness per destination
    - versions live at time T
    - unreferenced contents
  - Tests: Robolectric DAO tests on an in-memory DB covering every query, plus a test that the
    exported schema JSON matches the committed one.
- [ ] **M2.2 Settings** (DataStore Preferences, `SettingsRepository`). Keys and defaults:

  | key | default |
  |---|---|
  | `drive_enabled` | false |
  | `telegram_enabled` | false |
  | `wifi_only` | true |
  | `charging_only` | false |
  | `min_battery_pct` | 20 |
  | `schedule` | `DAILY` (`OFF`/`DAILY`/`EVERY_6H`) |
  | `include_roots` | `[""]` (whole primary volume) |
  | `excludes` | default list in M2.5 |
  | `encryption_enabled` | false |
  | `retention_days` | 30 |
  | `snapshot_keep` | 10 |
  | `install_uuid` | generated on first launch |
  | `drive_folder_ids` | JSON cache |
  | `tg_storage_chat_id` | none |

  Settings are exposed as a `Flow<Settings>`.
- [ ] **M2.3 Secrets** (`SecretStore`). Tink `AeadConfig.register()` and
  `AndroidKeysetManager` with the master key URI `android-keystore://duobackup_master`, keyset
  stored in its own prefs file. It encrypts:
  - Telegram `api_id`/`api_hash`
  - the TDLib database encryption key (32 random bytes)
  - the wrapped `masterKey` (M8)

  The API is `put(name, bytes)`, `get(name)` and `delete(name)`. The value AAD = name.
  Tests (Robolectric where the Keystore is available, otherwise a fake): round-trip,
  tampering is detected, and deleting a key removes it.
- [ ] **M2.4 All Files Access flow.**
  - Add `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
    tools:ignore="ScopedStorage"/>`.
  - Check: `Environment.isExternalStorageManager()`.
  - Request: `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with `package:` URI,
    falling back to `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`.
  - Re-check in `onResume`.
  - **Honesty note in the UI:** on Android 11+ even All Files Access cannot read
    `Android/data` and `Android/obb`, so they are always excluded and the UI says so.
- [ ] **M2.5 `AndroidSourceFileSystem : SourceFileSystem`.**
  - Walk `Environment.getExternalStorageDirectory()` (volume `primary`) with
    `java.nio.file.Files.walkFileTree`. Do not follow symlinks.
  - Skip unreadable entries with a counted warning, not a failure.
  - Default excludes:
    - `Android/data/**`
    - `Android/obb/**`
    - `**/.thumbnails/**`
    - `**/.cache/**`
    - `**/cache/**`
    - `**/.trash*/**`
    - `**/.Trash*/**`
    - `**/*.tmp`
    - `**/*.part`
    - `**/*.duobackup-part`
    - `**/.nomedia`
    - `DuoBackup Restore/**` (the restore output folder from M5)
  - Removable volumes come from `StorageManager.storageVolumes` and are listed in settings
    but disabled by default (Deferred: §9).
- [ ] **M2.6 UI skeleton** (Compose, Navigation), with screen state as `StateFlow` in Hilt
  ViewModels.
  - Screens:
    - **Onboarding**: explains, requests All Files Access, requests notifications.
    - **Home**: last run, next run, pending bytes per destination, quota, a "Back up now"
      button, the current run's progress.
    - **Destinations**: Drive card, Telegram card (placeholders now).
    - **Browse**: file tree from the catalog; restore actions come in M5.
    - **Settings**: everything in M2.2, plus an exclude editor and an include-roots picker
      (folder list from the walker, not SAF).
    - **Activity log**: the `runs` table.
  - All strings go in `res/values/strings.xml`. Plurals use `plurals`.
- [ ] **M2.7 Worker scaffold.**
  - `BackupWorker : CoroutineWorker` (`@HiltWorker`) with unique work names
    `duobackup.backup.periodic` and `duobackup.backup.now` (`ExistingWorkPolicy.KEEP`).
  - Constraints come from settings: network `UNMETERED` or `CONNECTED`, `requiresCharging`,
    `requiresBatteryNotLow`.
  - Calls `setForeground(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))`
    before any long work.
  - The manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
    `POST_NOTIFICATIONS` and `INTERNET`.
  - The manifest also merges the service declaration for
    `androidx.work.impl.foreground.SystemForegroundService` with
    `android:foregroundServiceType="dataSync"` (`tools:node="merge"`).
  - **Time budget:** the default run budget is **45 min**.
    - Android 15+ caps `dataSync` foreground services at about 6 h per 24 h. Handle
      `onStopped`/the timeout by finishing the current part and committing.
    - When the queue is non-empty at the end, enqueue a follow-up one-time run with a 15-min
      initial delay (counts toward the cap; the engine just resumes).
  - The notification channel is `backup`, low importance. It shows progress
    (bytes/total and the current file name only at debug log level) and has a "Stop" action
    (`WorkManager.cancelUniqueWork`).
  - For now the worker body calls a no-op `BackupSession` and writes a `runs` row.
  - Tests: a WorkManager test driver (`work-testing`) checks constraints mapping and unique
    work behaviour.
- [ ] **M2.8 Release signing in CI.**
  - The `release` build type reads signing config from env vars (`SIGNING_*`). If they are
    absent, the `assembleRelease` task is skipped and the build does not fail.
  - Add a CI job `release-apk`:
    - Runs only on `push` to `main` and tags `v*`.
    - Decodes `SIGNING_KEYSTORE_BASE64` to a temp file.
    - Runs `./gradlew assembleRelease`.
    - Prints the signing certificate SHA-1 (`keytool -list -v`; needed for S3).
    - Uploads `duobackup-release-apk`.
  - Add R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) with keep rules for
    kotlinx.serialization and TDLib (added in M6).

**Acceptance**

- CI is green, including the new Room and worker tests.
- On a device (S7):
  - Onboarding grants All Files Access.
  - The Browse screen is empty but works.
  - "Back up now" shows the foreground notification and creates an activity-log row.
  - Settings persist across restarts.

---

### M3 — Google Drive destination

**Branch:** `feat/drive-destination`. **Needs:** S2 and S3 (Google Cloud project + Android
OAuth client).

**Goal:** a complete `DriveTransport` in `:drive`, fully tested against MockWebServer, plus
the connect/disconnect flow in `:app`.

**Steps**

- [ ] **M3.1 `DriveApi`** (OkHttp, kotlinx.serialization). A thin REST client, base
  `https://www.googleapis.com` (injectable for tests). Operations:
  - `about(fields = "user(emailAddress),storageQuota")`, which gives `limit` (may be absent =
    unlimited), `usage` and `usageInDrive`.
  - `listFiles(q, fields, pageToken, pageSize = 1000)` with paging helper.
  - `createFolder(name, parentId?, appProperties)`.
  - `getFile(id, fields)`.
  - `updateFile(id, metadata)`.
  - `deleteFile(id)`.
  - `download(id, target, rangeStart?)` (`alt=media`, `Range` header for resume).

  Every request adds `Authorization: Bearer <token>` from `AccessTokenProvider`. On **401**,
  invalidate the token once, fetch a new one and retry once. A second 401 throws
  `AuthRequired`.
- [ ] **M3.2 Error mapping** (a single function, unit-tested with recorded JSON error bodies):

  | Response | Result |
  |---|---|
  | 403 with reason `userRateLimitExceeded` or `rateLimitExceeded` | `RateLimited(backoff)` |
  | 429 | `RateLimited` (honour `Retry-After` if present) |
  | 403 with reason `storageQuotaExceeded` or `quotaExceeded` | `QuotaExceeded` |
  | 403 with reason `insufficientPermissions` / `appNotAuthorizedToFile` | `AuthRequired` |
  | 404 | `NotFound` |
  | 5xx, `IOException`, timeout | `Transient` |
  | other 4xx | `Permanent` |

  **The access token must never appear in exception messages or logs.** Add a test that
  asserts this by scanning exception strings.
- [ ] **M3.3 `ResumableUploader`** (§7.3 of `info.md`, §4.4 here).
  - `open(metadata, totalBytes)` sends `POST /upload/drive/v3/files?uploadType=resumable&fields=id,size,md5Checksum`
    with headers `X-Upload-Content-Type: application/octet-stream` and
    `X-Upload-Content-Length`, and takes the session URI from `Location`. Persist it
    immediately in `drive_sessions` (through a callback interface; `:drive` does not know
    Room).
  - `queryOffset(uri, total)` sends `PUT` with `Content-Range: bytes */total`.
    - 308 with `Range: bytes=0-n` gives n+1.
    - 308 without `Range` gives 0.
    - 200/201 means already complete; parse the file.
    - 404/410 gives `SessionGone`.
  - `putChunk(uri, file, offset, length, total)` streams from the file with a
    `RequestBody` that does not load into memory.
    - Chunk length is a multiple of 262,144 bytes except the final one.
    - **Adaptive size:** start at 1 MiB, target 10 s per chunk, then clamp to
      `[256 KiB, 64 MiB]`, rounded down to 256 KiB.
    - The MD5 is updated as bytes are confirmed. On resume after a crash, it is recomputed
      from the scratch file up to the confirmed offset.
  - Driver loop: offset = queryOffset; loop putChunk.
    - After each chunk, take the new offset from the 308 `Range` header (the server may
      accept less than was sent).
    - Reset `failures` to 0 after every chunk that advances.
    - On `Transient`/`RateLimited`, wait with `Backoff` and re-query the offset.
    - Stop after 100 consecutive failures or when the session is stale (> 6 days).
    - On `SessionGone` or other 4xx, open a new session from 0.
  - Completion: the response gives `id`, `size` and `md5Checksum`. Verify them against the
    scratch file; on mismatch, delete the remote file and throw `Transient`.
  - Tests with MockWebServer and scripted responses:
    - happy path, multiple chunks
    - the server accepts a shorter range than was sent
    - crash and resume using a persisted URI
    - 404 during the query restarts from zero
    - 503s trigger backoff (virtual time via `runTest`)
    - 403 rate limit, then success
    - 403 storage quota gives `QuotaExceeded`
    - MD5 mismatch deletes the remote file and retries
    - every non-final chunk length is a multiple of 256 KiB (assert on recorded requests)
- [ ] **M3.4 `DriveLayout`.** It finds or creates `DuoBackup/`, `data/`, `index/` and `key/`
  via `appProperties` queries (§4.4).
  - Query by property, never by name. The root query also matches `install` *or* any
    DuoBackup root. If more than one root is found, pick the one with the newest index and
    report the others in `PrepareResult.warnings`.
  - Folder ids are cached in settings and re-resolved once on 404.
- [ ] **M3.5 `DriveTransport : BackupTransport`.**
  - `prepare` checks the token, resolves the layout and reads `about`.
  - `limits` returns `maxFileBytes = 5 TB`, `quotaFreeBytes = limit - usage` (null if
    unlimited), and `captionMax = n/a`.
  - `uploadPart` uses `ResumableUploader` with `DriveProps.part`, parent `data/`, name per
    §4.1. It returns `RemoteLocation.Drive(id)`.
  - `downloadPart` writes to `target`, resumes with `Range` when `target` exists, and checks
    that the final size equals `stored_size`.
  - `checkPart` uses `getFile(id, "id,size,trashed")`. Missing or trashed gives `MISSING`.
  - `putIndex`/`listIndexes`/`getIndex`/`deleteIndex` work on `index/` with
    `DriveProps.index`.
  - Key file operations are stubbed to `UnsupportedOperationException` until M8.
  - Tests: transport-level tests over MockWebServer, including the layout bootstrap from an
    empty Drive and from an existing one.
- [ ] **M3.6 Google authorization in `:app`** (`GoogleAuthManager : AccessTokenProvider`).
  - Use `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest` with
    requested scope `https://www.googleapis.com/auth/drive.file`.
  - If `result.hasResolution()`, launch `result.pendingIntent` through
    `ActivityResultContracts.StartIntentSenderForResult` (UI only), then call
    `getAuthorizationResultFromIntent`.
  - Otherwise use `result.accessToken`.
  - In the worker, call `authorize` again with the application context.
    - A silent success gives a fresh token.
    - If a resolution is needed (the user revoked access), throw `AuthRequired`. The
      destination is then paused and a notification "Reconnect Google Drive" is posted,
      deep-linking to the Destinations screen.
  - Cache the token in memory with its expiry (assume 55 min if unknown). Never persist it.
  - Disconnect revokes access (use the client's revoke API if available in the pinned
    version, otherwise the OAuth revoke endpoint with the token) and clears the cached
    folder ids. Remote data is not touched.
  - Verify the exact API surface against the pinned `play-services-auth` version and record
    any difference in the Decision Log.
- [ ] **M3.7 Destinations UI for Drive.**
  - The card shows the connected account email, quota (used / limit, "Unlimited" when no
    limit), the folder status, and buttons for Connect / Disconnect / "Test upload".
  - "Test upload" sends a 1 MiB random part to `data/` with `appProperties.db = "probe"`,
    reads it back, compares SHA-256 and deletes it. It shows the result and throughput.

**Acceptance**

- CI is green, and Kover on `:drive` is at least 80 %.
- On a device (S7):
  - Connect works.
  - The quota is shown.
  - Test upload passes.
  - Disconnect and reconnect works.
  - Turning on airplane mode during the test upload, then off again, resumes the upload
    rather than restarting it (check the log).

---

### M4 — Backup engine

**Branch:** `feat/backup-engine`. It can be split into `feat/engine-scan-hash` and
`feat/engine-upload`.

**Goal:** `BackupSession` in `:core` implementing the §2.3 pipeline for any set of
`BackupTransport`s, wired into `BackupWorker`, with Drive as the first real destination.

**Steps**

- [ ] **M4.1 Test fakes in `:core` test fixtures** (`java-test-fixtures` plugin, so `:app`
  tests can reuse them):
  - `InMemoryCatalog`
  - `FakeFileSystem`: a tree of entries with size, mtime and content generator; supports
    mutating between scans.
  - `FakeTransport`: in-memory storage with scriptable failures (rate limit after N parts,
    quota exceeded at X bytes, transient every k-th call, missing parts, corrupted download
    bytes).
  - `FakeClock`
  - `TempScratchSpace`
- [ ] **M4.2 `Scanner`.**
  - Walks the roots, applies the `ExcludeMatcher`, and diffs against the catalog in batches
    of 500 inside one transaction per batch.
  - Size or mtime changed, or a new path, gives `NEEDS_HASH`. Unchanged files get
    `last_seen_scan` updated.
  - After a full root walk, the scan is marked complete, and paths with
    `last_seen_scan < scan.id` get `DELETED` and their current version `superseded_at = now`.
  - Tests:
    - new, changed (size), changed (mtime only), unchanged, deleted
    - an incomplete scan (walker throws mid-way) applies no deletions
    - exclude handling
- [ ] **M4.3 Hash stage.**
  - Processes `NEEDS_HASH` files older than 45 s (by `Clock`), one at a time. Files that
    are too fresh wait until the next run.
  - Re-stat after hashing. If the file changed, leave it as `NEEDS_HASH`.
  - Read errors set `ERROR` with a code (`EACCES`, `ENOENT`, `EIO`) and do not abort the run.
  - Tests: a file mutated during hashing, a file deleted between scan and hash, and the
    settle-time behaviour.
- [ ] **M4.4 `Planner`.**
  - For each newly hashed file:
    1. Look up `contents` by sha256. If missing, create it with a layout from
       `PartSizing.choose` (enabled destinations' detected limits), `encrypted` = current
       setting, and `oid` per §4.1.
    2. Insert the `file_versions` row (supersede the previous current version).
  - Pending work per destination is all `content_parts` of LIVE contents referenced by a live
    version that have no `part_locations` row for that destination.
  - Order is smallest content first, then oldest `first_seen`, so many small files finish
    early.
  - Tests:
    - two identical files give one content
    - a changed file gives a new content and a superseded version
    - a file reverted to an old hash reuses the old content, and no upload is needed
    - enabling a second destination later makes all existing parts pending for it only
- [ ] **M4.5 `PartProducer` and scratch handling** (§2.5).
  - Opens the source at `plain_offset` and copies `plain_size` bytes into the scratch file
    `<scratchDir>/<oid16>.<pi>.dbp`, hashing as it goes.
  - If the hash differs from `part_sha256`, the source changed after planning. Discard the
    scratch, mark the file `NEEDS_HASH` and skip the part. Encryption is added in M8.
  - Free-space check before producing.
  - The scratch directory is `context.noBackupFilesDir/scratch`. It is cleaned at session
    start, except files referenced by `drive_sessions`.
- [ ] **M4.6 `Uploader`** (one per destination, run concurrently per §2.5).
  - For each pending part, call `transport.uploadPart`, then **in one transaction** insert
    `part_locations` and remove any `drive_sessions` row.
  - Exceptions are handled by `RetryPolicy`:
    - `RetryAfter`: wait, then retry the same part (the Telegram pacer applies its own
      global wait).
    - `Backoff`: retry up to 5 times, then move on and leave the part pending.
    - `PauseDest`: pause this destination for the run and record the reason.
    - `FailItem`: record the error and continue.
  - Before uploading a part, check `limits.quotaFreeBytes` (when known) against `stored_size`.
    If it does not fit, pause with `QUOTA`.
  - Respects the time budget.
  - Tests: everything with `FakeTransport` failure scripts, plus a property test that kills
    the session at a random point, restarts it, and checks that every part ends up uploaded
    **exactly once** (no duplicates in `FakeTransport`) and that no part is recorded without
    being stored.
- [ ] **M4.7 `SnapshotWriter`.**
  - Builds `IndexSnapshot` from the catalog (§4.5) and streams it through
    `IndexSnapshotCodec` into a scratch file.
  - Calls `putIndex` on each prepared destination, records the `snapshots` row, then rotates:
    deletes remote and local snapshot rows beyond `snapshot_keep`.
  - Runs even when the session stopped on budget.
  - Tests: snapshot content equals the catalog state, `pendingParts` is correct, and rotation
    keeps N.
- [ ] **M4.8 `BackupSession` orchestration and progress.**
  - Emits `StateFlow<SessionProgress>`: stage, files scanned/hashed, bytes done/total per
    destination, current pause reasons, ETA (EWMA throughput).
  - Writes the `runs` row on start and end.
  - Cancellation-safe: on `CancellationException`, it commits nothing partial beyond what the
    invariants already guarantee, and rethrows.
- [ ] **M4.9 Wiring.**
  - `BackupWorker` builds the session from the enabled destinations (only Drive for now),
    with a 45-min budget.
  - The Home screen binds to progress through a `ProgressRepository` (a process-wide
    `StateFlow` that the worker publishes to).
  - Schedule changes re-enqueue periodic work.
- [ ] **M4.10 Pre-run estimate.** The Home screen shows "X files, Y GB pending for Drive (free
  space Z GB)", plus a warning when Y > Z.

**Acceptance**

- CI is green, and Kover on `:core` stays at least 80 %.
- On a device (S7), with Drive enabled, a folder of mixed files (including one > 600 MB) is
  backed up.
- A second run uploads nothing.
- After one file is modified, only it is uploaded.
- Killing the app during a large upload resumes it on the next run without restarting.
- The snapshot file appears in `DuoBackup/index/`.

---

### M5 — Restore with layered verification

**Branch:** `feat/restore`.

**Goal:** reliable, resumable, verified restore of any file version from the catalog, plus a
verify-only "restore drill". Restore is tested from the first destination onward, because a
backup whose restore has never been exercised is not a backup.

**Steps**

- [ ] **M5.1 Schema v2** (the first real migration).
  - Add the `restore_jobs` table: `id`, `created_at`, `mode` (`ORIGINAL`/`FOLDER`/
    `VERIFY_ONLY`), `folder_name`, `conflict` (`KEEP_BOTH`/`OVERWRITE`/`SKIP`),
    `prefer_dest`, `state`, `finished_at`.
  - Add the `restore_items` table: `job_id`, `vol`, `path`, `sha256`, `mtime`, `state`
    (`PENDING`/`RUNNING`/`RESTORED`/`RESTORED_FROM_FALLBACK`/`SKIPPED_IDENTICAL`/`FAILED`),
    `error`, `bytes_done`.
  - Write `Migration(1, 2)` with a migration test based on the exported v1 schema.
- [ ] **M5.2 `RestoreEngine`** in `:core` (§2.4).
  - **Target path:**
    - `ORIGINAL` uses `<volume root>/<path>`.
    - `FOLDER` uses `<primary>/DuoBackup Restore/<folder_name>/<path>`, which is excluded
      from scans.
  - **Temp file:** `<target dir>/.<name>.duobackup-part`, kept on the same filesystem so the
    rename is atomic. Parent directories are created as needed.
  - **Free space:** before starting a file, require the remaining file bytes plus one stored
    part plus 64 MiB.
  - **Per part:**
    1. Download into the scratch file `restore-<oid16>.<pi>`.
    2. Check the stored size.
    3. Stream through `decrypt?` (identity until M8) and SHA-256 at the same time, appending
       to a *part buffer file* rather than the temp file.
    4. Only when the hash equals `part_sha256`, append the buffer to the temp file.

    No unverified byte ever reaches the temp file.
  - **Fallback:** a download or verification failure on the preferred destination tries the
    other destination for that part. If both fail, the item is `FAILED(PART_UNAVAILABLE |
    PART_CORRUPT)`. The temp file is kept for a retry and deleted when the job is cancelled.
  - **Resume:** the temp file length is always at a part boundary, so resume continues at the
    next part. The whole-file digest is rebuilt by re-hashing the existing temp prefix.
  - **Finalize:**
    1. Compare the whole-file SHA-256.
    2. Apply the conflict policy:
       - existing file with the same SHA-256: `SKIPPED_IDENTICAL`
       - `KEEP_BOTH`: rename to `name (restored N).ext`
       - `OVERWRITE`: atomic replace via `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`
       - `SKIP`
    3. Call `setLastModified(mtime)`.
    4. Report the result.
  - **Empty content** creates an empty file. **`VERIFY_ONLY`** runs the same pipeline but
    discards the output after the whole-file hash matches, and records `verified_at` on the
    used `part_locations`.
  - Tests (FakeTransport + temp dirs):
    - happy path, multi-part
    - a corrupted part on the preferred destination falls back to the other
    - both corrupted gives FAILED, and no file at the target
    - kill mid-file and resume
    - all three conflict policies
    - an identical existing file is skipped
    - empty file
    - a deep missing directory tree
    - `VERIFY_ONLY` writes nothing under the target roots
- [ ] **M5.3 `RestoreWorker`.**
  - Unique work `duobackup.restore`, foreground `dataSync`, same constraints model as backup.
    "Wi-Fi only" applies, but the user can override it per job.
  - Mutually exclusive with `BackupWorker` through a shared `Mutex` in a process-wide
    `EngineLock`. A scheduled backup that finds a restore running reschedules itself.
  - After restoring media files, calls `MediaScannerConnection.scanFile` in batches.
- [ ] **M5.4 Browse and restore UI.**
  - The tree view is built from the catalog. Each file shows a status chip:
    - Backed up: Drive / Telegram / Both
    - Pending
    - Error
    - Deleted on device
  - A file's detail shows its versions (`file_versions`) with dates and sizes.
  - Actions: restore file, restore version, restore folder, restore everything.
  - The restore dialog has the mode (default `FOLDER` with name
    `yyyy-MM-dd HHmm`), the conflict policy (default `KEEP_BOTH`), the preferred destination
    and "Allow mobile data".
  - A job screen shows progress and a per-item result list, filterable by FAILED. It has
    "Retry failed".
- [ ] **M5.5 Restore drill.**
  - Settings has "Verify backups now", which runs `VERIFY_ONLY` on a random sample (default
    5 files, at most 200 MB total, spanning both destinations when available).
  - It also runs automatically every 7 days on unmetered networks while charging.
  - The Home screen shows the last verification time and its result.

**Acceptance**

- CI is green.
- On a device (S7), restore the M4 test folder to `DuoBackup Restore/`. Restored files match
  their originals byte for byte (compare hashes in the UI detail).
- Killing the app mid-restore resumes it.
- Turning on airplane mode mid-part, then off again, recovers.
- The drill reports OK.

---

### M6 — Telegram destination (TDLib)

**Branch:** `feat/telegram-destination`. It can be split into `feat/tdlib-auth` and
`feat/telegram-transport`. **Needs:** S4.

**Goal:** `TelegramTransport : BackupTransport` on top of TDLib, with in-app login, a storage
channel, the global pacer and runtime limit detection.

**Facts to verify first** (from the pinned artifact `com.github.tdlibx:td:<latest tag>`,
1.8.56 at the time of writing; JitPack serves it):
- the exact constructors of `SetTdlibParameters`, `CreateNewSupergroupChat`,
  `SearchChatMessages` (newer TDLib versions add `topicId`), `MessageSendOptions` and
  `InputMessageDocument`
- the error format for flood waits

Record the findings in the Decision Log before writing the gateway.

**Steps**

- [ ] **M6.1 Dependency and packaging.**
  - Add `tdlib = { module = "com.github.tdlibx:td", version = "…" }`.
  - Set `ndk.abiFilters = ["arm64-v8a", "armeabi-v7a", "x86_64"]`.
  - Release builds produce ABI splits plus a universal APK. Debug builds stay universal.
  - R8 rule: `-keep class org.drinkless.tdlib.** { *; }`.
  - CI asserts the APK contains `lib/arm64-v8a/libtdjni.so` (`unzip -l`).
- [ ] **M6.2 `TdGateway`** (interface plus `TdGatewayImpl`), the only code that touches
  `org.drinkless.tdlib.Client`.
  - One client per process, owned by an application-scoped singleton (`TdHolder`) and
    shared by the UI and workers.
  - `Client.create(updatesHandler, updateExceptionHandler, defaultExceptionHandler)`.
    Updates are emitted to a `MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256)`.
  - `suspend fun <R : TdApi.Object> send(fn: TdApi.Function<R>): R` uses
    `suspendCancellableCoroutine`. A `TdApi.Error` becomes `TdError(code, message)`.
  - `Client.execute` is used for synchronous calls (`setLogVerbosityLevel(1)` at startup;
    TDLib logs go to `noBackupFilesDir/tdlib/td.log`, capped at 10 MB, or off in release).
  - Error parsing (`TdErrors.retryAfterSeconds(e)`) handles both `429 "Too Many Requests:
    retry after N"` and `420 "FLOOD_WAIT_N"`. Unit tests cover both and a non-match.
- [ ] **M6.3 Auth state machine** (`TelegramAuth`), exposing a
  `StateFlow<TgAuthState>` mapped from `updateAuthorizationState`.
  - `WaitTdlibParameters`: send `SetTdlibParameters` with:
    - `databaseDirectory = filesDir/tdlib/db`
    - `filesDirectory = noBackupFilesDir/tdlib/files`
    - `databaseEncryptionKey` from `SecretStore` (generated once)
    - `useFileDatabase`, `useChatInfoDatabase` and `useMessageDatabase` set to true
    - `useSecretChats = false`
    - `apiId`/`apiHash` from `SecretStore`
    - `systemLanguageCode = Locale.getDefault().language`
    - `deviceModel = Build.MODEL`, `systemVersion = Build.VERSION.RELEASE`
    - `applicationVersion = BuildConfig.VERSION_NAME`
  - The login flow supports these UI states:
    - phone number
    - **QR login** (`RequestQrCodeAuthentication`, which renders the `link` from
      `WaitOtherDeviceConfirmation` as a QR; preferred when the user has Telegram on another
      device)
    - code (shows the code type: app / SMS / call)
    - email address and email code (when Telegram asks for them)
    - 2FA password (shows the hint)
  - `WaitRegistration` shows "Create the account in the official Telegram app first". No
    sign-up is done here.
  - `Ready` checks `getMe` and stores the user id.
  - `LoggingOut`/`Closed` handling: if `Closed` arrives unexpectedly, the holder recreates
    the client.
  - Log out calls `LogOut` and leaves the channel intact.
  - **Credential entry screen:** `api_id` (positive integer) and `api_hash` (32 hex chars),
    validated, with a link to my.telegram.org and a step-by-step explainer.
  - Workers call `TelegramAuth.awaitReady(timeout = 30 s)`. If it is not ready, they throw
    `AuthRequired` and post a "Sign in to Telegram" notification.
- [ ] **M6.4 `TelegramPacer`** (`CLAUDE.md` invariant 4).
  - Wraps every message-creating or editing call: `sendMessage`, `editMessage*`,
    `deleteMessages`, `createNewSupergroupChat`, `setChatDescription`.
  - Uses a `Mutex`, a minimum spacing of 1.5 s between writes (configurable; never below
    1 s), and a global `pausedUntil` set to `now + (retryAfter + 1) s` on any flood-wait.
    That includes flood waits reported asynchronously through `updateMessageSendFailed`.
  - Exposes `StateFlow<PacerState>` so the UI can show "Telegram asked to wait 6 min".
  - A wait longer than 30 min pauses the Telegram destination for this run (`PauseDest`)
    instead of blocking the worker.
  - Tests use virtual time:
    - spacing is respected
    - a flood-wait blocks all callers
    - the async flood-wait path works
    - a long wait gives `PauseDest`
- [ ] **M6.5 `TelegramLimits`** (`CLAUDE.md` §5).
  - `getOption("is_premium")` gives `OptionValueBoolean`.
  - `getApplicationConfig()` gives a `JsonValueObject`. Read `upload_max_fileparts_premium`
    or `upload_max_fileparts_default` (a `JsonValueNumber`).
    `maxFileBytes = parts × 524_288`.
  - `getOption("message_caption_length_max")` gives `OptionValueInteger`.
  - On failure or a missing key, use the `Limits.kt` fallbacks and log a warning.
  - Values are cached for the session and invalidated on `updateOption` for `is_premium` /
    `message_caption_length_max`.
  - Unit tests run with a fake gateway returning recorded JSON values, missing keys and
    errors.
- [ ] **M6.6 Storage channel** (`StorageChannel`, §4.3).
  - `ensure()` checks:
    1. If `tg_storage_chat_id` is set and `getChat` succeeds, check that the marker is in
       `getSupergroupFullInfo(...).description`. If so, done.
    2. Otherwise discover: `searchChats("DuoBackup", 50)` then `searchChatsOnServer`, keep
       channel chats where the user is the creator and the description has
       `#duobackup-storage v1`. If there is one, use it. If several, return the candidates to
       the UI to choose.
    3. If none, create it with `createNewSupergroupChat(title = "DuoBackup", isChannel = true,
       description = marker)`, through the pacer.
  - The chat is archived and muted (`addChatToList(ChatListArchive)`,
    `setChatNotificationSettings` mute forever) so it doesn't clutter the user's chat list.
    This is best effort.
- [ ] **M6.7 `TelegramTransport : BackupTransport`.**
  - `uploadPart`:
    1. Build the caption with `CaptionCodec` under the detected budget.
    2. Call `sendMessage(chatId, InputMessageDocument(InputFileLocal(scratch), thumbnail =
       null, disableContentTypeDetection = true, caption), options(disableNotification =
       true))` through the pacer.
    3. Wait on the updates flow for `updateMessageSendSucceeded` whose `oldMessageId` equals
       the temporary id, or `updateMessageSendFailed`.
    4. Track `updateFile` for the document file id to report upload progress
       (`remote.uploadedSize`).
    5. **Stall timeout:** if there is no progress for 5 min, call `deleteMessages` on the
       temporary message (which cancels the upload) and throw `Transient`.
    6. On success, return `RemoteLocation.Telegram(chatId, message.id,
       document.document.remote.id, document.document.remote.uniqueId)`.
  - `downloadPart`:
    1. Resolve the file with `getRemoteFile(remoteFileId)`. If that fails (stale file
       reference), use `getMessage(chatId, messageId)` and take the document file.
    2. Call `downloadFile(fileId, priority = 16, offset = 0, limit = 0, synchronous =
       false)` and observe `updateFile` until `local.isDownloadingCompleted`, applying the
       same stall timeout.
    3. Move `local.path` to `target` (copy and delete if on a different filesystem), then
       call `deleteFile(fileId)` so TDLib's cache does not grow.
  - `checkPart` uses `getMessage`. A "not found" error gives `MISSING`.
  - `deletePart` calls `deleteMessages([id], revoke = true)` through the pacer.
  - `putIndex` sends the document with an index caption (§4.3) and returns the location.
  - `listIndexes` uses `searchChatMessages(chatId, query = "#duobackup-index", filter =
    SearchMessagesFilterDocument, limit = 100)`. It pages with `fromMessageId` and parses the
    captions.
  - `getIndex` downloads like a part. `deleteIndex` deletes like a part.
  - Tests (`FakeTdGateway`, plain JVM unit tests in `:telegram`; the `TdApi` classes are
    plain Java, and only `Client` needs native code):
    - the send-succeeded and send-failed paths
    - an async flood-wait
    - stall timeout
    - a stale remote id falls back to `getMessage`
    - download cleanup calls `deleteFile`
    - caption over budget gives an error before any send
    - index listing parses only DuoBackup captions
- [ ] **M6.8 Destinations UI for Telegram.**
  - The card shows the signed-in account (name, Premium badge if detected), the detected
    per-file limit and caption limit, the storage channel name, the pacer state, and buttons
    for Sign in / Sign out / Test upload (same probe as Drive, using a probe caption
    `#duobackup-probe`, deleted afterwards).
  - An explainer says that Telegram may slow uploads after tens of GB on free accounts, so the
    first full backup can take days and resumes automatically.
- [ ] **M6.9 Enable Telegram in the engine.** `BackupWorker` includes `TelegramTransport`
  when it is enabled. The engine from M4 needs no changes; if it does, the abstraction is
  wrong, so fix the abstraction.

**Acceptance**

- CI is green, and the APK contains the TDLib native libraries.
- On a device (S7):
  - Sign in by code and by QR both work.
  - 2FA works when it is enabled.
  - The channel is created once (signing out and in again reuses it).
  - Test upload passes.
  - A > 600 MB file backs up as 2 parts (on a free account: 512 MiB + rest).
  - Restore from Telegram verifies.
  - Killing the app mid-part re-sends that part only, with no duplicate parts recorded
    (orphan messages are allowed and cleaned in M9).

---

### M7 — Dual destination, reconciliation, connect existing backup

**Branch:** `feat/dual-destination`.

**Goal:** both destinations work together. The catalog can be rebuilt on a new phone from
either destination, and redundancy can be inspected and repaired.

**Steps**

- [ ] **M7.1 Concurrent uploaders with a shared producer** (§2.5).
  - Verify, with an engine test using two `FakeTransport`s, that:
    - a part's scratch file is produced once and used by both destinations
    - one destination being paused (QUOTA or a long flood-wait) does not stop the other
    - the paused destination later re-produces the part from the source
  - Scratch accounting never exceeds 2 parts.
- [ ] **M7.2 Per-file redundancy status.**
  - Derived by query: `BOTH`, `DRIVE_ONLY`, `TELEGRAM_ONLY`, `PENDING`, `ERROR`.
  - Shown in Browse and summarised on Home ("12,431 files · 98 % on both").
- [ ] **M7.3 Snapshot to both destinations.** `SnapshotWriter` writes to every prepared
  destination. `snapshots` rows record both locations. Rotation runs per destination.
- [ ] **M7.4 Connect existing backup** (new phone, reinstall, wiped app data).
  - The Onboarding screen offers "I already have a DuoBackup backup". Connect Drive and/or
    Telegram, then:
    1. `listIndexes` on each, take the newest of each, download and verify (gzip and JSON
       well-formed), then `IndexSnapshot.merge`.
    2. If the index is encrypted, prompt for the passphrase (M8 adds it; until then encrypted
       indexes are reported as "requires encryption support").
    3. Import into the catalog in one transaction: `contents`, `content_parts`,
       `part_locations`, then `files` (state `HASHED`, size/mtime from the index,
       **`hold = 1`**) and current `file_versions`.
    4. Report the counts.
  - **Hold semantics:**
    - Scans compare held files normally. If the path exists with the same size and mtime, it
      is unchanged, so `hold` is cleared and nothing is uploaded.
    - If the path exists but differs, it is a new version, and `hold` is cleared.
    - If the path is missing, nothing happens: no deletion is recorded.
    - Restoring a held file clears `hold`.
    - Browse has a filter "Not on this phone (from previous backup)" with bulk actions
      *Restore* and *Forget* (forget = release hold, so it becomes deleted and expires
      through retention).
  - Tests: import from Drive-only, Telegram-only and merged indexes; hold semantics in all four
    cases; re-import is idempotent.
- [ ] **M7.5 Rebuild from Telegram captions** (disaster path when no index is readable).
  - Page through the storage channel with `searchChatMessages(filter = Document)`, newest to
    oldest, 100 per page, through a read pacer with 1 req/s.
  - Decode every `db1:`/`dbe1:` caption into contents, parts and locations. Group parts by
    `o` and accept a content only when all `pc` parts are present.
  - Recover paths from `p`/`vol`/`mt`. One path per content is the limit; note this in the UI.
  - Show a progress and result summary. This is also offered from Settings → "Rebuild catalog
    from Telegram".
  - Tests: a fake channel with complete contents, incomplete contents, foreign messages and
    duplicate parts (the newest message wins).
- [ ] **M7.6 Repair redundancy** (manual action plus a monthly suggestion).
  - Scan the catalog for parts present on one destination but missing on the other enabled
    one.
  - Source preference is:
    1. the local file, if it still exists and its part hash matches
    2. otherwise download from the destination that has it, verify `part_sha256`, then upload
  - This uses the same uploader, budget and pacer rules.
  - Tests: both source paths, plus a corrupt remote that is not propagated.

**Acceptance**

- CI is green.
- On a device (S7), with both destinations enabled, a backup shows "on both" for all files.
- After clearing app data, "Connect existing backup" restores the full Browse tree from Drive.
  Doing it again from Telegram only gives the same tree.
- Deleting the Drive index files and running "Rebuild from Telegram" recovers all contents.

---

### M8 — Optional encryption

**Branch:** `feat/encryption`.

**Goal:** opt-in encryption (off by default) for content, captions, Drive properties and
indexes, using the formats already built and tested in M1.6. It has safe key backup and
honest UX.

**Steps**

- [ ] **M8.1 `KeyManager`** in `:app`.
  - `enable(passphrase)`:
    1. Generate `masterKey` (`SecureRandom`).
    2. Build `KeyFile` (600k iterations).
    3. **Upload the key file to every enabled destination** (`putKeyFile`) and read each one
       back to verify it unlocks.
    4. Only then store the wrapped `masterKey` in `SecretStore` and set
       `encryption_enabled = true`.

    If any step fails, roll back and leave encryption off.
  - `unlock(passphrase)` (new device) fetches the key file from any destination, unlocks it
    and stores the key.
  - `changePassphrase(old, new)` writes a new key file (same `keyId`) to all destinations.
    The old key files are kept for 7 days, then deleted.
  - `disable()` sets the flag off for future contents only. The key is kept, because existing
    encrypted data still needs it.
  - Implement `putKeyFile`/`getKeyFile` on both transports (§4.3, §4.4). This is when the M3
    stubs get replaced.
  - Tests:
    - enable fails if one destination's key upload fails, leaving state unchanged
    - unlock with the wrong passphrase
    - changing the passphrase keeps the data readable
    - disable leaves old contents restorable
- [ ] **M8.2 Engine integration.**
  - **PartProducer:** when `contents.encrypted = 1`, the scratch file gets the DBE1 stream of
    the part plaintext (contentKey, fresh salt per part).
    - The plaintext hash is checked while reading.
    - The stored size is asserted to equal `PartSizing.storedSize`.
  - **RestoreEngine:** `Dbe1.newReader` sits between the download and the hash check. Any
    `CryptoException` counts as `PART_CORRUPT`, which triggers the fallback.
  - **Captions:** `dbe1:` via `CaptionCipher(metaKey)`. The `o` key carries the HMAC oid.
  - **Drive `ph`:** HMAC, not the plaintext hash.
  - **Indexes:** `.json.gz.dbe` (metaKey). The whole file is encrypted, so a reader learns
    which key it needs from the index's Drive `appProperties.kid` or Telegram caption
    `kid=<keyId>` (§4.3, §4.4), not from the JSON.
  - **Dedup and planning:** a content that already exists as plain is reused as it is.
    Encryption is a property of the content, decided once. Settings shows "N files stored
    without encryption (backed up before encryption was enabled)". Re-encrypting is Deferred
    (§9).
  - Tests: the full engine round-trip with encryption on both FakeTransports, and mixed plain
    and encrypted contents in one snapshot. Tampered ciphertext on one destination makes
    restore succeed through the other.
- [ ] **M8.3 Locked state.**
  - If `encryption_enabled` is set but the key cannot be loaded (for example, the Keystore
    was wiped after a restore from a device backup), backups pause with
    `KEY_LOCKED`, the app asks for the passphrase, and restores of encrypted contents are
    blocked with the same prompt.
  - Exclude the DataStore, the Tink keyset and the TDLib dirs from Android Auto Backup and
    device-transfer (`android:dataExtractionRules`, `android:fullBackupContent`). Keystore keys
    do not migrate, so the wrapped blobs must not either.
- [ ] **M8.4 Recovery code.** On enable, show a **recovery code**: `masterKey` as Crockford
  Base32 in groups of 4, plus a 4-character checksum.
  - The user must confirm they saved it by retyping 2 random groups.
  - Restoring with the recovery code alone, without the passphrase, is supported.
  - The UI states plainly: "Without the passphrase or the recovery code, nobody — including
    you — can read the encrypted backup."
- [ ] **M8.5 Settings UI.** Encryption toggle, change passphrase, show recovery code (requires
  the passphrase again), and a status row with `keyId`. The passphrase strength meter requires
  at least 12 characters and warns on common patterns.

**Acceptance**

- CI is green.
- On a device (S7), with encryption enabled:
  - Back up a folder.
  - In Telegram, captions show only `dbe1:…`.
  - In Drive, file names show only `<hex>.<n>.dbp` and properties hold no plaintext hashes.
  - Restore verifies.
  - Clearing app data and using "Connect existing" with the passphrase restores. Doing it with
    the recovery code also works.

---

### M9 — Retention, garbage collection, audit

**Branch:** `feat/retention`.

**Goal:** destinations do not grow forever. Old versions and deleted files expire, and
unreferenced remote data is removed safely. Missing remote data is detected and re-uploaded.

**Steps**

- [ ] **M9.1 Expiry rules** (`RetentionEngine`, pure `:core`, with `retention_days = R`):
  - The current version of an existing file never expires.
  - A superseded version expires when `superseded_at + R days < now`. This covers
    overwritten versions and files deleted on the device.
  - A file with `hold = 1` never expires (M7.4).
  - Snapshots older than `R` days are deleted, except the newest one. Snapshots never keep
    content alive past these rules, so the rules are fully defined by `file_versions`.
  - Expired versions are deleted from `file_versions`. A `files` row with no versions left and
    state `DELETED` is deleted.
  - Tests: a table-driven test over versions and times, the hold case, and the newest
    snapshot being kept.
- [ ] **M9.2 Garbage collection.**
  - A content is garbage when no `file_versions` row references it.
  - It moves to `DELETING`, each location gets `deletePart` (NotFound counts as success), its
    location rows are removed, and then it becomes `GONE` (row deleted).
  - The process is resumable at any step.
  - **Safety rails:**
    - GC never runs in the same session as a catalog import (M7.4/M7.5) or within 24 h of one.
    - GC is skipped if the last scan of any root was incomplete.
    - If one run would delete more than 20 % of live contents or more than 50 GB, it pauses
      and asks the user to confirm in the app.
  - Telegram deletions go through the pacer, at most 100 messages per `deleteMessages` call.
  - Tests: GC after an expiry, resume after a crash mid-GC, safety-rail triggers, and a
    content shared by two files is not deleted while one file still references it.
- [ ] **M9.3 Orphan sweep** (remote objects not in the catalog, such as parts uploaded right
  before a crash, before their location was recorded).
  - **Drive:** list `data/` with `appProperties db = part`. Any file whose id is not in
    `part_locations` and whose `createdTime` is older than 24 h is deleted.
  - **Telegram:** page through the channel's documents. Any message with a `db1:`/`dbe1:`
    caption whose id is not in `part_locations`, and that is older than 24 h, is deleted.
  - **Never** touch objects without DuoBackup markers.
  - The same safety rails as GC apply. The sweep runs at most once a week.
  - Tests with fakes: orphans deleted, young orphans kept, foreign objects kept.
- [ ] **M9.4 Audit.**
  - A weekly light audit runs `checkPart` on a 2 % sample of locations (at least 20,
    rate-limited).
  - A manual full audit is available from Settings.
  - A `MISSING` result sets `part_locations.missing = 1` and removes the row from consideration
    as a location. The part becomes pending again for that destination, and the uploader
    re-uploads it from the local file or through the M7.6 repair path.
  - The Home screen shows the last audit result.
- [ ] **M9.5 Scheduling.**
  - Retention, GC, sweep and audit run at the end of a backup session in the remaining
    budget, each at most once per period.
  - All of them are visible in the activity log with counts of deleted versions, contents and
    bytes freed per destination.
  - Settings offers retention days (7/30/90/365/forever) and snapshots to keep.

**Acceptance**

- CI is green.
- On a device (S7), with `retention_days = 7`:
  - Modify a file three times.
  - Advance time through a debug-only clock override in the developer settings of the debug
    build.
  - Run a backup: old versions expire, their parts disappear from Drive and Telegram, and the
    current version is still restorable.
- A manually deleted Telegram part message is detected by the full audit and re-uploaded.

---

### M10 — Web panel

**Branch:** `feat/web-panel`. **Needs:** S5 and S6. Work can start in parallel with M8 once
M7 is merged, because the format is stable after M1 and M7.

**Goal:** a static, read-only SPA on GitHub Pages that signs in with Google, reads the newest
index from Drive, and lets the user browse files, see redundancy and quota, and download
individual files from Drive in the browser with full verification.

**Step 0 — verify before building.** Confirm that a **Web** OAuth client in the same Cloud
project, with scope `drive.file`, can list and read files created by the Android client.
`drive.file` access is granted per Cloud project. Do the check with a throwaway HTML page and
record the result in the Decision Log. If it fails, stop and raise it with the owner. The
fallback options are a narrow `drive.readonly` scope for the web panel only, or the Google
Picker API.

**Steps**

- [ ] **M10.1 Scaffold `web/`.**
  - Vite + React + TypeScript (strict), ESLint (typescript-eslint, react-hooks), Prettier and
    Vitest.
  - `base: "/duobackup-apk/"`.
  - No UI framework is required. Use a small hand-written CSS with custom properties, dark and
    light themes, and responsive layout (the phone is the primary viewer).
- [ ] **M10.2 Auth.**
  - Use Google Identity Services' **token model** (`google.accounts.oauth2.initTokenClient`,
    scope `drive.file`).
    - The token lives in memory only, with silent re-request on expiry.
    - There is no client secret and no backend, which fits a static site. A refresh token is
      not needed for an interactive panel.
  - The client id comes from `import.meta.env.VITE_GOOGLE_CLIENT_ID`, injected at build time.
  - Set a CSP meta tag allowing only `accounts.google.com` and `www.googleapis.com`.
- [ ] **M10.3 Data layer** (`web/src/lib/`).
  - `drive.ts`: `about`, `listFiles` with paging, `getMedia` (with a `Range` option), using
    `fetch` with the bearer token. Errors are mapped like in M3.2.
  - `layout.ts`: finds the DuoBackup root, `index/` and `key/` folders by `appProperties`.
  - `index.ts`:
    1. Pick the newest index.
    2. Download it.
    3. If it is `.dbe`, ask for the passphrase.
    4. Gunzip with `DecompressionStream('gzip')`.
    5. Parse and validate `format`/`v`.
  - `crypto.ts` (WebCrypto):
    - PBKDF2-SHA256 unwrap of the key file
    - HKDF subkeys
    - DBE1 stream decryptor (frame by frame, rejecting the same tampering as the Kotlin
      reader)
    - `CaptionCipher` decryption (not needed for Drive, but kept for parity tests)
  - The derived key is held in memory only and cleared on sign-out or tab close.
  - **Interop tests:** Vitest reads `../app/core/src/test/resources/golden/*` and must decode
    every golden vector exactly like the Kotlin tests. This is the contract that keeps the APK
    and the web panel compatible.
- [ ] **M10.4 Screens.**
  - **Sign in**
  - **Overview:** device, last backup time and result, pending parts, Drive quota bar, counts by
    redundancy (Drive / Telegram / both, computed from the parts' `tg`/`gd` entries)
  - **Browse:**
    - a folder tree with virtualized lists (handles 100k+ entries)
    - search by name
    - sort by name, size or date
    - file detail with size, mtime, hash, parts and locations
  - **Snapshots:** list of indexes with dates, with the option to open an older one
- [ ] **M10.5 Download from Drive.**
  - For a file whose parts are all on Drive:
    1. Download the parts in order.
    2. Decrypt if encrypted.
    3. Verify each part's SHA-256 (`crypto.subtle.digest` per part; parts are ≤ 512 MiB, so
       use streaming through a WASM-free incremental SHA-256 such as `@noble/hashes`).
    4. Verify the whole-file SHA-256 incrementally.
    5. Write through `showSaveFilePicker` when it is available, otherwise as a Blob for files
       ≤ 1 GB with a clear message above that.
  - Any verification failure aborts and deletes the partial output where possible. The UI
    never offers an unverified file.
  - Telegram-only files show "Stored on Telegram only — restore it from the app", because
    MTProto is not used in the browser.
- [ ] **M10.6 `.github/workflows/web.yml`.**
  - Triggers: PR and push on `web/**`, plus `app/core/src/test/resources/golden/**` so
    interop is re-checked when the vectors change.
  - `setup-node` (LTS, npm cache), then `npm ci`, `npm run lint`, `npm test` and
    `npm run build`.
  - On push to `main`, deploy with `actions/upload-pages-artifact` and `actions/deploy-pages`
    (`permissions: pages: write, id-token: write`, environment `github-pages`).

**Acceptance**

- CI is green, and the interop tests pass on every golden vector.
- The site is live at `https://rosyidputrar.github.io/duobackup-apk/`.
- On a phone browser, sign in, see the same file count as the app, and download one plain and
  one encrypted multi-part file. Their hashes match the originals.
- A tampered part (edited in Drive by the owner for the test) is rejected.

---

### M11 — Hardening and v1.0 release

**Branch:** `chore/release-v1`.

**Steps**

- [ ] **M11.1 Reliability UX.**
  - Offer `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, explained and not forced.
  - Detect a known aggressive OEM (`Build.MANUFACTURER`) and link to dontkillmyapp.com
    instructions.
  - A notification appears when no successful backup has happened for 3 days while backups
    are enabled.
- [ ] **M11.2 Diagnostics.**
  - Keep a local rotating log (5 × 1 MB) with redaction. Tokens, api_hash, phone numbers and
    passphrases are never logged, and paths are hashed at info level.
  - Settings → "Export diagnostics" shares a zip with the logs, the `runs` table and the
    settings without secrets.
  - A unit test asserts that the redaction patterns catch the known secret shapes.
- [ ] **M11.3 Scale test.**
  - A debug-only tool generates 50,000 synthetic files (mixed sizes up to 1.5 GB, sparse) in a
    test folder.
  - Measure scan time, hash throughput, catalog size and snapshot size. Write the results to
    the PR body.
  - Targets on a mid-range phone:
    - incremental scan of 50k unchanged files in under 60 s
    - snapshot for 50k files in under 10 MB gzipped
    - Browse stays smooth
- [ ] **M11.4 Failure drills** (documented in `docs/drills.md` with results):
  - airplane mode mid-upload and mid-restore
  - force-stop
  - reboot mid-session
  - Drive quota full (use a nearly full test account, or simulate it in a debug build)
  - a long Telegram flood-wait
  - an expired or revoked Google grant
  - Telegram session terminated from another device
  - storage full during restore
  - a corrupted part on one destination
  - lost local DB, then recovery through Connect existing
- [ ] **M11.5 Polish.** App icon (adaptive), accessibility (content descriptions, touch
  targets, font scaling), empty and error states for every screen, and an Indonesian
  translation `values-in/strings.xml` (optional, if the owner wants it).
- [ ] **M11.6 Release.**
  - `versionCode` comes from `git rev-list --count HEAD`, and `versionName` from the tag.
  - The workflow `release.yml` on tag `v*`:
    1. Builds the signed release APKs (ABI splits plus universal).
    2. Creates a GitHub Release with the APKs and SHA-256 sums attached, plus the notes from
       `CHANGELOG.md`.
  - Write `CHANGELOG.md` (Keep a Changelog format) and README sections for install, setup
    (S2–S5 for anyone self-building), privacy (what leaves the phone, what is encrypted) and
    limitations.
  - The owner creates the tag `v1.0.0` after merging. The agent never tags without being asked.

**Acceptance:** every drill passes, the release is published by the workflow, and a fresh
install from the release APK can back up and restore on both destinations.

---

## 7. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Telegram limits or ToS on using an account as bulk storage; account restriction | Telegram destination stops | Pacer, no parallel clients, conservative spacing, honest UX; Drive remains as an independent copy; never the only copy by default (onboarding recommends both) |
| Free-account upload throttling after tens of GB | The first full backup takes days | Expected and designed for: budgets, resume, ETA shown, no special casing |
| `com.github.tdlibx:td` is a third-party JitPack build of a native library | Supply chain, stale version | Pin the exact version, restrict the JitPack repo to that group, verify the AAR checksum in the Decision Log; fallback is building TDLib from source in a CI job (documented, Deferred) |
| TDLib API changes between versions | Compile breaks on upgrade | All TDLib use goes through `TdGateway`; Dependabot PRs for TDLib are reviewed by hand |
| Android 15+ `dataSync` time limits and OEM background killing | Runs cut short | 45-min budgets, follow-up runs, resume at part granularity, battery-optimization prompt |
| OAuth app left in "Testing" mode | Drive grants expire every 7 days | S2 says publish to production; the app detects `AuthRequired` and notifies |
| `drive.file` not shared between the Android and web clients | Web panel cannot see files | M10 step 0 verifies first; fallbacks are documented |
| User loses passphrase and recovery code | Encrypted backup unreadable | Encryption is opt-in; a forced recovery-code confirmation step; explicit wording |
| Shared Google storage pool fills up (Gmail/Photos) | Drive uploads stop | Quota detected each run; pause plus notification; retention settings |
| Catalog corruption or loss | Restore blocked | Snapshots on two destinations, caption rebuild (M7.5), Room exported schemas and migration tests |
| Public repository | Secrets could leak | Secrets only in GitHub Actions secrets; `.gitignore`; no credentials in code (`CLAUDE.md` invariant 8) |

---

## 8. Testing strategy

- **`:core` (JVM, JUnit 5)** is where most of the logic and most of the tests live.
  - Formats have golden vectors.
  - The engine has fakes and virtual time (`kotlinx-coroutines-test`).
  - Property-style tests cover chunk math and crash/resume (seeded random; the seed is printed
    on failure).
  - Coverage is at least 80 %, enforced by Kover.
- **`:drive` (JVM)** is tested with MockWebServer and scripted, recorded responses, including
  error bodies. Coverage is at least 80 %.
- **`:telegram`** is tested with `FakeTdGateway`. The `TdApi` classes are plain Java and need
  no native code. Real TDLib is exercised only on device (S7).
- **`:app`** has Robolectric tests for Room DAOs and migrations, `SecretStore`, WorkManager
  scheduling and ViewModels (with Turbine). No instrumented-test CI is required for v1.
- **Web** has Vitest unit tests for the data layer and crypto, plus interop against the Kotlin
  golden vectors.
- **On device**, each PR carries an explicit manual checklist (from the milestone acceptance).
  The owner reports results in a PR comment, and the agent addresses failures in the same PR.
- **Never** delete, skip or weaken a test to get green CI. A test that is wrong is fixed with
  an explanation in the commit message.

---

## 9. Deferred (not in v1)

| Item | Reason |
|---|---|
| Root / other apps' data | Out of scope by owner decision |
| Removable SD card / USB volumes | Volume identity and permission edge cases; the model already has a `volume` column |
| Content-defined chunking | Fixed 512 MiB parts plus whole-file dedup cover phone data (mostly immutable media); revisit for large mutable files |
| Re-encrypting existing plain contents | Costly re-upload; offer once encryption has shipped |
| Restore triggered from the web panel | Needs a phone ↔ web channel; web downloads directly instead |
| Bot API transport | 20 MB download limit makes it unfit (`info.md` §2.2) |
| Multiple Telegram accounts or channels, multiple devices writing one backup | Concurrency and merge complexity; the `install` id in formats keeps the door open |
| Building TDLib from source in CI | Only if the JitPack artifact becomes unavailable or stale |
| Compression | Media dominates phone storage and is already compressed |
| Play Store distribution | `MANAGE_EXTERNAL_STORAGE` policy; sideload or GitHub Releases only |

---

## 10. Decision Log

Append-only. Format: `D<n> (YYYY-MM-DD, milestone): decision — reason`.

- **D1 (2026-11-10, plan):** Drive is implemented before Telegram — it is testable end to end
  in CI with MockWebServer, which proves the engine before TDLib complexity arrives.
- **D2 (2026-11-10, plan):** DuoBackup uses its own remote formats (`db1:`/`dbe1:` captions,
  `DBE1` stream, `duobackup-index` v1) instead of copying TeleDrive's `td1:`/`TDE1` — explicit
  versioning and per-stream keys (HKDF with a random salt) avoid nonce reuse across parts.
- **D3 (2026-11-10, plan):** the passphrase KDF is PBKDF2-HMAC-SHA256 with 600k iterations
  rather than scrypt — it is natively available in WebCrypto for the web panel; the key file
  format allows a future KDF upgrade through `kdf.alg`.
- **D4 (2026-11-10, plan):** Android Drive auth uses Google Identity Services
  `AuthorizationClient` instead of AppAuth — there is no client secret or redirect URI on the
  device, and silent token refresh works in workers.
- **D5 (2026-11-10, plan):** the web panel uses the GIS token model (no refresh token, no
  backend) — a static site cannot keep a client secret, and an interactive panel does not need
  offline access.
- **D6 (2026-11-10, plan):** the catalog is content-addressed (`contents` keyed by plaintext
  SHA-256, `file_versions` for history) rather than the per-file `file_parts` table in
  `info.md` §8.1 — it gives deduplication, versioning and retention-driven GC with one model.
- **D7 (2026-11-10, plan):** Telegram per-file and caption limits are detected at runtime
  (`is_premium`, `getApplicationConfig` → `upload_max_fileparts_*`,
  `message_caption_length_max`), and so is the Drive quota (`about.storageQuota`) — accounts
  differ and change over time; constants are fallbacks only.
- **D8 (2026-11-10, M0):** `compileSdk`/`targetSdk` are 37, not the 36 written when the plan was
  drafted — API 37 is the latest stable platform and AGP 9.5.1 supports it.
- **D9 (2026-11-10, M0):** `:core` and `:drive` test on JUnit 6 (Jupiter), not JUnit 5 — it is
  the latest stable release of the same API line; the test API is unchanged.
- **D10 (2026-11-10, M0):** Android modules do not apply the `kotlin-android` plugin — AGP 9
  compiles Kotlin itself (built-in Kotlin); the Kotlin version is still pinned in the catalog
  through the Compose and serialization compiler plugins.
- **D11 (2026-11-10, M0):** CI sets `cache-provider: basic` on `gradle/actions/setup-gradle` —
  the v6 default is a proprietary caching service; the basic provider is the open-source one
  built on `actions/cache`.
