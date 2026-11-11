# Modular Workspace 2.0 — Android + runtime `.mcrm` plugins

**Architecture:** Kotlin / Jetpack Compose Material 3 / SQLite / JSON-driven plugins. A plugin is **not** a separate Android application and contains **no executable Kotlin/DEX**. It is a `.mcrm` file (a ZIP archive with `manifest.json`). The Android host has no Gradle dependency on CRM. On first launch, it installs the bundled `crm.mcrm` from its assets folder as an ordinary plugin; other plugins can be imported and removed through the UI without rebuilding the host.

## Build

1. Upload the **contents** of this directory to the root of a GitHub repository.
2. Open **Actions → Android Debug APK → Run workflow**.
3. Download the `ModularWorkspace-debug` artifact containing `app-debug.apk`.
4. On the phone, open the left drawer → **Управление плагинами** → **Установить .mcrm**. Install `plugins/hardware.mcrm`, `plugins/licenses.mcrm`, `plugins/support.mcrm` as desired.

The `plugins/*.mcrm` files in the repository are **not Android APKs**. They can be renamed to `.zip` for manual inspection. The APK contains only the bootstrap CRM plugin, which is also provided separately.

CI first runs `python tools/validate_plugins.py` and then `gradle :app:assembleDebug`. The workflow targets JDK 17 and Android 35. This project has not yet passed an independent end-to-end Android build and device test in this environment.

## Modular design

The core knows about generic plugins, collections, pages, fields, event hooks, permissions, extensions, settings, declarative actions and native capabilities. It does not contain a `CrmPlugin` class or a dedicated `plugin-crm` Gradle module.

A plugin declares:

- `id`, `name`, `api: 2`, `version`, `depends` — identity and dependency requirements.
- `collections` — types and fields (`text`, `multiline`, `number`, `select`, `boolean`, `date`, `datetime`, `reference`). All data in app-private `workspace.sqlite`. A reference uses the global collection identifier, such as `crm.clients`.
- `pages` — its own sections within its top-level drawer entry; `kind=collection`, `kind=metrics`, `kind=stale`.
- `settings` — host-rendered settings stored in SQLite by plugin namespace.
- `extensions` — contributions to another plugin's stable slots.
- `hooks` — reactions to topic names like `crm.clients.created`.
- `actions` — ordered business operations invoked by UI or record changes.
- `permissions.write` — external collections the plugin requests permission to modify. Permissions must be approved during installation; a declaration by itself **does not** grant write access.

### Stable extension slots

Assuming `crm.clients` defines a client card:

| Slot | Contribution kind | Purpose |
|---|---|---|
| `crm.clients.detail.tabs` | `detail_tab` | Add an independently sourced tab linked to the selected client record |
| `crm.clients.form.fields` | `form_field` | Add fields to the client editor without changing CRM's file |
| `crm.clients.form.fields` | `field_patch` | Override an existing field label, order or required status |
| `crm.clients.detail.fields` | `hide_field` | Hide a field in the generic detail/form rendering |
| `crm.clients.detail.actions` | `detail_action` | Show an action button implemented by the contributing plugin |
| `crm.clients.labels` | `label_patch` | Add supplementary text to a detail card |

Third-party plugins are loaded at runtime. The renderer merges contributions in priority order. Field IDs and collection IDs remain stable to preserve saved data. Dependencies are resolved on startup; orphaned plugins do not activate.

### Example: add a client-card tab

```json
{
  "kind": "detail_tab",
  "target": "crm.clients.detail.tabs",
  "title": "Оборудование",
  "collection": "hardware.devices",
  "relation_field": "client_id",
  "priority": 50
}
```

`hardware.devices` defines a `client_id` field referencing `crm.clients`. Selecting a client shows only that client's servers/workstations in the added tab, with the ability to create linked devices. Neither the host nor CRM needs to be rebuilt.

### Example: modify data in another plugin

`support.mcrm` defines a `detail_action` on the CRM client card, referring to `support.mark_vip`. This action has:

