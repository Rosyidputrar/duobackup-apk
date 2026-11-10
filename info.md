# DuoBackup — Info Lengkap & Inti Atom

Dokumen ini memuat **seluruh pengetahuan** hasil riset mendalam (bedah 15 repo sampai level kode)
untuk membangun sistem backup **seluruh isi penyimpanan HP Android** ke dua gudang sekaligus:

1. **Telegram** (channel privat, lewat akun sendiri via TDLib/MTProto)
2. **Google Drive** (akun Google AI Plus — penyimpanan 400 GB)

Ditulis untuk menjadi satu-satunya referensi yang dibutuhkan saat membangun **APK (Kotlin native)**
sebagai mesin backup dan **web (SPA statis)** sebagai panel. Berisi: arsitektur, fakta platform
terverifikasi, bedah repo per repo, referensi API lengkap, skema data, format enkripsi, algoritma,
matematika skala, anti-pola, dan sample code.

> Sumber clone yang dibedah (dibaca dari kode, bukan README):
> `Mahmud0808/TeleDrive`, `hasibullakhan/TeleNest`, `khrj/teledrive`, `FujiwaraChoki/TeleSync`,
> `rohittp0/GramUp`, `aravindrejidev/TelDrive`, `vedantlavale/taasant`, `Alirewa/tg-bot-uploader-drive`,
> `dominusmmp/gdbackup`, `slashramin/db2cloud-backup`, `offen/docker-volume-backup`,
> `HolgerHees/cloudsync`, `sabeechen/hassio-google-drive-backup`, `XayahSuSuSu/Android-DataBackup`,
> `restic/restic`.

---

## Daftar isi

