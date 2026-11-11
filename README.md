# Modular CRM — Android Material 3

An offline-first, plugin-oriented Kotlin / Jetpack Compose Android application.

## Build

Push **the contents of this directory** to a GitHub repository, including `.github/workflows/android.yml`. Open **Actions → Android Debug APK → Run workflow**. After the run, download **ModularCRM-debug** from the workflow's Artifacts section. The `app-debug.apk` inside is installable on Android 8.0+.

Local build with Android SDK 35, JDK 17 and Gradle 8.9:

```bash
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can open the project as well.

## Project structure

- `app`: host, PIN authentication, plugin registration and main navigation
- `core`: plugin API, event bus, Compose extension slots
- `plugin-crm`: SQLite data, CRM screens, settings, reminders, calendar synchronization
- `plugin-loyalty`: example of a plugin that receives CRM events and extends a client card

### Add your own plugin

1. Create a new Android library module, add `implementation(project(":core"))` and Compose dependencies.
2. Implement `AppPlugin`; provide `pages()` and `settings()` as needed.
3. In `onLoad`, subscribe to cross-plugin events (`registry.subscribe("crm.interaction.created") { ... }`) and/or contribute to UI slots (`registry.contribute("crm.client.detail") { clientId -> ... }`).
4. Include the module in `settings.gradle.kts`, add an app dependency and register its plugin in `MainActivity`'s plugins list.

Plugins are compiled into the host APK. There is **no** remote executable APK download, dynamic loading or untrusted-code sandboxing. A marketplace / runtime install scheme requires separate design and signature trust controls.

### CRM schema

`crm.sqlite` at Android's private database directory (`/data/data/com.example.modularcrm/databases/`). Tables: `clients`, `categories`, `client_categories`, `contacts`, `interactions`, `tasks`, `appointments`. Loyalty uses `loyalty.sqlite` in the same location. Foreign keys enabled; schema version 1.

Clients belong to multiple thematic categories. Interaction types are Contact, Work, Note. A client's stale-contact period is configurable (default 30 days). Task reminders support one-time, daily, weekly, and monthly recurrence. Recurring reminder alarms automatically advance and schedule the next occurrence after firing. You may also tap **Выполнено** to advance a repeating task manually. Alarms are inexact (`AlarmManager.setAndAllowWhileIdle`), so Android may deliver a notification later than the requested timestamp. Android 13+ also requires notification permission. Alarms are re-registered on reboot and application startup.

Scheduled works are distinct from task reminders and have a start/end time. With calendar access granted, a writable system calendar chosen in CRM settings and automatic sync enabled, saving a work creates or updates its calendar entry; deleting work deletes its mapped entry. Local SQLite is the source of truth. Edits made **in other calendar apps** are not imported. If calendar permissions are denied or no calendar is selected, the work remains saved in SQLite, and a manual sync button is available.

## Security caveats

The PIN verifier uses a random salt and PBKDF2-HMAC-SHA256, but ordinary SQLite database contents are **not encrypted**. PIN protects access through the UI, not a rooted device or a filesystem-level compromise. The temporary brute-force cooldown is in activity memory; use an encrypted database / platform keystore and persistent attempt throttling for a production app dealing with sensitive personal data. Debug APKs are signed with Android's debug key and must not be used for production distribution. The app currently does not have account sync, export, migration, editing client/contact entries or calendar-to-CRM sync.