```json
{"id":"mark_vip","steps":[
  {"type":"set_field","collection":"crm.clients","id":"${record.id}","values":{"notes":"VIP (из плагина поддержки)"}}
]}
```

It also declares `"permissions":{"write":["crm.clients"]}`. The host asks the user for approval when importing the archive; the interpreter enforces the approved permission every time an external write occurs.

### Event rules

When a generic collection record is created/updated/deleted, the host emits a topic such as `hardware.devices.created`. Hooks can use `${event.id}`, `${event.name}`, `${record.id}`, `${record.title}`. Available `steps`: `create`, `update`, `delete`, `set_field`, `emit`, `notify`, `calendar_upsert`, `schedule_reminder`.

A step may have an optional condition:

```json
{"type":"emit", "topic":"crm.followup.needed", "when":{"left":"${record.status}","equals":"Просрочено"}}
```

Event chains are depth-limited to prevent trivial infinite loops. **Important limitation:** business actions are not yet transactional across multiple records, and there is no rollback across actions or a general scripting language. This DSL is deliberately constrained so ordinary ZIP files cannot execute arbitrary app code.

## Bundled example plugins

| File | Content | CRM contributions |
|---|---|---|
| `crm.mcrm` | Clients: organizations/individuals, contacts, categories (many-to-many), interactions, tasks, works, metrics, stale contacts, settings | Client-card tabs for contacts, task list, jobs, history and category membership |
| `hardware.mcrm` | Servers, workstations, network devices, asset details and service records | Adds **Оборудование** tab and an optional client field; logs device creation to CRM interaction history with approved write permission |
| `licenses.mcrm` | Software inventory and activation/license records | Adds **ПО** and **Лицензии** tabs to client cards |
| `support.mcrm` | Customer service tickets | Adds **Обращения** tab and a cross-plugin **VIP** action with permission gate |

## Host native integrations

- The CRM declares `calendar_upsert` actions for work creation and editing. The host uses Android Calendar Provider and stores the system event ID in the work record to update the same event. It needs Calendar permissions, accessible under **Settings → Разрешить календарь и уведомления**.
- The CRM declares `schedule_reminder` on task creation and editing. The host schedules a local Android alarm and supports daily, weekly and monthly repetitions while the alarm chain is active. Android may delay inexact alarms to save power.
- **Known limitations:** no reboot restoration for alarms yet; removing a work does not automatically delete a linked system calendar event; a calendar is auto-selected from writable visible calendars; date/time editors currently require Unix timestamps in milliseconds. These limitations require further work before production use.

## Security and production readiness

- Plugin packages are size- and path-validated, never execute arbitrary code, and cannot issue SQL directly. Imported cross-plugin writes require explicit user approval.
- PIN uses salted PBKDF2 and repeated failed attempts result in a short lockout. However, the **SQLite database is not encrypted**; PIN is not disk encryption.
- **Activation codes, client contact data, passwords, and server access credentials must not be stored as real production secrets in this prototype**. Add at-rest encryption (preferably Android Keystore-backed) and backup/migration protections before deployment.
- The extension mechanism is additive/patch-based, not an unrestricted replacement of Compose source code. Further UI widgets must be implemented in the host interpreter. Plugin uninstall leaves data on disk to avoid accidental loss; it is not a deletion/migration tool.
- Imported plugins are not cryptographically signed or sandboxed per plugin yet. Treat permissions and provenance carefully.

## Project structure

```
app/src/main/java/com/example/modularcrm/
  PluginEngine.kt    # ZIP importer, validator, storage, event/actions interpreter, extension registry
  MainActivity.kt    # PIN, drawer, pages, dynamic cards, forms, permissions UI
  HostServices.kt    # restricted calendar and reminder native capabilities
  PinSecurity.kt
app/src/main/assets/plugins/crm.mcrm  # bootstrap file
plugins/*.mcrm                        # independently distributed plugins
plugins/*.manifest.json               # editable source manifests
tools/validate_plugins.py            # preflight schema/package checks
.github/workflows/android.yml        # compile APK on GitHub
```

## UI refresh — v3 (2026-11-11)