1. [Gambaran & keputusan arsitektur](#1-gambaran--keputusan-arsitektur)
2. [Fakta platform terverifikasi (angka resmi)](#2-fakta-platform-terverifikasi-angka-resmi)
3. [Bedah repo — klaster Telegram](#3-bedah-repo--klaster-telegram)
4. [Bedah repo — klaster Drive & tujuan ganda](#4-bedah-repo--klaster-drive--tujuan-ganda)
5. [Bedah repo — referensi inti (restic & Android-DataBackup)](#5-bedah-repo--referensi-inti-restic--android-databackup)
6. [Referensi API Telegram](#6-referensi-api-telegram)
7. [Referensi API Google Drive & OAuth](#7-referensi-api-google-drive--oauth)
8. [Skema data & format manifest](#8-skema-data--format-manifest)
9. [Enkripsi](#9-enkripsi)
10. [Algoritma inti](#10-algoritma-inti)
11. [Sisi Android](#11-sisi-android)
12. [Sample code](#12-sample-code)
13. [Matematika skala](#13-matematika-skala)
14. [Anti-pola (terbukti dari repo)](#14-anti-pola-terbukti-dari-repo)
15. [Checklist pembangunan bertahap](#15-checklist-pembangunan-bertahap)
16. [Glosarium & sumber](#16-glosarium--sumber)

---

## 1. Gambaran & keputusan arsitektur

### 1.1 Satu kalimat inti

> Scanner + hasher + chunker + dua adapter transport (Telegram & Drive) + **manifest yang dijaga
> seperti nyawa sistem** — karena tanpa manifest, isi channel Telegram hanyalah tumpukan pesan
> tak bernama dan isi Drive hanyalah file tanpa konteks.

### 1.2 Pipeline backup (satu arah, berlaku untuk kedua gudang)

```
Pindai penyimpanan HP
  → hash tiap file (SHA-256)
  → bandingkan dengan manifest: hanya file baru/berubah yang diproses (inkremental)
  → potong file besar jadi part (chunk)
  → (opsional) enkripsi per part/frame
  → unggah lewat antrean (WorkManager), catat lokasi tiap part ke manifest
  → tutup sesi: perbarui snapshot indeks di cloud
```

Pipeline restore adalah kebalikannya persis, dengan verifikasi hash di setiap lapis
(lihat §10.4).

### 1.3 Keputusan transport (hasil bedah, bukan selera)

| Gudang | Jalur yang dipilih | Alasan atomik |
|---|---|---|
| Telegram | **Akun sendiri via TDLib (MTProto)** | Satu-satunya jalur yang sanggup menelan satu HP penuh dengan jumlah pesan wajar (part 512 MiB). Bot API polos dibatasi unggah 50 MB dan **unduh balik hanya 20 MB** — lihat §2.2. |
| Google Drive | **OAuth pengguna, scope `drive.file`**, resumable upload tulisan tangan | Akun pribadi 400 GB tidak bisa memakai service account + impersonasi (itu Google Workspace). Scope `drive.file` = aplikasi hanya melihat file buatannya sendiri. |

Lapisan transport diabstraksikan satu antarmuka, dua adapter (pola `docker-volume-backup`):

```
interface BackupTransport {
    suspend fun upload(part: Part): RemoteLocation   // lokasi WAJIB dicatat sebelum part dianggap selesai
    suspend fun download(location: RemoteLocation, range: LongRange?): ByteSource
    suspend fun exists(sha256: String): Boolean      // dedup
    suspend fun delete(location: RemoteLocation)
    suspend fun prune(policy: RetentionPolicy)
}
```

### 1.4 Pembagian peran APK & web

- **APK = mesin.** Akses penyimpanan, TDLib, unggah/unduh, enkripsi, DB lokal (Room/SQLite),
  pekerjaan latar (WorkManager). Mesin tidak bisa murni web karena izin akses file Android.
- **Web = panel.** Menjelajah manifest, melihat status & kuota dua gudang, memicu restore.
  Web membaca **snapshot indeks** yang sama yang disimpan APK ke cloud (file `.json.gz` di
  channel Telegram dan/atau Drive) — web tidak pernah menyentuh file HP secara langsung.
- Urutan pembangunan: **APK dulu** (semua risiko teknis ada di sana dan format manifest
  ditentukan olehnya), web menyusul membaca format yang sudah stabil.

### 1.5 Dua gudang, satu skema indeks

Sumber kebenaran ganda (lihat §8):

- **DB lokal** (Room/SQLite) untuk kecepatan di HP.
- **Manifest yang ikut tinggal di cloud** agar bisa dibangun ulang di perangkat lain:
  - Telegram: caption JSON per file + snapshot indeks `.json.gz` berkala di channel.
  - Drive: `appProperties` per file (file dikenali dari propertinya, bukan nama).

---

## 2. Fakta platform terverifikasi (angka resmi)

### 2.1 Google — paket & Drive

- **Google AI Plus (Indonesia): Rp75.000/bulan, penyimpanan 400 GB.** Kuota adalah **satu kolam
  bersama** untuk Google Drive + Gmail + Google Foto, dan bisa dibagikan ke sampai 5 anggota
  keluarga (kuotanya tetap satu kolam). Di atasnya: AI Pro 5 TB (Rp309.000/bulan).
- Implikasi backup: ruang bersih Drive = 400 GB − isi Gmail/Foto/Drive yang sudah ada.
  Berhenti langganan = kuota jatuh kembali; rencanakan retensi dengan sadar.
- Drive API: satu file bisa sampai **5 TB**; unggahan resumable memakai potongan kelipatan
  **256 KiB** (kecuali potongan terakhir). Properti kustom file (`appProperties`): kunci + nilai
  harus < **124 byte** UTF-8 (praktik aman: < 120 byte).
- Error kuota/rate: HTTP 403 dengan reason `userRateLimitExceeded` / `rateLimitExceeded` /
  `quotaExceeded`, dan 429 — semuanya harus dijawab **backoff eksponensial**, bukan retry rapat.

### 2.2 Telegram — batas-batas (dari FAQ & dokumentasi resmi Bot API/MTProto)

| Hal | Akun gratis | Premium | Bot API (server publik) | Bot API server lokal |
|---|---|---|---|---|
| Ukuran maks per file (unggah oleh akun/bot) | **2 GB** (tepatnya 2.097.152.000 byte) | **4 GB** (4.194.304.000 byte) | **50 MB** | **2.000 MB** |
| Unduh file oleh bot | — | — | **20 MB** | tanpa batas ukuran |
| Total penyimpanan cloud | **tak terbatas** | tak terbatas | — | — |
| Jumlah file / pesan di channel | tak terbatas | tak terbatas | — | — |
| Caption per pesan | 1.024 karakter | 2.048 karakter | sama | sama |
| Hapus pesan oleh bot | — | — | hanya pesan < **48 jam** | — |

Fakta turunan yang menentukan desain:

- File 4 GB yang diunggah akun Premium ke channel **tetap bisa diunduh utuh oleh akun gratis**.
- **Aritmetika part MTProto**: file diunggah dalam part 512 KiB; akun biasa maks **4.000 part**
  (= 2.097.152.000 byte), Premium **8.000 part** (= 4 GiB). File yang ukurannya jatuh di sela
  angka bulat bisa ditolak server dengan `FILE_PARTS_INVALID`.
- Akun gratis **diperlambat (throttle)** setelah mengunggah puluhan GB (dokumentasi pengembang
  Telegram) — backup inkremental harian tidak terasa; unggahan massal sekali jalan akan ditahan.
- `FLOOD_WAIT` (HTTP 420 di MTProto / 429 di Bot API) berlaku **per akun**, bukan per chat:
  respons membawa `retry_after` (detik) yang **wajib** dipatuhi.
- Mengirim lewat `file_id` (file yang sudah ada di server Telegram) tidak kena batas ukuran —
  batas hanya berlaku saat byte benar-benar diunggah/diunduh.

### 2.3 Peta jalan transport Telegram yang terbukti di lapangan

| Jalur | Dipakai oleh | Batas per file | Ukuran part dalam kode |
|---|---|---|---|
| TDLib / akun (MTProto) | TeleDrive, TeleNest, teledrive (khrj) | 2 GB / 4 GB | 512 MiB; 1.950 MiB |
| Bot via MTProto (Pyrogram) | tg-bot-uploader-drive | 2 GiB (ditetapkan di kodenya) | tidak memecah |
| Bot API HTTP polos | TelDrive (Flutter), taasant | 50 MB naik / **20 MB turun** | 19 MiB (taasant) |

**Hukum taasant** (keputusan desain paling khas di seluruh riset): ukuran part harus diturunkan
dari **batas unduh**, bukan batas unggah — karena backup yang tidak bisa diunduh kembali bukan
backup. Lewat Bot API itu berarti part ≤ 19 MiB.


---

## 3. Bedah repo — klaster Telegram

### 3.1 TeleDrive — Mahmud0808 (Kotlin Multiplatform, TDLib) — spesimen terbaik

**Transport & autentikasi**

- TDLib 1.8.56 lewat wrapper `com.github.tdlibx:td`; klien utama
  `core/telegram/TdLibTelegramClient.kt`.
- Pengguna memasukkan **api_id + api_hash miliknya sendiri**, divalidasi (`apiId > 0`,
  `apiHash.length >= 16`), disimpan **terenkripsi** di DataStore (`api_id_enc`/`api_hash_enc`).
- Login standar TDLib: `SetTdlibParameters` (dengan `databaseEncryptionKey` dari provider kunci
  lokal) → `SetAuthenticationPhoneNumber` → `CheckAuthenticationCode` → `CheckAuthenticationPassword`
  (2FA). State machine otorisasi TDLib yang menggerakkan UI.

**Gudang & unggah**

- Gudang = **channel supergroup privat** yang dibuat/ditemukan otomatis: judul `TeleDrive`,
  deskripsi berisi penanda `#teledrive-storage`; penemuan via `SearchChatsOnServer` + cek
  deskripsi; pembuatan via `CreateNewSupergroupChat`. Mendukung multi-channel.
- Satu pesan per file: `SendMessage` + `InputMessageDocument(InputFileLocal(path))`;
  mengganti file memakai `EditMessageMedia`.
- Batas dihitung dari aritmetika part internal (§2.2), bukan angka iklan — termasuk komentar
  kode soal `FILE_PARTS_INVALID` untuk ukuran di sela angka bulat.

**Pemecahan file besar**

- `PART_SIZE = 512 MiB` (`core/transfer/FileParts.kt`); part dinamai `nama.001`, `.002`, dst.
- `PartUploader`: tiap part ditulis ke **file scratch** `${id}.${index}.part`, diunggah, lalu
  scratch **dihapus sebelum part berikutnya dibuat** — tidak pernah ada salinan kedua sebesar
  file asli; memungkinkan streaming + enkripsi tanpa menggandakan ruang.

**Bookkeeping**

- Room, tabel `files`: `id, folderId, name, sizeBytes, mimeType, category, localPath,
  contentHash` (SHA-256), `chatId, messageId, remoteFileId, remoteUniqueId, backupState,
  isEncrypted, partCount, trashedAt, …`.
- Tabel `file_parts`: PK komposit `(fileId, partIndex)` + `chatId, messageId, remoteFileId,
  remoteUniqueId, plainOffset, plainSize, storedSize, uploadedAt` — posisi byte plaintext
  dipetakan ke part tanpa mengunduh part lain.
- **Manifest hidup di caption**: `RemoteFileManifest` adalah JSON berkunci pendek
  (`v,id,n,p,fid,m,s,h,e,ct,mt,pc,pi,po,ps`) — komentar kodenya eksplisit:
  *"the captions alone can rebuild the local database"* (lihat §8.2 untuk format persisnya).
- Snapshot indeks berkala: dokumen `teledrive.index.json.gz` (penanda `#teledrive-index`) dan
  state folder (penanda `#teledrive-folders`) di channel yang sama.

**Unduh / restore / pencarian**

- `DownloadFile(fileId, priority, offset, limit, synchronous=false)` — offset/limit dipakai
  untuk **streaming media dengan seek**: hanya part yang menampung posisi yang diunduh
  (lewat `PartedTelegramDataSource`).
- Rebuild database dari nol: `SearchChatMessages` memindai caption channel; `GetMessage`
  mengambil dokumen tunggal.

**Enkripsi** — lihat §9.1 (format `StreamCrypto`, yang terbaik di seluruh riset).

**Error / rate limit / resume**

- Semua pemanggilan yang membuat pesan melewati **satu pacer global** (FLOOD_WAIT se-akun),
  dibungkus `withRateLimitRetry`: retry khusus error rate-limit, jeda `retryAfterSeconds + 1`
  detik, sampai batas percobaan.
- Resume unggah: part yang sudah tercatat di `file_parts` **dilewati** saat upload ulang.
  Resume unduh: loop `DownloadFile` berkala. Pekerjaan latar: WorkManager
  (`BackupResumeWorker`, `TransferQueueWorker`).

**Batas tertulis**: README — *"No file size limit: a 20 GB video is one file in the app"*
(berkat part 512 MiB); enkripsi opsional AES-256-GCM untuk isi, nama, caption, pohon folder.

**Inti yang bisa dicuri**

1. Caption = database yang bisa dibangun ulang; DB lokal hanyalah cache.
2. Part 512 MiB + scratch sekali pakai + skip part tercatat = resume per part paling bersih.
3. Enkripsi frame 1 MiB dengan nonce turunan indeks + AAD pengikat urutan; frame berukuran
   tetap agar file terenkripsi tetap bisa di-seek.
4. Satu pacer global untuk semua operasi tulis.

### 3.2 TeleNest — hasibullakhan (Kotlin/Android, TDLib)

Catatan asal-usul: paketnya `com.awd.teledrive` dan awalan manifestnya `#TELEDRIVE_*` —
turunan/peniru konsep teledrive-nya khrj, bukan TeleDrive-nya Mahmud0808.

- **Multi-slot**: banyak klien TDLib hidup bersamaan (map `slotId → Client`) = multi-akun
  dalam satu aplikasi; api_id/api_hash per slot disimpan lewat SecureSettings.
- Tujuan default **Saved Messages**; folder bisa virtual (manifest) atau channel privat
  per workspace.
- Unggah selalu sebagai dokumen (`InputMessageDocument`) — komentar kodenya menegaskan:
  `InputMessagePhoto` mengompres foto dan membuang EXIF + nama asli.
- Ambang pecah `MAX_FILE_SIZE = 1950 MiB` (margin aman di bawah 2 GiB). Caption tiap part
  terbaca manusia: `[TD_SPLIT|ID:<groupId>|PART:<i>/<n>|NAME:<nama asli>]` (+ `[VF:<folderId>]`,
  `[OTIME:<timestamp asli>]`). Setelah part terakhir: **pesan master metadata** teks
  `#TELEDRIVE_FILE_DATA` + JSON `SplitFileMaster` berisi daftar `partMessageIds`.
- **Manifest awan = satu JSON besar** (`TeleDriveManifest`: virtualFolders, fileMappings,
  secureFolderChatIds, splitFileMasters, folderMetadataIds, fileMetadataIds, deletedFolderIds,
  trashedFileIds, …) disimpan sebagai **pesan teks di Saved Messages** berawalan
  `#TELEDRIVE_MANIFEST` (cadangan: `#TELEDRIVE_MANIFEST_BACKUP_`), dicari kembali via
  `SearchChatMessages`, di-cache di SharedPreferences, dan **digabung antar-perangkat dengan
  tombstone** (daftar ID terhapus + timestamp) — sinkronisasi multi-perangkat tanpa server.
- Enkripsi dua skema: file = AES-256-GCM streaming (`salt(16) ‖ IV(12) ‖ ciphertext`), kunci
  dari kata sandi via **PBKDF2-HMAC-SHA256 hanya 10.000 iterasi** (titik terlemah repo ini);
  kata sandi utama = **Argon2id** (t=2, m=64 MiB, p=1) dengan fallback SHA-256(salt‖password)
  bila library native gagal dimuat.
- Rate limit paling lemah di klasternya: FLOOD_WAIT hanya jadi pesan error di layar login;
  tidak ada retry/backoff di jalur unggah.
- **Kejujuran riset**: README mengklaim part dilengkapi "verification checksums", tetapi tidak
  ditemukan kode hash/checksum apa pun di jalur split/gabung — klaim tidak terbukti di kode.

**Inti yang bisa dicuri**: manifest JSON tunggal di Saved Messages (tanpa channel khusus);
caption tag yang bisa dibaca manusia; merge manifest dengan tombstone; multi-slot TDLib.

### 3.3 teledrive — khrj (Electron + TDLib via airgram)

- Library: binding JS `airgram` menjalankan binary native `libtdjson` yang dibundel per-OS.
  apiId/apiHash ter-hardcode di kode sumber repo ini (jangan tiru penempatannya).
- Folder lokal `TeleDriveSync` dipantau `chokidar`; event add/change masuk antrean serial
  ber-lock, diproses satu per satu. File diunggah **utuh sebagai satu dokumen** ke Saved
  Messages; file berubah → media pesan lama **diganti di tempat** via `editMessageMedia`.
  Chunking internal diserahkan ke TDLib.
- Indeks dua lapis:
  1. Caption tag: `#TeleDrive <path-relatif>` — path adalah kunci pencarian.
  2. **Master file JSON** `TeleDriveMaster.json` berisi
     `{_: "TeleDriveMaster", version, files: { "<path-relatif>": ["<sha256>", …] }}` —
     value adalah **array hash = riwayat versi** (terakhir = terkini). File master ini sendiri
     disimpan sebagai dokumen di Saved Messages bercaption `#TeleDriveMaster …` dan **di-edit
     di tempat** setiap perubahan; saat startup diunduh kembali bila salinan lokal hilang.
- Logika versi dari SHA-256 file utuh: hash == versi terakhir → skip; hash ada di riwayat lama
  → konflik (tawarkan timpa atau restore versi cloud); selain itu → versi baru.
- `syncAll`: rekonsiliasi dua arah (lokal hilang → unduh; hash lokal = versi lama → timpa
  dengan unduhan; sama → skip).
- Unduh per file: `searchChatMessages` query `"#TeleDrive " + path`, ambil pesan pertama,
  `downloadFile({fileId, priority: 32})`, tunggu event `updateFile` sampai
  `local.isDownloadingCompleted`, salin ke folder, `deleteFile` (bersihkan cache TDLib).
- Enkripsi konten: tidak ada. Error/rate-limit: nyaris tidak ada penanganan FloodWait.

**Inti yang bisa dicuri**: caption-hashtag sebagai indeks terdistribusi + file master JSON
yang ikut tinggal di cloud dan di-edit di tempat; array hash per file sebagai riwayat versi.

### 3.4 TeleSync — FujiwaraChoki (Python + Telethon) — spesimen pelajaran negatif

- MTProto userbot via **Telethon**; kredensial dari `config.json` (api_id, api_hash, nomor HP);
  sesi persisten di file `anon.session`.
- Pemecahan: hanya file **> 2.000.000.000 byte** yang dipecah, dibaca per **50 MB** menjadi
  file `*.chunk` di folder `temp/`. File ≤ 2 GB pun tetap disalin utuh jadi satu `.chunk`
  sebelum diunggah (selalu ada salinan temp sebesar file asli).
- Bookkeeping: SQLite lokal, tabel `file(id UUID, file_name, file_path, file_size,
  chunks TEXT, type, uploaded)`. **message_id/file_id Telegram TIDAK disimpan** — kolom
  `chunks` berisi path file chunk **lokal**.
- Penanda di Telegram murni caption terstruktur:
  `"<id>::::::<path-absolut>::::::<nomor-chunk>:::::file"` (unggah) — dan format pencarian
  unduhnya memakai pemisah yang **tidak konsisten** (5 titik dua). Restore: cari pesan per
  chunk via `get_messages("me", search=caption)`, `download_media`, append berurutan.
- **Tidak ada**: verifikasi hash/ukuran (restore bisa korup diam-diam), penanganan FloodWait,
  retry, resume. `remove_file` memindai semua pesan Saved Messages dengan kondisi yang praktis
  selalu benar → berpotensi menghapus pesan di luar target.

**Inti yang bisa dicuri**: bukti bahwa caption terstruktur + pencarian pesan bawaan Telegram
cukup untuk restore tanpa menyimpan message_id (indeks dibangun ulang dari sisi server);
sisanya adalah daftar "jangan tiru" yang berharga.

### 3.5 GramUp — rohittp0 (Python + Telethon)

Kejujuran cakupan: mesin CLI-nya berada di branch `cli` (tidak ikut ter-clone; branch `main`
sekarang berisi kerangka web FastAPI). Yang terverifikasi dari `main` + docs:

- **Login QR via WebSocket**: endpoint `/ws` memanggil `client.qr_login()`, mengirim `qr.url`
  ke browser, `qr.wait(10)` dengan `qr.recreate()` saat timeout — jauh lebih ramah daripada
  OTP ketik manual.
- Pemilihan chat tujuan lewat **pesan pemicu di dalam Telegram**: pengguna mengirim pesan ke
  chat mana pun, handler menyimpan `chat_id` chat itu. Menghapus kebutuhan menyalin chat_id.
- State ringkas satu file data: `(phone_number, chat_id, backup_folders)` di `~/.gramup/saved`;
  folder restore `~/Restored`.
- Konstanta `DATABASE_ENCRYPTION_KEY` berisi **kunci publik RSA** (terdeklarasi, tidak dipakai
  di `main`) — indikasi versi CLI mengenkripsi indeks lokalnya secara asimetris: kunci privat
  tidak perlu ada di mesin yang menulis indeks.
- Klaim README (niat desain, bukan kode terverifikasi di sini): batas file 1,5 GB; resumable
  backup & restore; struktur direktori dipertahankan via path relatif; unggah manual bisa
  diadopsi (kirim file ke chat → GramUp mencatatnya).

### 3.6 TelDrive — aravindrejidev (Dart/Flutter, Bot API)

- Murni **Bot API**: `TelegramService({botToken, channelId})`, panggilan ke
  `https://api.telegram.org/bot<token>/<method>`. Token di secure storage.
- **Validasi setup di muka** (pola yang sangat layak ditiru): `getMe` → `getChat` →
  `getChatMember` — status bot harus administrator/creator dengan `can_post_messages`,
  diverifikasi **sebelum** unggahan pertama, dengan pesan error yang menyebut persis izin
  yang kurang.
- Routing metode per format agar tampil native: jpg/png/webp ≤ 10 MB → `sendPhoto`; mp4 →
  `sendVideo` (+`supports_streaming`); mp3/m4a → `sendAudio`; sisanya → `sendDocument` dengan
  `disable_content_type_detection=true` agar byte utuh. Caption = nama file (dipotong 1.000
  karakter); `disable_notification=true`.
- **Tidak ada pemecahan file**: konstanta eksplisit `maxUploadBytes = 50 MB`,
  `maxRedownloadBytes = 20 MB`; file di atas 50 MB **ditolak sebelum diunggah** dan masuk
  daftar "tidak akan pernah bisa disinkronkan".
- Bookkeeping: SQLite lokal (sqflite) — tabel `files` (termasuk `telegram_file_id`,
  `telegram_unique_id`, `telegram_message_id`, `channel_id`, status) dan `sync_links`
  (pasangan folder lokal ↔ channel). **Tidak ada manifest di sisi Telegram** — caption hanya
  nama file; Bot API memang tidak bisa me-list isi channel.
- Unduh: `getFile` → URL → streaming ke `<tujuan>.part`, rename hanya bila selesai dan ukuran
  cocok persis. File > 20 MB **tidak bisa diunduh kembali lewat bot** — pesan errornya jujur:
  *"This file is larger than 20 MB, the most a bot can download. Open it from the Telegram app
  instead."*
- Error: 429 → tunggu `retry_after + 1` detik, maks 3 percobaan. Timeout panggilan 30 detik,
  unggahan 20 menit. Sinkron latar memakai **anggaran waktu 8 menit per run** (berhenti memulai
  unggahan baru, run berikutnya melanjutkan), jeda antar-unggah 1.200 ms, file yang baru
  berubah < 45 detik dilewati (settle time). Unduhan gagal menghapus `.part` — tidak ada
  resume byte-level.

### 3.7 taasant — vedantlavale (Go, CLI/TUI, Bot API tulisan tangan)

- Klien Bot API mentah di atas `net/http` (tanpa SDK). Kredensial token bot + chat_id di
  `config.json` (izin 0600, folder 0700) / env. Chat target ditemukan interaktif: long-poll
  `getUpdates` menunggu pesan pertama yang didengar bot.
- **Hukum taasant**: komentar kodenya — *"Telegram bots can only download files up to 20 MB,
  so parts stay below that"* → `defaultPartSize = 19 MiB`.
- Tiap part **dienkripsi dulu** (AES-GCM, nonce acak 12 byte ditempel di depan ciphertext),
  dikirim sebagai `sendDocument` bernama selalu `part.bin`, 3 worker paralel, urutan dijaga
  indeks. Manifest per objek: pesan dokumen terakhir berisi JSON terenkripsi
  `{"parts":[{"file_id","message_id"}, …]}`; **ID objek** yang dipegang pengguna =
  `"<message_id>:<file_id>"` milik pesan manifest itu.
- Indeks aplikasi murni lokal (`index.json`: `{name, id, size, uploaded}`) dan **tidak
  disinkronkan** — kehilangan indeks berarti objek hanya bisa dijangkau bila ID-nya dicatat
  di tempat lain.
- Unduh: ambil manifest → semua part diunduh **paralel** tetapi **ditulis berurutan** sesuai
  indeks, dekripsi per part. Hapus: `deleteMessage` per part + manifest (bot hanya bisa
  menghapus pesan < 48 jam).
- Enkripsi **wajib**: kunci AES-256 mentah 32 byte dari `taasant keygen` (64 karakter hex di
  config); tidak ada KDF — satu kunci simetris untuk semua objek, dicadangkan manual.
- Retry generik satu pintu (`call()`): maks 5 percobaan; retry untuk error jaringan, 429
  (menunggu `retry_after`), dan 5xx; token **disaring dari pesan error** agar tidak bocor ke
  log. Tidak ada resume: part gagal diulang utuh dari byte nol.

**Inti yang bisa dicuri**: ukuran part dari batas unduh; manifest sebagai objek terenkripsi
terakhir + ID `message_id:file_id`; retry satu pintu yang menghormati `retry_after` +
penyaringan token dari error; unduh paralel, tulis berurutan.


---

## 4. Bedah repo — klaster Drive & tujuan ganda

### 4.1 tg-bot-uploader-drive — Alirewa (Python, Pyrogram → Drive)

- **Bot berbicara MTProto (Pyrogram 2.0.106 + TgCrypto), bukan HTTP Bot API** — docstring-nya
  menegaskan alasannya: MTProto menangani file sampai 4 GB secara native, tidak perlu local
  Bot API server. Batas di kode: `MAX_FILE_SIZE_BYTES = 2 GiB`, dicek sebelum diproses.
  Pelajaran transport penting: **"bot" ≠ "Bot API"**.
- Alur: pengguna mengirim media → bot **mengunduh dulu ke disk server** (file temp) → unggah
  ke Drive → file temp selalu dihapus di blok `finally`. Telegram di sini pipa masuk, bukan
  penyimpanan; message_id/file_id tidak dicatat.
- Sisi Drive (library resmi `google-api-python-client`):
  - Scope tunggal minimal: `https://www.googleapis.com/auth/drive.file`.
  - **Dua mode auth**: (1) OAuth2 per-pengguna — `Flow.from_client_config` tipe web,
    `access_type=offline, prompt=consent`, callback via server aiohttp bawaan (port 8080) atau
    tempel kode manual; token akses+refresh disimpan di tabel `users` (di repo ini plaintext —
    jangan tiru penyimpanannya); refresh otomatis saat kedaluwarsa. (2) Service Account
    bersama + satu folder tetap + kuota lunak (default 13 GB).
  - **Resumable upload resmi**: `MediaFileUpload(file_path, resumable=True, chunksize=5 MB)`
    lalu loop `request.next_chunk()` sampai tuntas, callback progres per chunk; I/O blocking
    jalan di thread-pool executor agar event loop async bebas.
  - Setelah unggah, file **langsung dibuat publik** (`permissions().create(type=anyone,
    role=reader)`) demi share link — keputusan desain yang harus disadari: **tidak ada mode
    privat di jalur ini; jangan tiru untuk backup pribadi.**
- Bookkeeping: SQLAlchemy + SQLite. Tabel `users` (id Telegram, token OAuth, penghitung harian,
  status premium/referral), `uploads` (`user_id, service_account_id, gdrive_file_id, file_name,
  file_size_bytes, share_link, is_personal_drive, is_deleted, uploaded_at`), `service_accounts`,
  `rate_limits`, `bot_settings`.
- Arah sebaliknya (Drive → Telegram): pengguna mengirim link Drive publik → ID diekstrak
  regex → diunduh pakai **gdown** (menangani halaman konfirmasi) → dikirim balik sebagai
  dokumen Telegram.
- Rate limit buatan sendiri: maks 3 unggahan per 60 detik per pengguna, cooldown 30 detik,
  kuota harian 2 GiB, satu unggahan konkuren per pengguna. Scheduler: file bot-drive dihapus
  dari Drive setelah 48 jam; file temp yatim > 2 jam disapu tiap 30 menit.
- Kekurangan: **tanpa retry/backoff** — kegagalan di tengah `next_chunk()` menggagalkan seluruh
  unggahan (sesi resumable tidak dipersistkan); tidak ada penanganan FloodWait khusus.
  Enkripsi konten: tidak ada.

### 4.2 gdbackup — dominusmmp (PHP, MySQL → lokal/Drive/Telegram)

- **Auth Drive manual**: OAuth2 authorization-code via cURL mentah ke
  `https://oauth2.googleapis.com/token`, tanpa library Google. URL otorisasi dibangun dengan
  scope **Drive penuh** (`auth/drive` — berlebihan; contoh buruk), `access_type=offline`,
  `prompt=consent`.
- Trik bagus: refresh token disimpan di file `.refresh-token.php` — header PHP penjaga
  (`<?php defined('GDBPATH') || die('forbidden'); // [timestamp]`) + blob terenkripsi
  **AES-256-CBC** (kunci dari PBKDF2-SHA256 100.000 iterasi) + `chmod 0600`. Token aman dari
  web-access sekaligus dari mata. Access token hanya hidup di memori per proses.
- Unggah Drive: labelnya `uploadType=resumable` **tapi** seluruh file dimuat ke RAM
  (`file_get_contents`) lalu dikirim **satu PUT utuh**, semua request cURL di-timeout 30 detik —
  file besar praktis mati. **Anti-pola utama repo ini.**
- Struktur folder: folder root config → folder prefix (dibuat idempoten: cari dulu, buat bila
  belum ada) → subfolder tanggal `YYYY-MM-DD` (mode gd-upload) → subfolder jam `HH.mm.ss`
  (mode gd-stream). Penamaan dump `{dbName}.{tuid}.sql.gz` atau satu ZIP `{prefix}.{tuid}.zip`,
  `tuid = uniqid(tanggal-jam)`.
- Dump MySQL ditulis generator PDO murni (bukan mysqldump), baris per 1.000, hasil di-`gzencode`
  level 9 di memori. Mode `gd-stream` = dump langsung unggah tanpa file lokal; `gd-upload` =
  ZIP dulu.
- Telegram: Bot API `sendDocument` via `CURLFile`, **tanpa caption**, yang dikembalikan hanya
  `message_id`. **Tidak ada pemecahan file**: batas bisa dikonfigurasi (default 50.000.000 byte,
  maks diterima 2 GB); melebihi batas = exception. Anti-flood sederhana: jeda minimum 0,1 detik
  antar request.
- Inkremental: tidak ada (selalu full dump). Retensi hanya berbasis umur (`retentionDays`)
  di Drive (query `createdTime < batas`) dan lokal; **di Telegram tidak ada retensi** —
  README-nya terang-terangan menyebut pembersihan Telegram harus manual.
- Jadwal: cron eksternal memanggil `run.php` yang dilindungi `cronjobKey`. Error: **nol retry**
  di semua jalur. Restore: `restore.php` hanya bisa dari file `.sql(.gz)` **lokal** — tidak bisa
  menarik dari Drive/Telegram.

### 4.3 db2cloud-backup — slashramin (Python + Bash, 4 file)

- Pipeline minimal yang benar: `mysqldump | gzip > db-backup-<YYYY-MM-DD_HH-MM-SS>.sql.gz`,
  lalu dua pengunggah independen di-gate env `UPLOAD_GDRIVE` / `UPLOAD_TELEGRAM`.
- Drive: `InstalledAppFlow.from_client_secrets_file('credentials.json')`, scope tersempit
  `drive.file` (pelajaran keamanan gratis: jangan pernah minta scope penuh bila aplikasimu
  hanya menyentuh file buatannya). Token disimpan **plaintext** di `token.json` dan **tidak ada
  kode refresh** — token kedaluwarsa = unggahan gagal sampai OAuth diulang manual.
- `MediaFileUpload(resumable=True)` + `files().create(fields='id')`; chunk murni default
  library Python (**256 KB**). **Tidak ada folder sama sekali** — semua backup menumpuk di root.
- Telegram: satu baris curl `sendDocument` dengan caption `"Database backup: $FILE"` (caption
  terkirim dua kali — di query string dan form field). Tidak ada cek ukuran (dump > 50 MB akan
  ditolak Telegram dan `curl -s` menyembunyikan errornya), tidak ada pemecahan, tidak ada retry.
- Inkremental/hash/enkripsi/retensi: tidak ada. File dump lokal tidak pernah dihapus.
  Bookkeeping = stdout. `set -e`: satu kegagalan membatalkan rantai.

**Inti yang bisa dicuri**: kerangka satu dump → dua pengunggah independen ber-flag adalah
kerangka minimal yang benar; sisanya adalah daftar anti-pola yang harus ditambah untuk naik kelas.

### 4.4 docker-volume-backup — offen (Go)

- **Interface `StorageBackend` (dua method: Copy & Prune)** yang seragam untuk S3, Google Drive,
  WebDAV, Dropbox, Azure, SSH, lokal — pola adapter transport yang persis dibutuhkan desain
  multi-tujuan.
- Backend Drive-nya: **service account murni + impersonasi wajib** (`config.Subject` harus diisi
  dari `GOOGLE_DRIVE_IMPERSONATE_SUBJECT`; kosong = error keras), kredensial JSON lewat env,
  mendukung Shared Drive. Artinya dirancang untuk **Google Workspace dengan domain-wide
  delegation — tidak bisa dipakai dengan akun Gmail pribadi biasa.** Jangan jadikan pola auth
  untuk DuoBackup.
- Unggah: `Files.Create(...).Media(f).Do()`, `parents` = folder ID atau `"root"`. **Tidak ada
  pemanggilan `ChunkSize(...)`** — ukuran potongan murni default client Go; tidak ada resume/
  retry tulisan tangan di backend Drive.
- Yang dibackup: satu arsip tar per run, nama dari template strftime (default
  `backup-%Y-%m-%dT%H-%M-%S.{{.Extension}}`), kompresi gzip/pgzip/zstd. Selalu snapshot penuh,
  tidak inkremental.
- **Enkripsi arsip utuh sebelum unggah**, tepat satu metode wajib dipilih (lebih dari satu =
  error): GPG simetris (passphrase), GPG asimetris (keyring publik), age simetris (scrypt),
  age asimetris (recipient X25519 / kunci SSH). Pelajaran: enkripsi hidup di **pipeline**,
  bukan di adapter — berlaku seragam untuk semua backend.
- Prune: `BACKUP_RETENTION_DAYS` + leeway; di Drive: list dengan query
  `name contains '<prefix>' and trashed = false` (+ `'<folderId>' in parents`), paginasi manual
  via `NextPageToken`, saring awalan nama persis, parse `createdTime` RFC3339, hapus yang lebih
  tua dari deadline. **Backend tidak perlu indeks lokal apa pun untuk rotasi.**
- Jadwal: cron bawaan (`robfig/cron/v3`) selain mode sekali jalan. Telegram di repo ini
  **hanya kanal notifikasi** (lewat shoutrrr) — bukan tujuan backup.

### 4.5 cloudsync — HolgerHees (Java, Drive API v2 — tua, idenya mahal)

- Auth dua jalur: (1) installed-app OAuth dengan redirect OOB klasik
  (`urn:ietf:wg:oauth:2.0:oob`), scope `DriveScopes.DRIVE`, kode ditempel manual; token
  diserialisasi ke file JSON (default `./.cloudsync.token`, plaintext); refresh otomatis saat
  sisa umur token ≤ 600 detik. (2) Service account file kunci **P12** + impersonasi.
- Unggah via MediaHttpUploader: chunk = `MINIMUM_CHUNK_SIZE × 4` = **1 MB** — **tetapi** hanya
  aktif bila progress ditampilkan dan file > 1 chunk; di luar itu `setDirectUploadEnabled(true)`
  alias unggah sekali-tembak non-resumable. Tiru dengan sadar, bukan tiru buta.
- Struktur: cermin pohon lokal di bawah `GOOGLE_DRIVE_DIR/<namaBackup>/…`; folder dibuat tingkat
  demi tingkat dari root dan di-cache. **Versioning**: saat file berubah, versi lama **disalin
  dulu ke folder histori** `<namaBackup> <yyyy.MM.dd_HH.mm.ss>` dengan struktur relatif yang sama,
  baru file utama ditimpa; penghapusan pun memindahkan ke histori bila histori aktif.
- **Deteksi berubah berjenjang**: `filesize` → `creationtime` → `modifytime` → checksum
  (checksum = **MD5 hex** isi file). Restore memverifikasi ulang MD5 dan menolak bila beda.
- **Metadata per item disimpan terenkripsi di dalam properti file Drive-nya sendiri**: metadata
  adalah array berversi `[versi, tipe, filesize, creationtime, modifytime, accesstime, checksum,
  atribut POSIX (group/user/permissions)…]`, dienkripsi, lalu dipecah **100 karakter per properti**
  bernama `metadata0..N` (visibility PRIVATE) + `metadataParts` sebagai penanda jumlah.
  Indeks restore menempel pada setiap file — tidak ada database pusat.
- Cache lokal CSV `.cloudsync_{name}.cache` (struktur pohon remote) + lock/pid file + mode
  `--nocache` untuk rebuild.
- **Enkripsi OpenPGP simetris** (BouncyCastle) dari satu passphrase: cipher **AES-256**, di
  dalamnya ada kompresi ZIP, format biner non-armor. Yang dienkripsi bukan cuma isi —
  **nama file/folder dan string metadata juga** (hasil PGP di-Base64, `/` diganti `_` agar aman
  jadi nama). File ≥ 128 MB dienkripsi lewat file temporer agar hemat memori.
- Error/retry: semua operasi remote dibungkus loop retry (parameter `retries` + `waitretry`);
  HTTP 404 = fatal ("coba --nocache"). Kegagalan unggah ditangani khas: dicari sampai **12×
  dengan jeda 5 detik** apakah file sebenarnya sudah terunggah sebagian di Drive — kalau
  ketemu, dilanjutkan sebagai *update*, bukan unggah ulang (cikal-bakal resume tanpa session API).
- Retensi = jumlah folder histori (`HISTORY`, default 0 = mati); `cleanHistory` mengurutkan
  folder histori dari `createdDate` terbaru dan menghapus sisanya. Penjadwal: tidak ada
  (cron/systemd eksternal).

### 4.6 hassio-google-drive-backup — sabeechen (Python) — resumable Drive terbaik

- Auth: OAuth2 authorization-code, scope `drive.file`, `access_type=offline` + `prompt=consent`.
  Arsitektur tukar token unik: pengguna default **tidak membawa client secret** — penukaran &
  refresh token dimediasi **server token milik proyek** (daftar URL failover, penanganan
  `invalid_grant`/401/503 dibedakan); pengguna berkredensial sendiri refresh langsung ke Google.
- Kredensial disimpan sebagai satu JSON (`client_id, client_secret?, refresh_token,
  access_token, token_expiry RFC3339`). Kesegaran token dicek dari `token_expiry`; error
  kredensial-kedaluwarsa dari request mana pun memicu refresh lalu request diulang.
- **Resumable upload tulisan tangan (raw REST Drive v3 via aiohttp, tanpa library Google)**:
  - Sesi dibuka `POST /upload/drive/v3/files/?uploadType=resumable&supportsAllDrives=true`
    dengan header `X-Upload-Content-Length` & `X-Upload-Content-Type`; URL sesi dari header
    `Location`.
  - Satuan dasar chunk 256 KB (kelipatan wajib Google). Ukuran chunk **adaptif**: mulai 1×256 KB,
    chunk berikutnya ditargetkan selesai dalam **±10 detik** berdasarkan kecepatan terukur,
    dibatasi `MAXIMUM_UPLOAD_CHUNK_BYTES`. Ada pembatas kecepatan opsional berbasis token bucket
    dalam satuan chunk 256 KB.
  - **Resume lintas percobaan**: URL sesi + metadata disimpan; percobaan berikutnya menanyakan
    posisi server dengan `Content-Range: bytes */<total>` — jawaban **308 + header `Range`**
    menentukan posisi lanjut. Sesi dianggap basi setelah **6 hari**; maks **100 kegagalan sesi
    berturut** (penghitung **reset setiap chunk berhasil**); error 4xx membatalkan sesi;
    404/410 → mulai dari nol.
  - Retry per request: backoff eksponensial basis **2 detik, faktor 2, maks 5 percobaan**,
    plafon tunggu 1 jam. 403/429 dikenali eksplisit sebagai rate limit.
- Struktur Drive datar: satu folder (`Home Assistant Backups`, dicari/dibuat & di-cache 30
  detik), semua backup file `.tar` langsung di dalamnya.
- **Bookkeeping = `appProperties`**: tiap file membawa `slug, date, tipe, versi, protected,
  retained, note`. Sebuah file hanya diakui sebagai backup sah bila `appProperties` memuat
  **slug dan tanggal**. Nilai properti dipotong aman (kunci+nilai < 120 byte UTF-8).
  `createdTime`/`modifiedTime` file di Drive **dipalsukan ke tanggal backup asli** agar urutan
  umur konsisten walau diunggah ulang. Pemetaan slug yang sama mencocokkan backup di lokal ↔
  Drive ↔ tujuan lain — tidak ada database indeks terpisah.
- Inkremental level file: tidak ada (setiap backup adalah tar penuh); yang ada rekonsiliasi
  daftar antar sumber berdasarkan slug+tanggal.
- Retensi ganda: skema hitung sederhana (default simpan 4 di Drive) **atau** skema
  **generasional** hari/minggu/bulan/tahun dengan hari jangkar yang bisa diatur.
- Error folder hilang/izin dicabut: buang cache folder, buat ulang, coba sekali lagi.

**Inti yang bisa dicuri**: resumable yang ditulis tangan dengan benar adalah referensi
implementasi terbaik dari seluruh riset; mediasi refresh token agar client secret tidak
menyebar; `appProperties` sebagai satu-satunya indeks; retensi generasional.

### 4.7 Tabel ringkas lintas repo Drive

| Aspek | gdbackup | db2cloud | docker-vol-backup | cloudsync | hassio |
|---|---|---|---|---|---|
| Auth Drive | OAuth manual, scope penuh | OAuth library, `drive.file` | SA + impersonasi wajib | OAuth OOB / SA P12 | OAuth `drive.file`, refresh dimediasi server |
| Resumable | nama saja, 1 PUT utuh | library, chunk 256 KB | default library Go | 1 MB, bersyarat | **manual, adaptif, resume lintas sesi** |
| Telegram | bot, tanpa pecah/caption | bot, caption nama file | hanya notifikasi | — | — |
| Enkripsi payload | tidak | tidak | GPG/age arsip utuh | **OpenPGP AES-256, termasuk nama** | tidak (andalkan sumber) |
| Indeks restore | tidak ada | tidak ada | nama file + createdTime | properti Drive per 100 char + cache CSV | **appProperties slug+date** |
| Retensi | umur (Drive/lokal) | tidak ada | umur + awalan nama | folder histori N | jumlah / generasional |

---

## 5. Bedah repo — referensi inti (restic & Android-DataBackup)

### 5.1 restic — standar emas arsitektur backup (Go)

**Content-Defined Chunking (CDC)**

- **Rabin Fingerprint** di atas jendela geser **64 byte**; titik potong ditentukan dari konten,
  jadi sisipan/hapus byte di tengah file hanya mengubah blob di sekitarnya.
- Polynomial irreducible **acak, digenerate sekali saat repo dibuat** dan disimpan di `config`
  — setiap repo berbeda (anti *watermark attack*).
- Ukuran chunk: **min 512 KiB, maks 8 MiB, target rata-rata 1 MiB**; file < 512 KiB tidak dipecah.
- Optimasi: chunk 512 KiB yang seluruhnya nol di-hash dari cache tanpa dibaca ulang (file sparse).

**Format repository**

- **Blob** = satuan data hasil chunking (atau *tree blob* berisi JSON struktur direktori).
  **ID blob = SHA-256 atas plaintext-nya** (sebelum kompresi & enkripsi). Semua referensi —
  snapshot, tree, index, nama file — adalah hash ini.
- **Pack file**: banyak blob terenkripsi digabung dalam satu file (ukuran min 4 MiB, **default
  16 MiB**, maks 128 MiB; pack data dan pack tree selalu terpisah). Struktur:
  `EncryptedBlob1 ‖ … ‖ EncryptedBlobN ‖ EncryptedHeader ‖ HeaderLength(4 byte LE)` — header
  sengaja di **akhir** agar blob bisa ditulis streaming.
- Isi header pack setelah dekripsi, per blob: `Type(1 byte) ‖ Length(4 byte LE) ‖ ID(32 byte)`
  (+ `UncompressedLength` bila terkompresi zstd, format repo v2).
- **Index**: file JSON terenkripsi `{supersedes: [id…], packs: [{id, blobs: [{id, type, offset,
  length, uncompressed_length?}]}]}` — peta blob → (pack, offset, panjang). Satu file index
  < 8 MiB. Index **bisa dibangun ulang hanya dari header semua pack** bila hilang.
- **Snapshot**: dokumen JSON `{time, parent, tree, paths, hostname, username, uid, gid, excludes,
  tags, original, summary}`. Tree rekursif: tiap Node membawa metadata lengkap (mode,
  mtime/atime/ctime, uid/gid, xattr); untuk file, `content` = **daftar ID blob berurutan**
  penyusun file itu.
- Layout backend: `config`, `data/` (pack, nama = hash), `index/`, `keys/`, `locks/`, `snapshots/`.

**Enkripsi**

- **AES-256-CTR + Poly1305-AES** (konstruksi manual yang mengimplementasikan interface AEAD):
  kunci enkripsi 32 byte; kunci MAC 32 byte berbentuk `k(16, AES-128) ‖ r(16, Poly1305)`;
  IV/nonce acak 16 byte per blob; MAC 16 byte → **overhead tetap 32 byte per blob**;
  format simpan `nonce ‖ ciphertext ‖ MAC`. MAC diverifikasi **sebelum** dekripsi.
- KDF: **scrypt**, output 64 byte (32 pertama = kunci enkripsi, 32 sisanya = kunci MAC);
  **salt 64 byte** acak per file kunci; parameter N/r/p **dikalibrasi per perangkat** saat kunci
  dibuat: target waktu ±500 ms dengan batas RAM 60 MB (contoh: N=65536, r=8, p=1).
- **Kunci dua lapis**: file kunci JSON `{created, username, hostname, kdf, N, r, p, salt, data}`
  — `data` adalah **master key acak** (JSON `{mac:{k,r}, encrypt}`) yang dienkripsi dengan kunci
  turunan password. Ganti/tambah password = tulis file kunci baru; data tidak dienkripsi ulang.

**Deduplikasi & verifikasi**

- Dedup global berbasis ID: hash plaintext dicek ke index in-memory sebelum menulis — blob yang
  sudah dikenal **tidak ditulis lagi**, berlaku lintas file, lintas snapshot, dalam satu run.
- Verifikasi berlapis saat baca/restore: (1) MAC Poly1305 saat dekripsi blob; (2) setelah
  dekripsi (+dekompresi), **SHA-256 plaintext dihitung ulang dan harus sama dengan ID blob yang
  diminta**. Verifikasi yang sama juga berjalan saat *menulis*. Di atasnya ada perintah audit
  penuh (`check`) yang membaca ulang struktur & isi repo.

**Abstraksi backend** — satu interface kecil yang diimplementasikan semua storage (local, S3,
SFTP, REST, Azure, GCS, B2, Swift, rclone):

```
Properties()  Hasher()
Save(ctx, Handle, RewindReader)
Load(ctx, Handle, length, offset, fn)   // baca RANGE dengan offset — kunci mengambil
                                        // satu blob dari tengah pack tanpa unduh utuh
Stat  List(ctx, FileType, fn)  Remove  Close  Delete  Warmup
+ klasifikasi error: IsNotExist / IsPermanentError
```

Objek diidentifikasi `Handle{Type, Name}`; logika repo di atasnya tidak tahu-menahu soal transport.

**Inti yang bisa dicuri**

1. ID = SHA-256 plaintext untuk semua objek — dedup, verifikasi, penamaan storage gratis.
2. Pack dengan header terenkripsi di ekor: tulis streaming; index bisa direkonstruksi dari
   header pack saja. Di Telegram, padanannya: pesan = pack, pesan manifest penutup = headernya.
3. Kunci dua lapis + scrypt terkalibrasi perangkat.
4. Index sebagai JSON terpisah dengan skema `{pack → [blob+offset+length]}` dan `supersedes`.
5. Interface backend minimalis dengan **Load ber-offset**.

### 5.2 Android-DataBackup — XayahSuSuSu (Kotlin) — sisi HP

**Mekanisme akses: root murni**

- Memakai **libsu**: aplikasi meng-bind **RootService** — service AIDL yang berjalan sebagai
  **proses root (su) terpisah**; semua operasi file/shell dieksekusi di proses itu atas nama
  root. Tidak ada Shizuku/SAF/MediaStore untuk data aplikasi.
- AndroidManifest aplikasi utama mendeklarasikan **nol permission** — semua akses lewat proses
  root, bukan API Android. (Permission hanya di modul library: INTERNET; POST_NOTIFICATIONS +
  FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC.)
- Path privat aplikasi lain dibaca langsung:
  `user → /data/user/<userId>/<pkg>`; `user_de → /data/user_de/<userId>/<pkg>`;
  `data → /data/media/<userId>/Android/data/<pkg>`; `obb → …/Android/obb/<pkg>`;
  `media → …/Android/media/<pkg>`.

**Yang dibackup & formatnya**

- Per aplikasi, per bagian = **satu arsip tar**, opsional terkompresi (TAR polos / ZSTD / LZ4;
  suffix `.tar` / `.tar.zst` / `.tar.lz4`). Eksekusi shell murni lewat root:
  `tar --totals -cpf - <src> | zstd -r -T0 --ultra … > <dst>`.
- Bagian per aplikasi (`DataType`): **apk** (base + split dari direktori sumber paket),
  **user**, **user_de**, **data**, **obb**, **media**, plus **config**.
- Struktur hasil: `<dirBackup>/apps/<packageName>/user_<userId>[@<preserveId>]/` berisi
  `apk.tar.zst`, `user.tar.zst`, dst., plus **`package_restore_config.json`** — manifest per
  aplikasi berisi seluruh entitas paket termasuk **daftar runtime permission + app-op** dan
  **SSAID per aplikasi**. Ada juga mode backup "media": folder bebas pilihan pengguna di-tar
  ke subfolder `files/`.
- **Eksklusi bawaan** saat tar — untuk user/user_de: `cache`, `lib`, `code_cache`, `no_backup`,
  `.ota`; untuk data/obb/media: `cache` dan `Backup_*`.
- Setelah arsip ditulis, arsip **diuji baca** (`testArchive`) dan ukuran dicatat; pada backup
  berikutnya bagian yang ukuran sumbernya **tidak berubah di-skip** ("Data has not changed") —
  inkremental kasar berbasis ukuran, bukan hash.
- Tujuan simpan: lokal, atau cloud buatan sendiri via klien **FTP/SFTP/SMB/WebDAV** —
  arsip diunggah apa adanya per file.

**Cara restore (berurutan — urutannya adalah pelajarannya)**

1. **APK dulu**: ekstrak arsip apk ke folder temp, instal via install session root (`Pm.install`).
2. **Data**: catat konteks SELinux asli path tujuan → ekstrak tar langsung ke path asal (opsi
   `--recursive-unlink` untuk membersihkan sisa) → **perbaiki kepemilikan** `chown(uid, gid)` →
   **pulihkan konteks SELinux** `chcon` dengan konteks asli (fallback: konteks parent dengan
   `system_data_file` diganti `app_data_file`).
3. **Permission**: runtime permission dari config di-grant ulang satu per satu.
4. **SSAID**: ditulis kembali per aplikasi agar identitas per-app tidak berubah.

Melewatkan chown/chcon adalah penyebab utama restore "berhasil tapi aplikasi rusak".

**Catatan arsitektur**: versi penerusnya (`source-next`) sedang bermigrasi ke **dua backend** —
`archive` (tar seperti di atas) dan **`rustic`**: implementasi restic-compatible berbahasa Rust
yang di-vendor dan dipanggil via JNI. Dua repo referensi ini secara harfiah bertemu di sana.

**Inti yang bisa dicuri**

1. Pola RootService libsu: service AIDL di proses root terpisah sebagai satu-satunya pintu
   operasi privilegi; UI tetap proses biasa.
2. Satu tar per (aplikasi × bagian) + satu JSON config per aplikasi — format yang bisa
   dijelajahi manusia dengan alat standar.
3. Backup bukan cuma file: permission, app-op, dan SSAID ikut disimpan & dipulihkan — inilah
   yang membuat aplikasi hasil restore "tidak merasa pindah rumah".
4. Restore = instal APK → ekstrak → chown → chcon.
5. Daftar eksklusi per kategori memangkas ukuran backup secara besar tanpa kehilangan data penting.


---

## 6. Referensi API Telegram

Ada **tiga** permukaan API yang relevan. Memahami bedanya adalah separuh proyek ini.

| Permukaan | Protokol | Identitas | Batas file | Dipakai oleh |
|---|---|---|---|---|
| **Bot API** | HTTPS `api.telegram.org/bot<token>/<method>` | bot (token BotFather) | 50 MB naik, 20 MB turun | TelDrive Flutter, taasant, gdbackup, db2cloud |
| **Bot API server lokal** | HTTPS ke server Bot API self-hosted (C++, open-source) | bot | 2.000 MB naik, unduh tanpa batas | (opsi di tg-bot-uploader-drive) |
| **MTProto** (TDLib / Telethon / Pyrogram) | protokol biner Telegram | akun pengguna **atau** bot | 2 GB / 4 GB per file | TeleDrive, TeleNest, teledrive, TeleSync, GramUp, tg-bot |

### 6.1 Bot API — method yang relevan

Base: `https://api.telegram.org/bot<TOKEN>/<METHOD>`. Respons selalu JSON
`{"ok": true, "result": …}` atau `{"ok": false, "error_code": …, "description": …,
"parameters": {"retry_after": …}}`.

| Method | Guna | Catatan atomik |
|---|---|---|
| `getMe` | uji token & identitas bot | langkah 1 validasi setup |
| `getChat` | info chat/channel tujuan | langkah 2 validasi setup |
| `getChatMember` | cek status bot di channel | harus `administrator`/`creator` + `can_post_messages` untuk bisa posting |
| `sendDocument` | kirim file sebagai dokumen | multipart/form-data; param: `chat_id`, `document` (file / URL / file_id), `caption` (≤1.024), `disable_notification`, `disable_content_type_detection=true` agar byte tidak diutak-atik. Mengembalikan `Message` berisi `message_id` dan `document.file_id` — **simpan keduanya** |
| `sendPhoto` / `sendVideo` / `sendAudio` | kirim media yang tampil native | foto direkompres bila sebagai photo; dokumen menjaga byte utuh. Batas multipart: foto 10 MB, lain-lain 50 MB |
| `getFile` | dapatkan `file_path` dari `file_id` | file > 20 MB ditolak untuk bot di server publik |
| unduh file | `GET https://api.telegram.org/file/bot<TOKEN>/<file_path>` | — |
| `getUpdates` | long-polling pesan masuk | dipakai taasant untuk *menemukan* chat_id secara interaktif |
| `deleteMessage` | hapus pesan | bot hanya bisa menghapus pesan < 48 jam |
| `editMessageMedia` | ganti media pesan yang sudah ada | dipakai teledrive (khrj) untuk memperbarui file/master JSON **di tempat** |

Respons `Message` yang penting untuk bookkeeping: `message_id`, `chat.id`, `document.file_id`,
`document.file_unique_id`, `document.file_size`, `caption`.

**Penanganan error Bot API yang benar** (gabungan pola TelDrive & taasant):

```
429 → baca parameters.retry_after → tidur (retry_after + 1) detik → ulangi (maks 3–5 kali)
5xx / jaringan → backoff eksponensial → ulangi
4xx lain → gagal permanen, jangan retry
Selalu saring token bot dari pesan error/log (URL mengandung token!)
```

### 6.2 MTProto lewat TDLib — method yang relevan

TDLib adalah klien resmi (C++) dengan binding JSON/Android (`org.drinkless.tdlib.Client` atau
wrapper `tdlibx`). Semua pemanggilan berbentuk objek `TdApi.*`; hasil & update datang sebagai
objek balasan/event. Alur yang dipakai repo-repo yang dibedah:

**Autentikasi (state machine `UpdateAuthorizationState`)**

```
SetTdlibParameters { apiId, apiHash, databaseDirectory, filesDirectory,
                     databaseEncryptionKey, useFileDatabase=true,
                     useChatInfoDatabase=true, useMessageDatabase=true,
                     systemLanguageCode, deviceModel, applicationVersion }
  → AuthorizationStateWaitPhoneNumber
SetAuthenticationPhoneNumber { phoneNumber }
  → AuthorizationStateWaitCode
CheckAuthenticationCode { code }
  → (bila 2FA) AuthorizationStateWaitPassword
CheckAuthenticationPassword { password }
  → AuthorizationStateReady
```

Sesi persisten disimpan TDLib sendiri di `databaseDirectory` (terenkripsi bila
`databaseEncryptionKey` diisi). **Jangan pernah menulis ulang protokol MTProto sendiri** —
memakai TDLib berarti chunking per part 512 KiB, resume unduhan, dan penanganan koneksi
diwarisi gratis.

**Penyimpanan & pencarian**

| Method TDLib | Guna |
|---|---|
| `CreateNewSupergroupChat` | membuat channel gudang (isChannel=true) |
| `SearchChatsOnServer` | menemukan kembali channel gudang dari penanda di judul/deskripsi |
| `SendMessage { chatId, inputMessageContent: InputMessageDocument { document: InputFileLocal(path), caption: FormattedText } }` | unggah satu file/part |
| `EditMessageMedia` | mengganti media pesan (update file / master JSON di tempat) |
| `SearchChatMessages { chatId, query, fromMessageId, limit }` | memindai caption untuk rebuild indeks / mencari file |
| `GetMessage` | mengambil satu dokumen dari message_id |
| `DownloadFile { fileId, priority, offset, limit, synchronous }` | unduh; **offset/limit = unduh sebagian (seek/streaming)**; progres lewat `UpdateFile` sampai `local.isDownloadingCompleted` |
| `DeleteMessages` | menghapus pesan part (akun sendiri tidak kena batas 48 jam bot) |

Catatan penting dari TeleDrive: `remoteFileId`/`remoteUniqueId` yang dikembalikan TDLib untuk
tiap dokumen **wajib** disimpan — itulah alamat stabil file di sisi Telegram selain `message_id`.

**FLOOD_WAIT di MTProto**: error berkode 420 dengan pesan `FLOOD_WAIT_<detik>`. Pola TeleDrive:
satu **pacer global** mengantrekan semua operasi yang membuat pesan, dan pembungkus retry
membaca detik dari error lalu menunggu `detik + 1`. Jangan membuat banyak klien paralel untuk
"mempercepat" — batasnya per akun, paralel hanya mempercepat kena FLOOD_WAIT.

### 6.3 MTProto lewat library lain (ringkas)

- **Telethon (Python)**: `TelegramClient(session_path, api_id, api_hash)`, `client.start(phone=…)`
  (OTP interaktif pertama kali), `client.send_file("me", path, caption=…)`,
  `client.get_messages(entity, search=…)`, `message.download_media(path)`. Sesi = file
  `*.session` (SQLite) — **perlakukan seperti password**.
- **Pyrogram (Python)**: `Client(name, api_id, api_hash, bot_token=…)` bisa login **sebagai bot**
  via MTProto — menembus batas 50 MB Bot API tanpa local server (batas kode tg-bot: 2 GiB).
  `message.download(...)`, `client.send_document(...)`.
- **airgram (JS)**: binding TDLib untuk Electron/Node (`new Airgram({apiId, apiHash, …})`,
  `client.api.sendMessage(...)`, update `updateAuthorizationState` & `updateFile`).

### 6.4 Cara mendapatkan api_id & api_hash

Pengguna membuat aplikasi di `my.telegram.org` → API development tools → dapat `api_id`
(numerik) + `api_hash` (32 hex). Keduanya **kredensial**: simpan terenkripsi di perangkat
(DataStore terenkripsi / Keystore), jangan pernah di-commit, jangan hardcode milik orang lain
(beberapa repo contoh meng-hardcode miliknya — jangan tiru dan jangan pakai).

---

## 7. Referensi API Google Drive & OAuth

### 7.1 OAuth 2.0 — alur & endpoint

Endpoint resmi:

```
Otorisasi : https://accounts.google.com/o/oauth2/v2/auth
Token     : https://oauth2.googleapis.com/token
Revoke    : https://oauth2.googleapis.com/revoke
```

Parameter otorisasi yang benar untuk aplikasi backup pribadi:

```
scope=https://www.googleapis.com/auth/drive.file   ← SATU-SATUNYA scope yang diminta
access_type=offline          ← agar dapat refresh_token
prompt=consent               ← memastikan refresh_token diterbitkan (terutama saat re-auth)
response_type=code
redirect_uri=<loopback / custom scheme di Android; https untuk web>
```

Tukar kode → token:

```http
POST https://oauth2.googleapis.com/token
Content-Type: application/x-www-form-urlencoded

code=<code>&client_id=<id>&client_secret=<secret>&redirect_uri=<uri>&grant_type=authorization_code
→ { "access_token": "…", "refresh_token": "…", "expires_in": 3599, "token_type": "Bearer" }
```

Refresh (access token berumur ±1 jam):

```http
POST https://oauth2.googleapis.com/token
grant_type=refresh_token&refresh_token=<rt>&client_id=<id>&client_secret=<secret>
```

Aturan main yang terbukti dari repo:

- **Scope `drive.file`** = aplikasi hanya melihat/mengubah file yang ia buat sendiri. Cukup
  untuk backup, dan pengguna tidak perlu takut. Minta scope penuh (`auth/drive`) hanya bila
  memang perlu membaca file buatan aplikasi lain (gdbackup memintanya — berlebihan).
- `refresh_token` bisa mati (`invalid_grant`): dicabut pengguna, diganti password, atau tidak
  dipakai 6 bulan. Tangani sebagai "harus login ulang", bukan crash.
- Penyimpanan token: di Android gunakan penyimpanan terenkripsi (EncryptedSharedPreferences /
  DataStore + Keystore). Token plaintext di file JSON (db2cloud) adalah anti-pola.
- Di **Android**, alur yang benar memakai **AppAuth / Chrome Custom Tab** dengan redirect
  custom scheme, dan **PKCE**. Di web SPA: Authorization Code + PKCE, `client_secret` tidak
  boleh tertanam di JavaScript — gunakan fungsi serverless sebagai perantara tukar token
  (pola mediasi server seperti hassio) bila perlu.
- Alternatif tanpa OAuth per pengguna: **service account** hanya masuk akal untuk Google
  Workspace (dengan domain-wide delegation + impersonasi, seperti docker-volume-backup) —
  **tidak** untuk akun Google pribadi/AI Plus.

### 7.2 Drive API v3 — operasi inti

Base: `https://www.googleapis.com/drive/v3`, header `Authorization: Bearer <access_token>`.

| Operasi | HTTP | Catatan |
|---|---|---|
| Buat folder | `POST /files` `{name, mimeType: "application/vnd.google-apps.folder", parents:[…]}` | pola "cari dulu, buat bila belum ada" (folder finder di-cache 30 detik di hassio) |
| Cari file/folder | `GET /files?q=…&fields=files(id,name,mimeType,createdTime,modifiedTime,size,appProperties),nextPageToken&pageSize=…` | lihat sintaks query di bawah |
| Upload sederhana | `POST /upload/drive/v3/files?uploadType=media` | hanya untuk file kecil |
| **Upload resumable** | lihat §7.3 | satu-satunya cara yang benar untuk file besar |
| Unduh | `GET /files/{id}?alt=media` | mendukung header `Range` untuk resume unduhan |
| Update metadata/properti | `PATCH /files/{id}` `{appProperties: {…}}` | kunci+nilai < 124 byte UTF-8 |
| Hapus | `DELETE /files/{id}` atau `POST /files/{id}/trash` | retensi: saring `createdTime` |
| Kuota | `GET /about?fields=storageQuota` | `storageQuota.limit` / `storageQuota.usage` (byte) |

Sintaks query `q` yang dipakai repo-repo:

```
name = 'backup.tar'
name contains 'backup-' and trashed = false
'<folderId>' in parents
createdTime < '2026-01-01T00:00:00Z'
appProperties has { key='slug' and value='…' }
mimeType = 'application/vnd.google-apps.folder'
```

Paginasi: ulangi dengan `pageToken = nextPageToken` sampai `nextPageToken` kosong.

### 7.3 Resumable upload — protokol persis (inti Drive)

Inilah bagian yang paling menentukan keberhasilan backup file besar dari HP. Protokol resmi
Google, ditulis ulang dari implementasi terbaik yang dibedah (hassio) + perilaku library resmi:

**Langkah 1 — buka sesi**

```http
POST https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true
Authorization: Bearer <access_token>
Content-Type: application/json; charset=UTF-8
X-Upload-Content-Type: application/octet-stream
X-Upload-Content-Length: <total byte file>

{"name": "<nama file>", "parents": ["<folderId>"],
 "appProperties": {"slug": "…", "date": "…", "sha256": "…"}}
```

Respons `200 OK` dengan header **`Location: <session URI>`** — URL sesi ini adalah aset:
**simpan** (di DB lokal) agar unggahan bisa dilanjutkan lintas proses/hari.

**Langkah 2 — unggah per potongan**

```http
PUT <session URI>
Content-Length: <ukuran potongan>
Content-Range: bytes <awal>-<akhir>/<total>
<bytes>
```

- Ukuran potongan harus **kelipatan 256 KiB (262.144 byte)**, kecuali potongan terakhir.
- Praktik terbaik (hassio): potongan **adaptif** — mulai dari 256 KiB–1 MiB, naikkan/turunkan
  agar satu potongan selesai dalam **±10 detik** berdasarkan kecepatan terukur; beri plafon
  (mis. 32–64 MiB di WiFi cepat). Library resmi Python memakai default 256 KiB;
  `MediaFileUpload(..., chunksize=5*1024*1024)` + loop `next_chunk()` adalah versi sederhananya.
- Respons per potongan: **`308 Resume Incomplete`** (+ header `Range: bytes=0-<terunggah>`)
  untuk potongan tengah; **`200/201`** + JSON file (berisi `id`) untuk potongan terakhir.

**Langkah 3 — tanya posisi & lanjutkan (resume)**

```http
PUT <session URI>
Content-Length: 0
Content-Range: bytes */<total>
→ 308 + Range: bytes=0-<n>     (server sudah memegang n+1 byte)
→ lanjutkan PUT dari offset n+1
```

Aturan sesi (dari implementasi hassio + dokumentasi Google):

- URL sesi **basi setelah ±6–7 hari** tidak aktif → buang sesi, mulai dari nol.
- Error **4xx** pada sesi = sesi batal (buang, mulai baru). **404/410** saat query posisi =
  sesi hilang → mulai dari nol.
- Error **5xx / 403 rate limit / jaringan** = retry dengan backoff eksponensial
  (basis 2 detik, faktor 2, plafon ±1 jam), **jangan** membuang sesi.
- Penghitung kegagalan sesi: reset ke nol **setiap ada potongan yang berhasil** — unggahan
  yang maju perlahan tetap hidup; yang macet total yang mati.
- Setelah selesai, **verifikasi**: `GET /files/{id}?fields=size,md5Checksum` — bandingkan
  ukuran (dan untuk file yang tidak diubah di tengah jalan, `md5Checksum` Drive = MD5 isi).

### 7.4 Batas & kuota Drive yang perlu dirancang

- Kuota penyimpanan = kuota akun (AI Plus 400 GB, berbagi dengan Gmail/Foto — §2.1).
- Ada batas laju permintaan per pengguna/proyek (error 403 `userRateLimitExceeded`) — jawab
  dengan backoff eksponensial + jitter, bukan antrean rapat.
- `appProperties`: total per file terbatas; kunci+nilai individual < 124 byte UTF-8.
  Untuk hash SHA-256 (64 hex) muat; untuk metadata besar gunakan file manifest terpisah.
- Nama file tidak unik di Drive — **jangan pernah** mengidentifikasi file dari nama;
  gunakan `id` atau `appProperties` (slug+date/hash), seperti hassio & cloudsync.


---

## 8. Skema data & format manifest

### 8.1 DB lokal (Room/SQLite di APK)

Satu tabel `file_parts` untuk dua gudang; kolom tujuan membedakan. Skema ini adalah sintesis
TeleDrive + teledrive (khrj) + hassio, dan sudah diverifikasi jalan di sqlite3:

```sql
CREATE TABLE IF NOT EXISTS files (
  id        TEXT PRIMARY KEY,          -- id internal, stabil lintas gudang
  path      TEXT NOT NULL,             -- path relatif di HP
  size      INTEGER NOT NULL,          -- byte, plaintext
  mtime     INTEGER NOT NULL,          -- epoch ms, buat deteksi berubah
  sha256    TEXT NOT NULL,             -- SHA-256 file utuh (plaintext)
  mime      TEXT,
  state     TEXT NOT NULL DEFAULT 'pending'
            -- pending | uploading | backed_up | error | deleted
);

CREATE TABLE IF NOT EXISTS file_parts (
  file_id        TEXT NOT NULL REFERENCES files(id),
  part_index     INTEGER NOT NULL,
  plain_offset   INTEGER NOT NULL,     -- posisi byte plaintext
  plain_size     INTEGER NOT NULL,
  stored_size    INTEGER NOT NULL,     -- byte setelah enkripsi (jika ada)
  dest           TEXT NOT NULL,        -- 'telegram' | 'drive'
  chat_id        INTEGER,              -- Telegram: channel tujuan
  message_id     INTEGER,              -- Telegram: pesan part (WAJIB disimpan)
  remote_file_id TEXT,                 -- Telegram: remoteFileId TDLib
  drive_file_id  TEXT,                 -- Drive: id file (dikenali dari appProperties)
  PRIMARY KEY (file_id, part_index)
);

-- Riwayat versi murah (model teledrive khrj): daftar hash per file.
-- Hash terakhir = terkini; hash cocok versi lama = konflik -> tawarkan restore.
CREATE TABLE IF NOT EXISTS file_versions (
  file_id   TEXT NOT NULL REFERENCES files(id),
  sha256    TEXT NOT NULL,
  seen_at   INTEGER NOT NULL,          -- epoch ms
  PRIMARY KEY (file_id, sha256)
);

CREATE INDEX IF NOT EXISTS idx_files_path ON files(path);
CREATE INDEX IF NOT EXISTS idx_parts_dest ON file_parts(dest, message_id);
```

Prinsip yang tidak boleh dilanggar:

- **message_id / remote_file_id / drive_file_id dicatat SEGERA setelah unggahan part berhasil**
  dan sebelum part dianggap selesai (TeleSync adalah contoh kegagalan prinsip ini).
- Baris `file_parts` yang sudah ada = resume token: unggahan ulang melewati part itu.

### 8.2 Manifest di caption Telegram (format TeleDrive)

Setiap pesan file membawa manifest dirinya sendiri sebagai caption, agar channel bisa
membangun ulang seluruh database:

- Polos: `td1:` + JSON
- Terenkripsi: `tde1:` + Base64( AES-GCM( JSON ) ) — kebocoran caption tidak membocorkan apa pun.

Kunci JSON sengaja pendek (caption maks 1.024 / 2.048 karakter):

| Kunci | Arti |
|---|---|
| `v` | versi format manifest |
| `id` | id file (sama dengan `files.id`) |
| `n` | nama file |
| `p` | path relatif |
| `fid` | folder id |
| `m` | mime type |
| `s` | ukuran plaintext (byte) |
| `h` | hash (SHA-256, bisa dipotong untuk part dari file terenkripsi) |
| `e` | terenkripsi? (0/1) |
| `ct` / `mt` | created / modified time |
| `pc` | part count |
| `pi` | part index |
| `po` | plain offset part ini |
| `ps` | plain size part ini |

Contoh: `td1:{"v":1,"id":"9f2…","n":"VID_2026.mp4","p":"DCIM/Camera/VID_2026.mp4","s":734003200,"h":"ab12…","e":1,"pc":2,"pi":0,"po":0,"ps":536870912}`

Format penanda lain yang terbukti:

- Snapshot indeks channel: dokumen bernama `teledrive.index.json.gz` dengan caption penanda
  `#teledrive-index`; state folder: `#teledrive-folders`.
- Master JSON gaya teledrive (khrj): caption `#TeleDriveMaster …`; isi
  `{"_":"TeleDriveMaster","version":1,"files":{"DCIM/a.jpg":["<sha256 v1>","<sha256 v2>"]}}`.
- Manifest tunggal gaya TeleNest: pesan teks `#TELEDRIVE_MANIFEST` (+ cadangan
  `#TELEDRIVE_MANIFEST_BACKUP_`) berisi satu JSON besar termasuk `deletedFolderIds` /
  `trashedFileIds` bertimestamp untuk merge multi-perangkat (tombstone: entri yang waktu
  hapusnya lebih baru menang).

### 8.3 Indeks di Google Drive (appProperties)

Pola hassio: setiap file Drive membawa propertinya sendiri; tidak ada DB pusat di Drive.

```
appProperties = {
  "slug":  "<id file internal>",        // kunci identitas
  "date":  "<RFC3339 waktu backup asli>",
  "sha":   "<8–16 hex pertama SHA-256>", // hash penuh disimpan di manifest, properti dipotong <120 byte
  "kind":  "part" | "index" | "full",
  "pi":    "<part index>"               // bila kind=part
}
```

Sebuah file hanya diakui sebagai backup sah bila properti identitas (`slug` + `date`) ada.
Setel `createdTime`/`modifiedTime` file ke waktu backup asli saat membuat metadata agar
penyaringan retensi berbasis waktu konsisten walau diunggah ulang.

File indeks di Drive: simpan snapshot indeks yang sama (`index.json.gz`) sebagai file dengan
`appProperties.kind = "index"` — web panel cukup mengunduh file itu untuk menjelajah isi gudang.

### 8.4 Snapshot indeks (format bersama dua gudang)

Satu format untuk Telegram & Drive, digzip sebelum disimpan:

```json
{
  "version": 1,
  "generatedAt": "2026-11-10T07:00:00Z",
  "device": "<nama perangkat>",
  "files": [
    {"id": "…", "path": "DCIM/Camera/a.jpg", "size": 12345, "mtime": 1762700000000,
     "sha256": "…", "dest": ["telegram", "drive"],
     "parts": [{"i": 0, "po": 0, "ps": 12345,
                "tg": {"chat": -100123, "msg": 456, "fid": "…"},
                "gd": {"id": "1AbC…"}}]}
  ]
}
```

Aturan snapshot: ditulis di **akhir** sesi backup yang berhasil (penanda commit), diberi nama
bercap waktu, dan snapshot lama dirotasi (simpan N terakhir, mis. 10). Restore selalu mulai
dari snapshot terbaru yang utuh.

---

## 9. Enkripsi

### 9.1 Format stream terbaik: StreamCrypto (TeleDrive)

Format biner file terenkripsi yang memungkinkan **streaming + seek** pada file terenkripsi:

```
Header : MAGIC "TDE1" (4 byte) ‖ VERSION (1 byte) ‖ SALT (8 byte)
Frame* : LEN (4 byte, bit tertinggi = penanda frame terakhir) ‖ CIPHERTEXT ‖ TAG (16 byte)
```

- Tiap frame = **AES-256-GCM** atas tepat **1 MiB plaintext** (kecuali frame terakhir).
- Nonce 12 byte = `SALT(8) ‖ chunkIndex(uint32)` — unik per frame tanpa disimpan.
- AAD = `VERSION ‖ chunkIndex ‖ isFinal` — frame tidak bisa dibuang, ditukar, atau diurut ulang
  tanpa ketahuan.
- Frame berukuran tetap → offset frame untuk posisi plaintext `x` bisa dihitung aritmetika
  (`frameStart(x)`) → file terenkripsi tetap bisa diputar/seek sambil diunduh sebagian.

### 9.2 Manajemen kunci

- **Kunci bernama terpisah** (TeleDrive `CryptoKeys`): `content`, `thumbnail`, `cache`,
  `tdlib_db` — dibungkus (wrapped) oleh kunci induk perangkat (Android Keystore).
- Cadangan kunci via passphrase: **PBKDF2-HMAC-SHA256, 310.000 iterasi, salt 16 byte, kunci
  256 bit** (TeleDrive). Alternatif restic: **scrypt dikalibrasi perangkat** (target ±500 ms,
  ≤60 MB RAM; salt 64 byte).
- **Kunci dua lapis** (restic): data dienkripsi dengan master key acak; yang dibungkus
  passphrase hanyalah master key itu → ganti passphrase tidak menyentuh data.
- Angka yang **jangan** ditiru: PBKDF2 10.000 iterasi (TeleNest).
- Konsekuensi yang harus disampaikan ke pengguna dengan jujur: **cadangan kunci = cadangan
  backup itu sendiri.** Tanpa kunci, gudang terenkripsi tidak bisa dibaca siapa pun —
  termasuk pemiliknya.

### 9.3 Skema alternatif yang ditemui

- **taasant**: AES-GCM per part, nonce acak 12 byte ditempel di depan ciphertext; satu kunci
  AES-256 mentah (hex 64) untuk semua objek; manifest ikut terenkripsi. Sederhana dan benar,
  minus manajemen kunci.
- **cloudsync**: OpenPGP simetris AES-256 (dengan kompresi ZIP di dalam), mengenkripsi isi +
  **nama file/folder + metadata** (Base64, `/`→`_` agar aman jadi nama).
- **restic**: AES-256-CTR + Poly1305-AES per blob, overhead tetap 32 byte/blob
  (`nonce 16 ‖ ciphertext ‖ MAC 16`), MAC diverifikasi sebelum dekripsi.
- **gdbackup** (hanya untuk token, bukan isi): AES-256-CBC + PBKDF2 100 ribu iterasi.

### 9.4 Yang dienkripsi, urutan prioritas

1. Isi file (frame/part).
2. Manifest & caption (mode `tde1:`) — tanpa ini, nama file & path bocor ke siapa pun yang
   bisa membaca channel.
3. Nama file di Drive (opsional, gaya cloudsync) — daftar isi Drive tidak membocorkan struktur.

---

## 10. Algoritma inti

### 10.1 Deteksi berubah (inkremental)

Jenjang murah → mahal (cloudsync + TeleDrive):

```
size berubah? → berubah
mtime berubah? → kandidat berubah → hitung SHA-256 → beda? → berubah
SHA-256 sama → lewati (dan catat hash ke file_versions)
```

Seluruh file di-hash hanya bila size/mtime mengindikasikan perubahan. File yang sedang ditulis
aplikasi lain dilewati dulu (settle time ±45 detik sejak mtime terakhir — pola TelDrive Flutter).

### 10.2 Deduplikasi

Dua tingkat:

- **Per file** (riwayat versi): hash file utuh cocok dengan entri `file_versions` → file ini
  sudah pernah dibackup persis sama → lewati seluruh part-nya.
- **Per part/blob** (gaya restic): ID part = SHA-256 plaintext part; cek `file_parts`/snapshot —
  part yang sudah ada di gudang tidak diunggah lagi, cukup dicatat referensinya. Berlaku lintas
  file (file duplikat hanya disimpan sekali).

### 10.3 Antrean unggah & kerja latar

- Semua unggahan melewati **satu antrean serial/paralel-terbatas** yang persisten (tabel
  antrean / WorkManager), bukan fire-and-forget: proses mati di tengah → bangun lagi → lanjut
  dari `file_parts` + status sesi Drive.
- **Pacer global Telegram**: operasi tulis dijarangkan; FLOOD_WAIT menghentikan seluruh
  antrean selama `retry_after`, bukan hanya satu file.
- **Anggaran waktu per run** (TelDrive Flutter): worker latar berhenti *memulai* unggahan baru
  menjelang batas waktu eksekusi Android, menyelesaikan yang sedang jalan, dan run berikutnya
  melanjutkan. Ini mencegah worker dibunuh paksa di tengah part.
- **Scratch sekali pakai**: part dipotong ke file scratch, diunggah, scratch dihapus —
  kebutuhan ruang bebas selama backup ≈ 1× ukuran part, bukan 1× ukuran file.

### 10.4 Restore (dengan verifikasi berlapis)

```
1. Ambil snapshot indeks terbaru (Telegram: pindai caption / unduh index.json.gz;
   Drive: cari file kind=index terbaru)
2. Untuk tiap file: unduh part berurutan dari gudang yang tersedia
   (Telegram: DownloadFile per part; Drive: GET alt=media, Range untuk resume)
3. Verifikasi SHA-256 per part → gagal? coba gudang satunya → masih gagal? tandai, jangan tulis
4. Dekripsi (bila e=1) frame demi frame
5. Gabung ke file sementara <nama>.part → verifikasi SHA-256 file utuh → rename atomik ke path asal
   → pulihkan mtime asli
6. Laporkan per file: pulih / pulih dari gudang cadangan / gagal (dengan alasan)
```

Aturan emas: **file hasil restore tidak pernah ditulis langsung ke path asal** — selalu lewat
file sementara + rename, agar kegagalan di tengah tidak menghancurkan data yang ada.
Dan: uji restore sejak hari pertama pengembangan — backup yang restore-nya tidak pernah diuji
bukan backup (TeleSync adalah contoh sistem yang backup-nya "jalan" tapi restore-nya rapuh).

### 10.5 Retensi

Pilih per gudang, bisa digabung:

- **Berbasis jumlah**: simpan N snapshot/backup terakhir (hassio default 4).
- **Berbasis umur**: hapus yang `createdTime` lebih tua dari X hari (docker-volume-backup,
  gdbackup) — di Drive cukup satu query + paginasi; di Telegram hapus pesan part + entri
  manifest, lalu tulis snapshot indeks baru.
- **Generasional** (hassio): simpan harian 7 hari terakhir, mingguan 4 minggu, bulanan 12
  bulan, tahunan — dengan hari jangkar yang bisa diatur.
- Telegram tidak menyediakan retensi otomatis apa pun (gdbackup mengakuinya di README):
  penghapusan harus dilakukan aplikasi sendiri lewat `DeleteMessages`/edit manifest.

### 10.6 Rekonsiliasi dua gudang

- Identitas file sama (`files.id` + SHA-256) di kedua gudang → status per file bisa
  `telegram-only`, `drive-only`, `both`, dan restore mengambil dari mana pun yang tersedia.
- Snapshot indeks ditulis ke **kedua** gudang di akhir sesi (murah: satu file .gz).
- Bila snapshot dua gudang berbeda versi, yang `generatedAt`-nya lebih baru menang; entri file
  digabung berdasarkan (id, sha256).

---

## 11. Sisi Android

### 11.1 Akses penyimpanan — batas kejujuran

| Cakupan | Mekanisme | Butuh root? |
|---|---|---|
| Penyimpanan bersama (`/sdcard`, DCIM, Download, dokumen) | Izin **All Files Access** (`MANAGE_EXTERNAL_STORAGE`) + MediaStore untuk media | tidak |
| Folder pilihan pengguna | Storage Access Framework (SAF, `ACTION_OPEN_DOCUMENT_TREE`) | tidak |
| Data privat aplikasi lain (`/data/data`, `/data/user`) | proses root (libsu RootService) | **ya** |
| Data aplikasi sendiri | biasa | tidak |

Catatan distribusi: All Files Access dibatasi ketat oleh kebijakan Play Store; untuk APK
pemakaian pribadi (sideload) tidak ada hambatan. **Tanpa root, jangan menjanjikan backup data
privat aplikasi lain** — yang realistis adalah seluruh penyimpanan bersama.

### 11.2 Izin & komponen (APK Kotlin)

- `MANAGE_EXTERNAL_STORAGE` (akses file penuh), `INTERNET`, `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK` (seperlunya selama unggahan),
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (ditawarkan, bukan dipaksa).
- Unggahan berjalan sebagai **foreground service** tipe `dataSync` yang digerakkan WorkManager
  (constraint: jaringan tersambung; opsi "hanya WiFi" & "hanya saat mengisi daya").
- Basis data Room dengan WAL; antrean transfer sebagai tabel, bukan status in-memory.

### 11.3 Pola root (bila memilih cakupan aplikasi — model Android-DataBackup)

- Service AIDL di proses su terpisah (libsu `RootService`) sebagai satu-satunya pintu operasi
  privilegi; UI tetap proses biasa; AndroidManifest aplikasi utama bisa nol permission.
- Per (aplikasi × bagian): satu arsip `tar | zstd` + satu `package_restore_config.json`
  (berisi runtime permission, app-op, SSAID).
- Restore: instal APK → ekstrak ke path asal → `chown(uid,gid)` → `chcon` konteks SELinux asli
  → grant permission → tulis balik SSAID.
- Eksklusi per kategori: user/user_de → `cache, lib, code_cache, no_backup, .ota`;
  data/obb/media → `cache, Backup_*`.

### 11.4 Baterai & data

- Default: hanya WiFi, tidak saat baterai < 20%, jeda antar part dapat diatur.
- Tampilkan perkiraan ukuran antrean sebelum mulai ("128 GB akan diunggah dalam ±X sesi").
- Telegram meng-throttle unggahan akun gratis setelah puluhan GB (§2.2) — jadwalkan backup
  besar pertama sebagai proses berhari-hari, bukan sekali duduk.


---

## 12. Sample code

Semua contoh adalah potongan pola dari repo yang dibedah, disederhanakan. Bukan kode produksi
siap tempel — tapi setiap potongan memuat keputusan penting dari sumbernya.

### 12.1 Kotlin — unggah satu part via TDLib + catat lokasi (pola TeleDrive)

```kotlin
suspend fun uploadPart(file: FileEntity, part: PartSpec, scratch: File): FilePartEntity {
    // 1) Tulis part (sudah terenkripsi bila mode enkripsi aktif) ke file scratch
    writePartToScratch(file, part, scratch)

    // 2) Kirim sebagai dokumen; caption = manifest part ini (td1:/tde1:)
    val caption = manifestCodec.encode(manifestOf(file, part), encrypt = file.isEncrypted)
    val request = TdApi.SendMessage().apply {
        chatId = storageChatId
        inputMessageContent = TdApi.InputMessageDocument().apply {
            document = TdApi.InputFileLocal(scratch.absolutePath)
            caption = TdApi.FormattedText(caption, emptyArray())
            disableContentTypeDetection = true
        }
    }
    val message = telegram.send(request)              // melewati pacer global (§12.2)

    // 3) CATAT lokasi SEGERA — sebelum part dianggap selesai
    val doc = (message.content as TdApi.MessageDocument).document
    return FilePartEntity(
        fileId = file.id, partIndex = part.index,
        plainOffset = part.plainOffset, plainSize = part.plainSize,
        storedSize = scratch.length(), dest = "telegram",
        chatId = message.chatId, messageId = message.id,
        remoteFileId = doc.document.id, remoteUniqueId = doc.document.remote.uniqueId
    ).also { partDao.insert(it); scratch.delete() }   // scratch dihapus sebelum part berikutnya
}
```

### 12.2 Kotlin — pacer global + retry FLOOD_WAIT (pola TeleDrive)

```kotlin
object TelegramPacer {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L

    // Semua operasi yang MEMBUAT pesan wajib lewat sini: FLOOD_WAIT berlaku per akun.
    suspend fun <T> call(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            mutex.withLock {
                val wait = nextAllowedAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
            }
            try {
                return block()
            } catch (e: TdException) {
                val retryAfter = parseFloodWaitSeconds(e)   // "FLOOD_WAIT_17" -> 17
                if (retryAfter == null || ++attempt > RATE_LIMIT_RETRIES) throw e
                mutex.withLock {
                    nextAllowedAt = System.currentTimeMillis() + (retryAfter + 1) * 1000
                }
            }
        }
    }
}
```

### 12.3 Kotlin — resumable upload Drive tulisan tangan (pola hassio, disederhanakan)

```kotlin
class DriveResumableUploader(private val http: OkHttpClient, private val token: () -> String) {

    /** Membuka sesi; kembalikan session URI. Simpan URI ini di DB lokal! */
    fun openSession(name: String, folderId: String, size: Long, appProps: Map<String, String>): String {
        val meta = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(folderId))
            put("appProperties", JSONObject(appProps))
        }
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true")
            .header("Authorization", "Bearer ${token()}")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", size.toString())
            .post(meta.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "open session gagal: ${res.code}" }
            return res.header("Location")!!          // ← session URI
        }
    }

    /** Tanya posisi server; -1 = sesi hilang (404/410). */
    fun queryPosition(sessionUri: String, total: Long): Long {
        val req = Request.Builder().url(sessionUri)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404 || res.code == 410) return -1
            val range = res.header("Range")          // "bytes=0-12345"
            return range?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0
        }
    }

    /** Unggah satu potongan; potongan kelipatan 256 KiB kecuali yang terakhir. */
    fun putChunk(sessionUri: String, bytes: ByteArray, start: Long, total: Long): ChunkResult {
        val end = start + bytes.size - 1
        val req = Request.Builder().url(sessionUri)
            .header("Content-Range", "bytes $start-$end/$total")
            .put(bytes.toRequestBody()).build()
        http.newCall(req).execute().use { res ->
            return when (res.code) {
                200, 201 -> ChunkResult.Done(res.body!!.string())   // selesai; parse id file
                308      -> ChunkResult.Incomplete                  // lanjut potongan berikutnya
                403, 429 -> throw RateLimitException()              // backoff, JANGAN buang sesi
                in 400..499 -> throw SessionDeadException()         // buang sesi, mulai dari nol
                else     -> throw IOException("HTTP ${res.code}")    // backoff, sesi dipertahankan
            }
        }
    }
}
```

Loop pengemudinya: `offset = queryPosition(...)` setiap percobaan ulang → `putChunk` per
potongan adaptif → penghitung gagal **reset setiap potongan berhasil** → backoff eksponensial
basis 2 detik untuk `RateLimitException`/IO error.

### 12.4 Python — potong & unggah via Telethon + caption terstruktur (pola TeleSync, diperbaiki)

Perbaikan dari aslinya: message_id **disimpan**, hash **diverifikasi**.

```python
import hashlib, json, os, sqlite3
from telethon import TelegramClient

CHUNK = 50 * 1024 * 1024          # 50 MB; hanya pecah file yang melebihi batas tujuan

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

async def backup_file(client: TelegramClient, db, path: str, file_id: str):
    size = os.path.getsize(path)
    digest = sha256_of(path)
    parts = max(1, (size + CHUNK - 1) // CHUNK)
    with open(path, "rb") as f:
        for i in range(parts):
            data = f.read(CHUNK)
            part_path = f"/tmp/{file_id}.{i}.part"
            with open(part_path, "wb") as out:
                out.write(data)
            caption = f"{file_id}:::::{path}:::::{i}:::::{parts}:::::{digest}"
            msg = await client.send_file("me", part_path, caption=caption)
            # WAJIB: simpan alamat part di Telegram (inilah yang tidak dilakukan TeleSync)
            db.execute("INSERT OR REPLACE INTO file_parts VALUES (?,?,?,?,?,?)",
                       (file_id, i, f.tell() - len(data), len(data), msg.id, digest))
            db.commit()
            os.remove(part_path)
```

### 12.5 Python — resumable Drive via library resmi (pola tg-bot-uploader-drive)

```python
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from googleapiclient.errors import HttpError
import time

SCOPES = ["https://www.googleapis.com/auth/drive.file"]

def upload_resumable(creds, file_path, name, folder_id, app_props, progress_cb=None):
    service = build("drive", "v3", credentials=creds)
    media = MediaFileUpload(file_path, resumable=True, chunksize=5 * 1024 * 1024)  # 5 MB
    request = service.files().create(
        body={"name": name, "parents": [folder_id], "appProperties": app_props},
        media_body=media, fields="id,size,md5Checksum")
    response, attempt = None, 0
    while response is None:
        try:
            status, response = request.next_chunk()
            if status and progress_cb:
                progress_cb(status.progress())
            attempt = 0                       # reset penghitung gagal per chunk berhasil
        except HttpError as e:
            if e.resp.status in (403, 429, 500, 502, 503, 504) and attempt < 5:
                time.sleep(2 * (2 ** attempt)); attempt += 1
            else:
                raise
    return response["id"]                     # simpan id ini ke file_parts.drive_file_id
```

### 12.6 Bot API polos dengan curl (pola db2cloud — dan batasnya)

```bash
curl -s -F document=@"backup.sql.gz" \
     -F caption="Database backup: db-2026-11-10.sql.gz" \
     "https://api.telegram.org/bot${BOT_TOKEN}/sendDocument?chat_id=${CHAT_ID}"
# Ingat: file > 50 MB ditolak; bot juga tidak bisa mengunduh balik file > 20 MB.
# Untuk backup sungguhan jalur ini hanya cocok untuk file kecil / notifikasi.
```

### 12.7 Python — part 19 MiB ala taasant (Bot API yang sadar batas unduh)

```python
PART = 19 * 1024 * 1024   # diturunkan dari batas unduh bot (20 MB), BUKAN batas unggah (50 MB)

def split_parts(path):
    with open(path, "rb") as f:
        i = 0
        while True:
            data = f.read(PART)
            if not data:
                break
            yield i, data
            i += 1
# Setiap part (setelah enkripsi) dikirim via sendDocument; terakhir kirim pesan manifest
# JSON {"parts":[{"file_id":…,"message_id":…}, …]} — ID objek = "<message_id>:<file_id>" manifest.
```

### 12.8 Contoh snapshot indeks (yang dibaca web panel)

Lihat format lengkap di §8.4. Web cukup: login Google → cari file Drive dengan
`appProperties has {key='kind' and value='index'}` terbaru → unduh → gunzip → render daftar.
Di Telegram, web tidak membaca langsung (MTProto bukan untuk browser) — APK yang menerbitkan
salinan indeks yang sama ke Drive.

---

## 13. Matematika skala

Patokan part & jumlah pesan untuk satu cermin penuh (sebelum dedup/kompresi):

| Isi HP | TDLib part 512 MiB | TDLib part 1.950 MiB | Bot API part 19 MiB |
|---|---|---|---|
| 64 GB | ±125 pesan | ±33 pesan | ±3.450 pesan |
| 128 GB | ±250 pesan | ±66 pesan | ±6.900 pesan |
| 200 GB | ±400 pesan | ±103 pesan | ±10.800 pesan |
| 256 GB | ±500 pesan | ±132 pesan | ±13.800 pesan |

Konsekuensi praktis:

- Jalur TDLib membuat backup penuh terasa seperti "mengunggah beberapa ratus file" — wajar.
- Jalur Bot API pada 200 GB berarti >10 ribu pesan dan >10 ribu peluang FLOOD_WAIT — hanya
  masuk akal untuk file kecil/selektif.
- Drive: 200 GB muat di kuota AI Plus 400 GB dengan sisa untuk Gmail/Foto rata-rata pengguna;
  256 GB penuh masih muat tetapi sempit — kombinasikan dengan eksklusi cache dan retensi.
- Waktu unggah dibatasi throttle Telegram (akun gratis melambat setelah puluhan GB) dan
  kecepatan uplink pengguna — rancang backup pertama sebagai proses bertahap berhari-hari
  dengan resume yang andal, bukan balapan sekali jalan.
- Kebutuhan ruang bebas di HP selama backup ≈ 1× ukuran part terbesar (scratch) + ruang unduh
  saat restore ≈ 1× ukuran file terbesar yang dipulihkan.

---

## 14. Anti-pola (terbukti dari repo)

1. **Tidak menyimpan message_id/file_id dan tidak memverifikasi hash** (TeleSync) →
   restore korup diam-diam. Simpan alamat setiap part, verifikasi SHA-256 per part & per file.
2. **"Resumable" yang memuat seluruh file ke RAM lalu satu PUT** (gdbackup, timeout 30 detik) →
   mati di file besar. Resumable berarti: sesi disimpan, posisi ditanya ke server.
3. **Scope Drive penuh padahal cukup `drive.file`** (gdbackup) & **token plaintext tanpa
   refresh handling** (db2cloud) → risiko dan kerapuhan yang tidak perlu.
4. **KDF lemah** (PBKDF2 10.000 iterasi, TeleNest) dan **klaim fitur tanpa kode** (checksum
   part TeleNest yang tidak ada di kode) → audit selalu ke kode, bukan README.
5. **Membuat file backup publik demi share link** (tg-bot-uploader-drive) → untuk backup
   pribadi: jangan pernah.
6. **Mengandalkan Bot API untuk file besar** (TelDrive Flutter) → file > 20 MB tidak bisa
   diunduh kembali oleh bot-nya sendiri; backup satu arah = bukan backup.
7. **Selalu menyalin file utuh ke temp sebelum unggah** (TeleSync) → kebutuhan ruang 2× ukuran
   file terbesar. Gunakan scratch per part.
8. **Tanpa retensi di Telegram** (gdbackup: pembersihan manual) → channel membengkak selamanya;
   retensi harus diimplementasikan aplikasi (hapus pesan + perbarui manifest).
9. **Meng-hardcode api_id/api_hash/token di kode sumber** (beberapa repo contoh) → kredensial
   bocor bersama repo. Kredensial selalu dari pengguna, disimpan terenkripsi di perangkat.
10. **Mengidentifikasi file Drive dari nama** → nama tidak unik di Drive; gunakan `id` /
    `appProperties`.
11. **Menulis hasil restore langsung ke path asal** → kegagalan di tengah menghancurkan data
    lama. Selalu file sementara + verifikasi + rename atomik.
12. **Caption sebagai satu-satunya indeks tanpa snapshot** → rebuild dari ribuan caption itu
    lambat; caption per file + snapshot indeks berkala adalah pasangan yang benar.

---

## 15. Checklist pembangunan bertahap

**APK dulu** (urutan yang disarankan):

- [ ] Kerangka Kotlin: Compose + Room (skema §8.1) + WorkManager + foreground service dataSync.
- [ ] Scanner penyimpanan bersama (All Files Access) + hashing SHA-256 + deteksi berubah (§10.1).
- [ ] Adapter Drive: OAuth (AppAuth + PKCE, scope drive.file) → resumable tulisan tangan
      (§7.3, §12.3) → appProperties (§8.3) → **uji restore + verifikasi**.
- [ ] Adapter Telegram: TDLib (login api_id/api_hash sendiri) → channel gudang otomatis →
      part 512 MiB + scratch (§12.1) → caption manifest (§8.2) → pacer FLOOD_WAIT (§12.2) →
      **uji rebuild DB hanya dari caption**.
- [ ] Snapshot indeks (§8.4) ditulis ke kedua gudang di akhir sesi; rotasi N snapshot.
- [ ] Restore penuh dari kedua gudang dengan verifikasi berlapis (§10.4) — diuji dengan file
      besar, file terenkripsi, dan part yang sengaja dirusak.
- [ ] Enkripsi (§9): format frame StreamCrypto + kunci dua lapis + alur cadangan/pulih kunci.
- [ ] Retensi (§10.5) + rekonsiliasi dua gudang (§10.6).
- [ ] (Opsional, cakupan root) pola Android-DataBackup: RootService + tar per aplikasi +
      restore chown/chcon/permission/SSAID (§11.3).

**Web menyusul**:

- [ ] SPA statis (React + Vite) di Vercel/Cloudflare Pages, tanpa backend.
- [ ] Login Google OAuth (PKCE) → baca snapshot indeks dari Drive → jelajah file & status.
- [ ] Fungsi serverless seperlunya saja (tukar token) — tidak ada server yang menyimpan data.

---

## 16. Glosarium & sumber

**Glosarium singkat**

- **Part/chunk**: potongan byte dari file besar; satuan unggah, resume, dan verifikasi.
- **Manifest**: indeks yang memetakan file → part → lokasi di gudang (+hash). Di proyek ini:
  DB lokal + caption Telegram + appProperties Drive + snapshot indeks.
- **Pacer**: pengatur jarak global untuk operasi tulis Telegram agar tidak memicu FLOOD_WAIT.
- **Session URI**: URL sesi resumable Drive; aset yang harus disimpan untuk melanjutkan unggahan.
- **Scratch**: file sementara satu part; dihapus segera setelah part terunggah.
- **Tombstone**: catatan "sudah dihapus + kapan" agar penghapusan menyebar antar perangkat
  tanpa server.
- **CDC (content-defined chunking)**: pemotongan berdasarkan isi (Rabin fingerprint) ala restic —
  batas part bergeser mengikuti konten sehingga sisipan byte hanya mengubah part di sekitarnya.

**Repo sumber (semua dibedah dari kode)**

- Telegram/TDLib: `github.com/Mahmud0808/TeleDrive`, `github.com/hasibullakhan/TeleNest`,
  `github.com/khrj/teledrive`, `github.com/FujiwaraChoki/TeleSync`, `github.com/rohittp0/GramUp`
- Telegram Bot API: `github.com/aravindrejidev/TelDrive`, `github.com/vedantlavale/taasant`,
  `github.com/Alirewa/tg-bot-uploader-drive`
- Drive & ganda: `github.com/dominusmmp/gdbackup`, `github.com/slashramin/db2cloud-backup`,
  `github.com/offen/docker-volume-backup`, `github.com/HolgerHees/cloudsync`,
  `github.com/sabeechen/hassio-google-drive-backup`
- Referensi inti: `github.com/restic/restic`, `github.com/XayahSuSuSu/Android-DataBackup`

**Dokumentasi resmi yang dirujuk**

- Telegram Bot API: `core.telegram.org/bots/api` (batas 50 MB / 20 MB / 2.000 MB server lokal,
  `retry_after`).
- Telegram FAQ & Premium FAQ: batas 2 GB / 4 GB per file, penyimpanan cloud tak terbatas.
- TDLib: `core.telegram.org/tdlib` (method & state machine otorisasi).
- Google Drive API v3: `developers.google.com/drive/api` (resumable upload, `appProperties`,
  query `q`, error 403/429).
- Google OAuth 2.0: `developers.google.com/identity/protocols/oauth2`.
- Google One / AI Plus Indonesia: `one.google.com` (AI Plus 400 GB Rp75.000/bulan).

---

*Dokumen ini adalah hasil riset baca-saja atas repo-repo open-source di atas (lisensi masing-masing
berlaku untuk kodenya). Pola & angka dirangkum untuk pembelajaran dan pembangunan ulang, bukan
salinan kode mereka. Kredensial apa pun yang ter-hardcode di repo contoh sengaja tidak dicantumkan.*
