# DuoBackup

DuoBackup backs up an Android phone's shared storage to two destinations at once: a private
Telegram channel (through your own account, via TDLib) and your personal Google Drive (scope
`drive.file`). Backups are incremental and deduplicated, encryption is optional, and every
restore is verified with SHA-256. A static, read-only web panel for browsing the backup index
on Drive follows once the APK is stable. The project is under construction; see the status
table in [`plan.md`](plan.md).

## Repository layout

```
app/                 Android Gradle project (open this folder in Android Studio)
  app/               :app       Android application (Compose UI, DI wiring)
  core/              :core      pure Kotlin/JVM logic and formats
  drive/             :drive     Google Drive transport
  telegram/          :telegram  Telegram (TDLib) transport
web/                 read-only web panel (added in a later milestone)
.github/workflows/   CI
plan.md              implementation plan and milestone status
info.md              research reference (Indonesian)
```

## Getting the debug APK

There is no release yet. Every CI run builds a debug APK:

1. Open the repository's **Actions** tab and pick the latest green **android** run.
2. Under **Artifacts**, download `duobackup-debug-apk` and unzip it.
3. Install `app-debug.apk` on the phone (allow installing from unknown sources).

The debug build installs as `io.github.rosyidputrar.duobackup.debug`, so it can sit next to a
release build later.

## Building locally

With JDK 17 and the Android SDK installed:

```
cd app
./gradlew ktlintCheck test lintDebug assembleDebug
```