The app now includes two curated Material 3 palettes, based on the selected warm white/orange design and a dark navy/amber variant. In **Настройки → Внешний вид**, choose **Светлая**, **Тёмная**, or **Системная**. Preference is saved using private Android SharedPreferences; it is separate from plugin SQLite data.

- Hamburger icon at top left opens a plugin drawer plus plugin manager, settings, and PIN lock.
- Home page displays summary tiles for customer, inventory, license key, and task collections, with quick plugin shortcuts.
- Plugin pages use a bottom navigation bar (four primary pages plus **Ещё** for additional sections); this is scoped to the current plugin and auto-generates from its manifest.
- Lists have search, larger row targets, an explicit add floating button, and status-friendly warm surfaces.
- Tapping a row opens a full-screen card. The card's tabs are merged from installed `.mcrm` plugin extensions; external modules retain their own collection storage and can add related data tabs to the CRM client card.
- Existing PIN, SQLite schema, calendar integration, events/actions, and external `.mcrm` installation logic are retained.

**Build and device testing:** `tools/validate_plugins.py` passes. No Android SDK/Gradle runtime was available locally to compile and instrument-test the UI changes. Run the included GitHub Actions workflow and inspect its logs before distributing an APK. The actual visual layout may vary with device width/font size. Android permissions, data encryption and calendar/reminder limitations documented above remain unchanged.


## Изменения CRM 2.1 — задачи и обновления плагинов

- В форме задач доступны режимы **Одноразово**, **Еженедельно**, **Ежемесячно**. В зависимости от выбора показывается дата календаря, день недели или число месяца (1–31), а также выбор времени. При ежемесячной задаче 29–31-го в коротких месяцах используется последний день месяца без потери исходного выбранного числа.
- Даты и время выбираются стандартными Android DatePickerDialog и TimePickerDialog, отображаются в формате `дд.ММ.гггг ЧЧ:мм`. Unix timestamp остаётся только внутренним форматом хранения и интеграции с Calendar Provider/AlarmManager.
- В менеджере `.mcrm` установка пакета с тем же ID и **более новой SemVer-версией** предлагает обновление вместо ошибки «уже установлен». JSON-записи и настройки SQLite не удаляются. Базовая проверка совместимости запрещает удаление существующих коллекций и полей; изменение их семантики/типа пока требует ручной проверки. Перед заменой файла плагина сохраняется резервная копия и выполняется откат при ошибке.
- В комплект входит `plugins/crm.mcrm` версии `2.1.0`. Пользователям с предыдущим приложением следует импортировать этот файл через «Управление плагинами» → «Загрузить плагин»; встроенный CRM на существующей установке автоматически не перезаписывается.

**Ограничения:** сборка APK в данной среде не проверена; повторяющиеся AlarmManager-напоминания пока не восстанавливаются автоматически после перезагрузки Android. Обновление плагина не выполняет произвольную миграцию БД и не защищает от смысловой несовместимости сохранённых данных.


## CRM 2.2: реквизиты и договоры

* В карточку организации добавлены ИНН, КПП, ОГРН/ОГРНИП, ОКПО, ОКВЭД, налоговый режим, полное название, юридический/фактический/почтовый адрес, руководитель, основание полномочий и сайт. Поля организации скрываются для физлица.
* Вкладка «Реквизиты и счета»: независимые записи `crm.bank_accounts` (несколько счетов одному клиенту) с банком, БИК, расчётным и корреспондентским счетами.
* Вкладка «Договоры»: независимые записи `crm.contracts` с номером, типом, предметом, сроками, суммой, статусом, продлением, условиями оплаты и документом.
* В форме договора кнопка «Прикрепить файл» использует системный выбор документа и сохраняет URI с длительным разрешением чтения. Файл остаётся у исходного провайдера, приложение хранит ссылку, а не копию. При переносе файла или отзыве разрешения ссылка может перестать работать.
* Версия `crm.mcrm` повышена до 2.2.0. Старые записи сохранены: обновление добавляет только новые поля и коллекции.
* ИНН/БИК/счета хранятся текстом для сохранения ведущих нулей; автоматическая валидация реквизитов пока не реализована.
