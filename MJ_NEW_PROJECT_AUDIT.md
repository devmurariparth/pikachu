# MJ NEW PROJECT — COMPLETE PRODUCT + ANDROID ARCHITECTURE AUDIT

**Repository:** `devmurariparth/pikachu`  
**Project:** MJ 2.0 / Android application  
**Branch audited:** `main`  
**Audit scope:** inspection and documentation first; no major MJ implementation started  
**Audit date:** 2026-09-27

> This audit is based on the actual repository contents, manifest, Gradle configuration, Kotlin source, tests, resources, and GitHub Actions configuration in `devmurariparth/pikachu`. The previous `devmurariparth/MJ` repository is intentionally out of scope.

---

## 1. Executive summary

The repository is a real Kotlin/Jetpack Compose Android project with a meaningful amount of MJ functionality already implemented. It is not an empty scaffold.

The current architecture is approximately:

```
Compose UI
   ↓
ChatViewModel
   ↓
OfflineActionHandler
   ↓ (when needed)
ActionPlanner → direct Gemini REST API
   ↓
IntentManager / feature managers
   ├─ Contacts / Calls
   ├─ SMS
   ├─ WhatsApp
   ├─ Music
   ├─ Device settings
   ├─ Tasks / WorkManager
   └─ Accessibility global actions
```

The strongest existing foundation is the separation between command planning and action execution, plus local/offline routing, Compose UI, Room/WorkManager task infrastructure, unit tests, instrumentation tests, and a working CI pipeline.

The biggest architectural gap is that the project is currently an **in-app assistant with an AccessibilityService**, not yet a real Android system assistant. There is no `VoiceInteractionService`, no assistant-role integration, no assistant session architecture, no foreground service architecture, and no App Functions integration.

The current voice implementation uses Android `SpeechRecognizer` and `TextToSpeech` from the activity/view-model lifecycle. Its "Hey MJ" behavior is regex detection after recognition; it is **not a true system hotword/background assistant implementation**.

The most serious security issue found during the audit was a tracked `.env` containing a real Gemini API key. The secret value is intentionally not reproduced anywhere in this audit. The tracked secret file has been removed from the current source tree and `.env` is now ignored. Because the secret existed in Git history, the key should still be considered exposed and rotated/revoked.

The current Gemini architecture also sends a client-held API key directly to the Gemini Developer API. This is not an appropriate production security boundary for a public Android application. The production direction should be Firebase AI Logic with enforced App Check or a controlled backend/proxy architecture.

The latest **verified** GitHub Actions run before the security-only cleanup succeeded across:
- unit tests + debug build
- Android lint
- instrumented tests on API 26
- instrumented tests on API 36
- unsigned R8/release build

However, that CI run was for commit `4ff0fee3d936bc3885eda1ea752d1c52766c679c`. The subsequent secret-removal commits have **not** themselves been CI-verified yet. Therefore this audit does not claim that the post-cleanup tree has a fresh green CI result.

---

## 2. Current architecture

### Application/module structure

Current project is a single Android module:

- root Gradle project: `MJ`
- module: `:app`
- source package: mostly `com.example`
- applicationId: `com.aistudio.mjassistant.abxyzt`
- namespace: `com.example`

Main layers currently present:

- UI: Jetpack Compose
- presentation/state: `ChatViewModel`
- command planning: `ActionPlanner`
- offline command routing: `OfflineActionHandler`
- action dispatch: `IntentManager`
- voice: `VoiceInteractionManager`
- AI transport: `GeminiApi` + Retrofit/OkHttp
- tasks: Room + WorkManager
- contacts/calls: ContactsContract + ACTION_CALL
- messaging: ACTION_SENDTO / WhatsApp intents
- music: app/deep-link/search intents
- device controls: Settings panels + Accessibility global actions
- vision: Accessibility screenshot / camera-related UI + Gemini multimodal request
- memory/settings: SharedPreferences
- diagnostics: in-memory log buffer and crash telemetry

### Architectural assessment

**Decision: REBUILD**

The current architecture is useful as a feature prototype, but it should be refactored into explicit layers before major capability expansion:

1. UI/presentation
2. Assistant session/state machine
3. Intent understanding/planning
4. Tool registry
5. Tool authorization/policy gate
6. Action executors
7. verification/result adapters
8. platform integration layer
9. persistence
10. AI provider layer
11. voice/assistant system integration

The current `ActionPlanner → IntentManager` path is too permissive because the LLM can select raw action names and payloads without a strongly typed, permission-aware tool contract.

---

## 3. Gradle / AGP / Kotlin / Java audit

| Item | Current value | Decision |
|---|---|---|
| Gradle | 9.3.1 | KEEP |
| Android Gradle Plugin | 9.1.1 | KEEP / verify compatibility during future upgrades |
| Kotlin | 2.2.10 | KEEP |
| KSP | 2.2.10-2.0.2 | KEEP |
| Java compile source | 11 | FIX |
| Java compile target | 11 | FIX |
| CI JDK | 21 | KEEP |
| Compose | BOM 2024.09.00 | FIX / controlled modernization |
| Room | 2.7.0 | KEEP |
| WorkManager | 2.10.0 | KEEP |
| Robolectric | 4.16.1 | KEEP |
| Roborazzi | 1.59.0 | GATE/OPTIONAL |
| Firebase BOM | 34.17.0 | FIX / align with actual Firebase architecture |

### JVM issue

The repository compiles source/target Java at version 11 while CI runs JDK 21. This can work, but the build configuration is inconsistent with the current toolchain and should be explicitly standardized.

**Decision: FIX**, not a blind upgrade. First verify all plugins and libraries, then select a single supported JVM target.

Do not downgrade Gradle/AGP merely to preserve obsolete code.

---

## 4. Android SDK compatibility

Current values:

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 24`

Application ID:

`com.aistudio.mjassistant.abxyzt`

Namespace:

`com.example`

### Android 13 / API 33

API 33 remains supported because `minSdk = 24`. The project must **not** raise minSdk just to simplify newer assistant APIs.

Current CI tests API 26 and API 36. There is no dedicated API 33 emulator matrix.

**Decision: FIX**

Add API 33 to compatibility testing in a later build-validation phase. Keep compile/target at 36 unless a documented platform requirement changes.

Important Android 13 considerations already relevant to this project:

- POST_NOTIFICATIONS is a runtime permission.
- microphone access remains subject to while-in-use/background restrictions.
- Android 13 uses the device's default speech provider rather than relying on old hard-coded Google speech implementation behavior.
- Android 12+ restricts background foreground-service starts.
- Android 14+ adds stricter foreground-service type/permission validation.
- exact alarms have special access requirements and should only be used for legitimate user-facing exact-time functionality.

---

## 5. MainActivity / current UI

### Current implementation

`MainActivity` is a Compose `ComponentActivity`.

It initializes:

- crash telemetry
- network connectivity state
- battery optimization state
- app settings
- user memory
- task manager

It then renders:

- `AssistantOverlayScreen`
- `AssistantSetupScreen`
- `SettingsScreen`

The UI is state-switched using local string values such as `"chat"`, `"setup"`, and `"settings"`.

### Assessment

**UI foundation: KEEP**

**Navigation/state architecture: FIX**

The current screen switch is simple and workable, but production MJ should use a typed navigation/state model rather than string-based screen routing.

The existing UI contains substantial premium-oriented components:

- voice orb
- voice visualizer
- shimmer animation
- settings
- memory management
- diagnostics
- permission explanation
- vision consent

These should not be deleted. Their actual purpose should be preserved while the assistant architecture underneath them is rebuilt.

---

## 6. Current AI implementation

### Current path

`ActionPlanner` sends user text to a custom REST interface:

`https://generativelanguage.googleapis.com/`

It uses Retrofit and passes the Gemini API key as a query parameter.

The planner asks the model to return JSON containing:

- action
- payload
- speech
- language

It then parses that JSON and converts it to `PlannedAction`.

### Current strengths

- explicit system prompt
- language field
- action/payload separation
- timeout
- network error handling
- retry-aware error model
- fallback parsing if JSON is malformed

### Current weaknesses

1. LLM-generated raw action names are not represented as a strongly typed tool schema.
2. Payloads are mostly unvalidated strings.
3. Tool authorization is mixed into downstream executors.
4. AI responses are logged.
5. No structured action ID exists for end-to-end verification.
6. No universal confirmation policy exists for high-impact actions.
7. No cancellation token is propagated through the full action chain.
8. No explicit post-action verification layer exists.
9. Direct client Gemini API-key usage is not suitable as the final production security model.
10. The current API endpoints/models must be validated against the provider configuration before release.

**Decision: REBUILD**

Do not remove the existing planner yet. Use it as a reference/prototype while replacing the raw string action contract with a typed tool system.

---

## 7. Gemini / Firebase AI integration

### Actual current state

The repository declares:

- Firebase BOM
- `firebase-ai`
- Firebase App Check libraries in the version catalog

But the inspected source does not show an actual Firebase AI Logic implementation.

The actual runtime AI path is custom Retrofit → Gemini Developer API.

There is also no `google-services.json` in the repository tree and the Google Services plugin is declared in the version catalog but not applied in `app/build.gradle.kts`.

### Decision

**Current direct Gemini REST integration: FIX → REBUILD**

**Unused/partial Firebase setup: FIX**

Production direction:

- Firebase AI Logic SDK
- Firebase App Check with production attestation
- controlled authentication if required
- no production Gemini secret embedded in the APK
- optional server-side backend for sensitive/high-value tool orchestration

Firebase AI Logic documentation currently recommends App Check and provides a proxy architecture that keeps Gemini API credentials out of the client application.

---

## 8. API key / secret handling

### Critical finding

A tracked `.env` existed in the repository and contained a real Gemini API key.

The value is deliberately not recorded in this document.

### Immediate cleanup performed during audit

- tracked `.env` was removed from the repository
- `.env` and other local environment files are now ignored
- `.env.example` remains as a placeholder-only template
- no secret value was printed in chat or written to the audit

### Remaining security action

Because the key was previously committed to Git history:

**ROTATE/REVOKE THE EXPOSED GEMINI KEY.**

Removing the current file does not erase the historical secret from existing Git objects, clones, caches, or forks.

If repository history is considered compromised, perform an authorized history purge after key rotation.

### Client-entered key problem

`AppSettingsManager` stores a custom Gemini API key in ordinary `SharedPreferences`.

**Decision: REBUILD**

Do not use ordinary SharedPreferences as a production secret store.

For the final architecture, avoid requiring end users to paste a privileged Gemini Developer API key into MJ.

---

## 9. .env / .env.example handling

Current `.env.example` contains a placeholder value only.

**Decision: KEEP .env.example**

Recommended convention:

- `.env`: local-only, ignored
- `.env.example`: placeholders only
- CI secrets: GitHub Actions secrets/environment or secure provider integration
- Android production AI: Firebase AI Logic/App Check or backend proxy
- no API secret in Kotlin source
- no API secret in BuildConfig for production distribution

The Secrets Gradle Plugin can be retained for local development values, but it must not be treated as proof that an Android client-side key is secure.

---

## 10. Voice / STT implementation

### Current implementation

`VoiceInteractionManager` uses:

- Android `SpeechRecognizer`
- `RecognitionListener`
- `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
- partial recognition results
- RMS level for visualizer
- language preference
- continuous conversation loop
- regex wake phrase detection
- `TextToSpeech`

### Important distinction

Current "Hey MJ" is **not a real system hotword service**.

The implementation recognizes speech and then checks the recognized text for:

- Hey MJ
- OK MJ
- Okay MJ
- Hi MJ
- MJ

Therefore it is a wake-phrase filter over an active recognition session, not a true always-available hotword engine.

### Decision

**STT foreground/in-app interaction: KEEP → FIX**

**Current regex wake-word loop: REBUILD**

Do not hide microphone use.

For real background assistant capability, use the official Android assistant architecture and system-managed voice interaction mechanisms where available.

---

## 11. Text-to-speech

Current TTS:

- Android `TextToSpeech`
- dynamic locale selection
- speech-rate adjustment
- utterance callbacks
- interruption/barge-in
- continuous conversation restart

**Decision: KEEP → FIX**

Strengths are real and useful.

Required future improvements:

- centralized audio/session state
- cancellation propagation
- assistant audio attributes
- clear handling when selected locale is unavailable
- no automatic re-listening after a failure that could create loops
- test Gujarati, Hindi, English and mixed-language flows on real devices

---

## 12. AccessibilityService audit

### Current service

`AssistantService : AccessibilityService`

Manifest configuration includes:

- `typeAllMask`
- `flagRetrieveInteractiveWindows`
- `flagReportViewIds`
- `canRetrieveWindowContent=true`
- `canPerformGestures=true`

Current code uses accessibility for:

- HOME
- BACK
- RECENTS
- NOTIFICATIONS
- QUICK SETTINGS
- screenshots

The current accessibility event callback does not perform autonomous UI scraping/automation.

### Risk

This is still a significant policy/privacy surface.

Android documentation states accessibility services are intended to assist users with disabilities. They are explicitly user-enabled system services.

MJ must not become an unrestricted Accessibility automation bot.

### Decision

**AccessibilityService: GATE/OPTIONAL → REBUILD**

Use it only for legitimate, clearly scoped accessibility use cases.

Do not use it as the default mechanism for:

- clicking arbitrary UI controls
- reading arbitrary screen content
- bypassing app APIs
- sending messages through UI automation
- controlling third-party apps when official APIs/intents exist
- defeating security prompts

The future assistant architecture must prioritize official APIs, intents, roles, media APIs, App Functions and system assistant APIs.

---

## 13. VoiceInteractionService / default assistant

### Current state

No `VoiceInteractionService` exists.

No `VoiceInteractionSessionService` exists.

No assistant voice-session implementation exists.

No RoleManager assistant-role request exists.

No manifest declaration for a voice interaction service exists.

### Decision

**REBUILD**

This is a core missing architectural component for the stated MJ vision.

Android's `VoiceInteractionService` is the system-managed top-level voice interactor for the selected assistant and is specifically designed for background hotword/voice interaction support.

The implementation should be lightweight, with actual interaction UI/session work handled by the associated session services.

### Default assistant

Current state: **NOT IMPLEMENTED**

Target architecture:

- `RoleManager.ROLE_ASSISTANT`
- user-consent flow
- `VoiceInteractionService`
- voice interaction session/service
- assistant invocation handling
- lock-screen behavior according to supported system rules

**Decision: REBUILD**

---

## 14. Background execution

### Current state

No general foreground-service architecture exists.

No microphone foreground service exists.

No assistant-specific background service exists.

Task reminders use WorkManager.

### Decision

**Task/reminder background work: KEEP**

**Assistant background execution: REBUILD**

Do not use a permanent ordinary background service to keep MJ alive.

For background assistant capability, use the system assistant/VoiceInteractionService architecture.

For user-visible long-running work, use foreground services only when a legitimate foreground-service type and Android restrictions allow it.

Android 12+ restricts background foreground-service starts, and Android 14+ adds stricter checks for while-in-use permissions such as microphone.

---

## 15. Foreground services

Current repository tree contains no ordinary `Service` implementation dedicated to foreground execution.

`AssistantService` is an AccessibilityService, not a foreground assistant service.

**Decision: GATE/OPTIONAL**

Do not add a generic "always running" foreground service.

Only add specific foreground service types if a concrete user-facing use case requires one and Android permits it.

---

## 16. App launching

Current implementation:

- package-name based `getLaunchIntentForPackage`
- hard-coded common package names in the AI prompt
- activity launch through explicit package targeting

**Decision: KEEP → FIX**

Official app launch intents are appropriate.

However:

- package visibility should be minimized
- `QUERY_ALL_PACKAGES` should not be the default solution
- app aliases/package IDs should be resolved safely
- missing apps must produce real failure states
- AI should not invent package names without validation

For known apps, explicit intents/deep links are preferred.

---

## 17. Contacts

Current implementation:

- `READ_CONTACTS`
- ContactsContract
- exact/contains matching
- multiple-contact disambiguation
- multiple-number disambiguation
- no-number handling

**Decision: KEEP → FIX**

This is one of the stronger current features.

Future improvements:

- typed contact tool
- confirmation for ambiguous/high-impact calls
- structured contact selection
- privacy minimization
- better multilingual names/phonetic matching
- avoid reading more contact data than required

---

## 18. Calling

Current implementation:

- `CALL_PHONE`
- `Intent.ACTION_CALL`
- fallback to `Intent.ACTION_DIAL`
- contact resolution before call

**Decision: KEEP → FIX**

The mechanism is an official Android API.

However, current "success" should be defined more carefully. Starting `ACTION_CALL` proves that the call intent was dispatched, not that a call was actually connected.

For the future assistant:

1. resolve contact
2. show/announce target
3. obtain required permission
4. dispatch call
5. report "call initiated" rather than "call completed"
6. optionally verify using supported Telecom state if the product requires call-state awareness

Do not claim a call connected unless Android provides evidence.

---

## 19. SMS / messaging

Current SMS implementation uses:

- `ACTION_SENDTO`
- `smsto:`
- prefilled `sms_body`

This is appropriate for user-mediated messaging.

**Decision: KEEP**

Do not add unnecessary `SEND_SMS` permission.

The current implementation opens the messaging UI; it does not prove that a message was actually sent.

Therefore response wording must remain:

- "Opening Messages..."
- "Message prepared..."

rather than:

- "Message sent"

unless an actual supported send API confirms delivery/sending.

---

## 20. WhatsApp

Current implementation:

- package detection
- WhatsApp/WhatsApp Business
- WhatsApp web/API URL
- ACTION_SEND share intent
- chooser fallback

**Decision: FIX**

The current feature is not a reliable autonomous WhatsApp send mechanism.

It can open WhatsApp or prepare/share content, but it cannot guarantee a message was sent.

Future mechanism:

1. official supported deep link/intent
2. user-mediated send when required
3. App Functions if/when the target app exposes a supported function and Android makes it available
4. no Accessibility clicking of WhatsApp's Send button

---

## 21. YouTube

Current implementation:

- explicit YouTube package where installed
- search URL
- browser fallback

**Decision: KEEP → FIX**

Opening/searching is legitimate.

Playing a specific video is not guaranteed by merely launching a search URL.

Future architecture should report exact result state:

- app opened
- search launched
- playback requested
- playback confirmed, only if a supported media/session API confirms it

---

## 22. Spotify / media

Current implementation primarily uses:

- app/deep links
- web search
- MediaStore media-play search fallback
- YouTube / YouTube Music search

There is no inspected `MediaController`/MediaSession control architecture.

**Decision: REBUILD**

For generic media transport controls:

- use MediaSession/MediaController where a supported media session/token is available
- use app-specific official intents/deep links where required
- use App Functions where available and supported
- do not use Accessibility to press Play/Pause buttons in arbitrary apps

Search/open behavior can remain as a fallback.

---

## 23. Notifications

Current implementation has reminder notifications via `ReminderNotificationWorker`.

Manifest includes:

`POST_NOTIFICATIONS`

No NotificationListenerService is present.

**Decision: KEEP**

Future work:

- Android 13 runtime permission UX
- notification channel validation
- action buttons
- deep links into task/reminder UI
- explicit verification of notification scheduling

Do not add notification-reading capability unless there is a clearly defined, permitted product requirement.

---

## 24. Alarms / timers

Current implementation:

- `AlarmClock.ACTION_SET_ALARM`
- `AlarmClock.ACTION_SET_TIMER`
- WorkManager for task reminders
- manifest includes `com.android.alarm.permission.SET_ALARM`

**Decision: KEEP → FIX**

Using the system AlarmClock intents is the correct direction.

Current parsing is limited. For example, the planner's alarm payload is essentially an hour string.

Required future improvements:

- minutes
- AM/PM
- natural-language date/time parsing
- repeat rules
- timezone correctness
- explicit confirmation when ambiguous
- use exact alarms only where the user-facing function legitimately requires them

Do not use exact alarm privileges merely to keep MJ alive.

---

## 25. Settings / device controls

Current implementation includes:

- Wi-Fi panel
- Bluetooth settings
- brightness
- flashlight
- volume
- quick settings
- notification shade

### Wi-Fi

The code correctly recognizes modern Android restrictions and opens the Internet connectivity panel instead of trying to directly force Wi-Fi state.

**Decision: KEEP → FIX**

It should not say "Wi-Fi turned on" merely because a settings panel opened.

### Bluetooth

Current implementation opens Bluetooth settings rather than guaranteeing a state transition.

**Decision: KEEP → FIX**

### Brightness

Uses `Settings.System.canWrite` and may open the write-settings screen.

**Decision: KEEP → GATE/OPTIONAL**

Do not assume `WRITE_SETTINGS` is a normal runtime permission. It is special app access.

### Flashlight

Uses `CameraManager.setTorchMode`.

**Decision: KEEP**

This is an official API.

### Volume

Uses `AudioManager.adjustVolume`.

**Decision: KEEP → FIX**

Need clear stream/audio policy and result semantics.

### Quick Settings / notification shade

Uses Accessibility global actions.

**Decision: GATE/OPTIONAL**

Do not make this the foundation of device control.

---

## 26. Permissions audit

Current manifest permissions:

- INTERNET
- RECORD_AUDIO
- CAMERA
- SYSTEM_ALERT_WINDOW
- CALL_PHONE
- READ_CONTACTS
- POST_NOTIFICATIONS
- ACCESS_WIFI_STATE
- CHANGE_WIFI_STATE
- BLUETOOTH
- BLUETOOTH_CONNECT
- VIBRATE
- WRITE_SETTINGS
- QUERY_ALL_PACKAGES
- com.android.alarm.permission.SET_ALARM

### General decision

**FIX**

Permissions should be capability-driven and requested only at the moment the user invokes a feature.

Do not request all permissions during initial onboarding.

---

## 27. QUERY_ALL_PACKAGES

Current manifest declares:

`android.permission.QUERY_ALL_PACKAGES`

**Decision: REMOVE**

The current use case is app launching and package detection. Android package visibility supports narrower approaches and explicit intents.

Replace with:

- explicit package launches
- targeted `<queries>` where genuinely required
- documented package visibility use cases

Do not use QUERY_ALL_PACKAGES merely to make an app launcher easier.

---

## 28. SYSTEM_ALERT_WINDOW

Current manifest declares SYSTEM_ALERT_WINDOW.

No dedicated overlay service architecture was found in the inspected tree.

**Decision: GATE/OPTIONAL**

Keep only if a real overlay product requirement remains after the assistant-role architecture is implemented.

Do not request overlay permission simply to simulate a lock-screen/system assistant.

---

## 29. WRITE_SETTINGS

Current manifest declares WRITE_SETTINGS.

Current code uses it for brightness.

**Decision: GATE/OPTIONAL**

Keep only for a clearly user-facing feature that genuinely needs it.

Use system settings panels where possible.

---

## 30. CALL_PHONE

Current manifest declares CALL_PHONE.

Current code uses ACTION_CALL.

**Decision: KEEP**

This permission is directly tied to a user-facing calling capability.

Use ACTION_DIAL as the lower-privilege fallback when direct calling permission is unavailable.

---

## 31. Accessibility policy risk

Risk level: **HIGH**

Reasons:

- broad event mask
- window-content access
- gesture capability
- global actions
- screenshot capture
- use as a device-control fallback

The current code is not yet an unrestricted UI automation engine, which is good.

But the architecture makes it easy to drift into policy-sensitive behavior.

**Decision: GATE/OPTIONAL**

Future policy rule:

> If an official Android API, Intent, RoleManager, Telecom API, MediaSession/MediaController, App Function, or supported deep link can perform the action, MJ must use that mechanism before Accessibility.

Accessibility must not become a universal escape hatch.

---

## 32. Android 13+ restrictions

Important requirements for MJ:

### Android 13

- POST_NOTIFICATIONS is runtime controlled.
- speech services should use the system/default provider.
- background microphone behavior must not assume foreground permissions persist indefinitely.
- notification UX must handle denied notification permission.

### Android 12+

- background foreground-service starts are restricted.
- exact alarms require appropriate special access for exact scheduling.

### Android 14+

- foreground service types and their prerequisites are more strictly enforced.
- microphone/camera foreground services are subject to while-in-use restrictions.

### Android 16+

- App Functions are available as an Android intelligence integration surface, but the API is currently experimental/preview.
- They should be treated as an optional capability layer, not the only way MJ works.

**Decision: FIX**

---

## 33. Security / privacy risks

### Critical

1. Previously tracked Gemini API key.
2. API key passed directly from Android client to Gemini Developer API.
3. User-entered API key stored in ordinary SharedPreferences.
4. Git history contains the previously committed secret and requires rotation/history assessment.

### High

5. Raw AI response is logged by ActionPlanner.
6. User queries/actions are logged.
7. IntentManager logs payloads, which can include contact names, phone numbers and message content.
8. Accessibility screenshot capability is sensitive.
9. User memory is stored in SharedPreferences.
10. Crash telemetry stores exception messages.
11. `allowBackup=true` requires careful review of what personal data can be backed up.

### Medium

12. Broad package visibility permission.
13. Broad Accessibility configuration.
14. Overlay permission.
15. Write-settings access.
16. Multiple third-party integrations with different privacy semantics.

**Decision: REBUILD security boundaries before production release.**

---

## 34. Existing tests

Current test groups:

### Local JVM/Robolectric

- `AssistantLoggerTest`
- `OfflineActionHandlerTest`
- `CallActionManagerTest`
- `BatteryOptimizationManagerTest`
- `UserMemoryManagerTest`
- `MusicActionManagerTest`

### Instrumented

- `AssistantInstrumentedTest`

### Current quality

Good examples:

- command parsing
- offline routing
- contact/call parsing
- music parsing
- permission branch
- intent generation

### Important limitations

Some tests validate that an intent/result object was generated, not that the external application actually performed the requested real-world action.

Examples:

- YouTube/Spotify tests can verify intent creation, not playback.
- call tests can verify ACTION_CALL dispatch, not connection.
- offline tests verify routing, not final execution.
- no real VoiceInteractionService tests exist.
- no assistant-role tests exist.
- no API contract tests exist for the current Gemini response schema.
- no security tests ensure secrets never enter logs.
- no end-to-end multi-step task tests exist.

**Decision: KEEP + EXPAND**

Do not delete tests to obtain green CI.

### Test classification

- pure parsing: KEEP
- local routing: KEEP
- Room/task logic: KEEP + FIX
- Robolectric Android behavior: KEEP
- instrumented tests: KEEP
- external-app action claims: REBUILD around observable contracts
- future assistant role: ADD instrumentation tests
- permission flows: ADD instrumentation tests
- cancellation: ADD unit/instrumented tests
- verification: ADD end-to-end tests

---

## 35. GitHub Actions / build system

Current workflow: `.github/workflows/android-ci.yml`

Jobs:

1. Unit tests and debug build
2. Android Lint
3. Instrumented tests API 26
4. Instrumented tests API 36
5. Unsigned release build with R8/ProGuard

CI uses:

- Ubuntu
- JDK 21
- Gradle setup action
- Gradle wrapper 9.3.1
- emulator runner for instrumentation

### Last verified CI result

Run:

`34028343043`

Commit:

`4ff0fee3d936bc3885eda1ea752d1c52766c679c`

All five jobs completed successfully.

This is strong evidence that the **pre-security-cleanup tree** built and tested successfully through GitHub Actions.

### Important qualification

After the security cleanup:

- `.env` was removed
- `.gitignore` was updated

Those new commits have not yet been run through CI.

Therefore:

**CURRENT POST-CLEANUP BUILD STATUS: NOT YET RE-VERIFIED**

Do not call it green until a new CI run succeeds.

---

## 36. Release signing

Current Gradle logic:

- release signing uses environment variables if a keystore exists
- debug uses standard AGP debug signing
- CI deliberately builds an unsigned release with `-PallowUnsignedRelease`

Current repository tree contains no release keystore.

### Assessment

**Decision: KEEP current CI unsigned release verification**

**Release signing production path: REBUILD/CONFIGURE**

A production release pipeline should:

- use GitHub Actions protected secrets/environment
- use a protected keystore
- restrict release workflow permissions
- avoid committing signing material
- verify the resulting APK/AAB
- publish only from protected branches/tags

The current CI does not prove that a signed production release can be generated.

---

## 37. Obsolete / deprecated code

Findings include:

- Java/Kotlin toolchain configuration that should be standardized.
- Some comments refer to older build assumptions.
- `SpeechRecognizer` behavior is being used as a wake-word approximation rather than the official assistant architecture.
- Firebase dependencies exist without a corresponding active Firebase AI implementation.
- Google Services plugin is declared but not applied.
- App Functions are absent even though they are relevant to the future Android intelligence architecture.
- old/generated schema artifacts exist under `com.Mj.ai.data.db.MjDatabase` while the current task database is `com.example.data.task.TaskDatabase`.

The Room database currently uses:

`fallbackToDestructiveMigration(dropAllTables = true)`

This is not appropriate as a final production migration strategy for persistent user tasks.

**Decision: FIX**

---

## 38. Duplicate / dead code

Potential stale/duplicated areas:

1. exported Room schema history under an old package name versus current TaskDatabase package.
2. Firebase AI dependencies without active Firebase usage.
3. Google Services plugin declared but not applied.
4. multiple device-control fallback paths that often resolve to "open settings" rather than performing the requested state change.
5. duplicated parsing responsibilities between OfflineActionHandler, ActionPlanner and feature managers.
6. synchronous and suspend action execution paths in IntentManager.
7. legacy compatibility methods such as `findContactPhoneNumber` alongside newer typed contact outcomes.

These should be removed only after reference analysis and migration.

**Decision: FIX first, then REMOVE confirmed dead code.**

No blind deletion is recommended.

---

## 39. Fake / placeholder functionality

### Confirmed or likely placeholder semantics

The code has several places where "success" means an intent/panel was opened.

Examples:

- Wi-Fi: success can mean the Internet panel opened.
- Bluetooth: success can mean Bluetooth settings opened.
- brightness fallback: success can mean Quick Settings opened.
- WhatsApp: success can mean WhatsApp/share UI opened.
- YouTube/Spotify: success can mean search/deep link opened.
- calling: success means ACTION_CALL was dispatched, not that the call connected.

### Decision

**REBUILD verification semantics**

Every action should return a structured result:

```
ActionResult
- actionId
- requestedAction
- status
- started
- completed
- verified
- userVisibleMessage
- errorCode
- errorMessage
- cancellable
```

MJ must never say "Done" when it only started an external UI flow.

---

## 40. Missing functionality required for the MJ vision

| Required capability | Current state | Decision | Correct future mechanism |
|---|---|---|---|
| MJ identity/name | Present | KEEP | App/session identity |
| Gujarati | Partial language-aware design | FIX | STT/TTS + model language handling |
| Hindi | Partial | FIX | STT/TTS + model language handling |
| English | Present | KEEP | Existing stack |
| Mixed-language voice | Not reliably verified | REBUILD | multilingual STT + language-aware response |
| Natural conversation | Partial | REBUILD | assistant session state machine |
| Fast local commands | Present | KEEP → FIX | local command router |
| AI reasoning | Present | REBUILD | typed planner/tool calling |
| Tool/function calling | Raw action JSON only | REBUILD | typed tool registry |
| App launching | Present | KEEP → FIX | explicit intents/package visibility |
| Contacts | Present | KEEP → FIX | ContactsContract / picker |
| Calling | Present | KEEP → FIX | Telecom/Intent.ACTION_CALL |
| Messaging | SMS prepared flow | KEEP → FIX | ACTION_SENDTO |
| WhatsApp | UI/deep-link flow | FIX | supported deep links/App Functions where available |
| Media control | Search/deep links | REBUILD | MediaSession/MediaController + supported app APIs |
| Alarms | Present | KEEP → FIX | AlarmClock intents |
| Timers | Present | KEEP | AlarmClock intents |
| Tasks/reminders | Present | KEEP → FIX | Room + WorkManager |
| System settings | Partial | GATE/OPTIONAL | official Settings APIs/panels |
| Background assistant | Missing | REBUILD | VoiceInteractionService |
| Default assistant | Missing | REBUILD | RoleManager.ROLE_ASSISTANT |
| Lock-screen assistant | Missing | REBUILD | VoiceInteractionService/session + system-supported surfaces |
| Cancellation | Partial TTS interruption only | REBUILD | structured CancellationSignal/coroutine cancellation |
| Action verification | Missing | REBUILD | verified ActionResult layer |
| Premium UI | substantial foundation | KEEP | Compose/Material 3 |
| Secure API handling | Unsafe | REBUILD | Firebase AI Logic/App Check or backend |
| Error handling | substantial foundation | FIX | unified typed error model |
| Multi-step tasks | limited | REBUILD | durable task/state machine |
| Accessibility automation | broad fallback | GATE/OPTIONAL | only legitimate accessibility use |
| App Functions | Missing | GATE/OPTIONAL | Android 16+ experimental integration |
| Vision | Present | KEEP → FIX | consented Camera/Accessibility capture + secure AI path |
| Notification reading | Missing | GATE/OPTIONAL | only if future requirement and permitted |
| True hotword | Missing | REBUILD | system assistant/hotword architecture |

---

# 41. Recommended production architecture

## Layer A — Assistant system integration

- `VoiceInteractionService`
- `VoiceInteractionSessionService`
- `VoiceInteractionSession`
- RoleManager assistant-role request
- system-managed assistant lifecycle

## Layer B — Assistant session engine

A central state machine:

```
IDLE
 ↓
LISTENING
 ↓
UNDERSTANDING
 ↓
PLANNING
 ↓
CONFIRMING (if needed)
 ↓
EXECUTING
 ↓
VERIFYING
 ↓
RESPONDING
 ↓
IDLE
```

Every state must be cancellable.

## Layer C — Tool registry

Example:

```
OpenAppTool
ContactLookupTool
CallTool
SmsTool
WhatsAppTool
AlarmTool
TimerTool
TaskTool
MediaTool
SettingsTool
NavigationTool
VisionTool
MemoryTool
```

Each tool declares:

- required permissions
- required role/access
- confirmation requirement
- supported API levels
- cancellability
- verification strategy
- fallback strategy

## Layer D — Platform adapters

Use the most official mechanism available:

1. Android API
2. Intent/deep link
3. RoleManager/default assistant
4. VoiceInteractionService
5. MediaSession/MediaController
6. Telecom
7. App Functions where available
8. Accessibility only where legitimately appropriate

## Layer E — AI provider

Preferred production options:

### Option A
Firebase AI Logic + App Check

### Option B
Controlled backend proxy

### Option C
On-device model for low-latency/simple tasks where device support exists

Recommended hybrid behavior:

```
Local deterministic command
        ↓
if confidently matched → execute
        ↓
else
AI planner
        ↓
typed tool call
        ↓
permission/policy gate
        ↓
executor
        ↓
verification
```

---

# 42. Phase-by-phase implementation plan

## Phase 0 — Audit / security baseline

**Status: IN PROGRESS / AUDIT COMPLETE**

Tasks:

- remove tracked secret
- ignore local env files
- rotate exposed key
- document build baseline
- preserve existing tests
- no major feature implementation

## Phase 1 — Architecture foundation

**NEXT IMPLEMENTATION PHASE**

Tasks:

1. Introduce typed `AssistantTool` contract.
2. Introduce `ActionRequest`.
3. Introduce `ActionResult`.
4. Introduce permission/policy gate.
5. Introduce verification layer.
6. Separate UI from execution.
7. centralize cancellation.
8. standardize JVM/build configuration.
9. clean stale Firebase/plugin/schema configuration only after references are verified.
10. add API 33 CI coverage.

**Do not start background voice yet.**

## Phase 2 — Official assistant integration

Tasks:

- implement VoiceInteractionService
- implement assistant session
- implement RoleManager assistant role request
- define lock-screen behavior
- verify Android 13 compatibility
- add instrumentation tests

## Phase 3 — Voice

Tasks:

- STT session lifecycle
- multilingual Gujarati/Hindi/English
- mixed-language handling
- TTS locale handling
- interruption/cancellation
- system assistant invocation
- no hidden microphone

## Phase 4 — Tool system

Tasks:

- app launch
- contacts
- calls
- SMS
- alarms
- timers
- tasks
- navigation
- settings
- media
- WhatsApp supported flows

Every tool must have verification semantics.

## Phase 5 — AI provider/security

Tasks:

- migrate away from client-held Gemini Developer API key
- Firebase AI Logic or backend
- App Check
- authentication where appropriate
- secure quota controls
- logging redaction
- prompt/tool injection defenses

## Phase 6 — Media and app integrations

Tasks:

- MediaSession/MediaController
- app-specific official APIs
- supported deep links
- App Functions where available
- no Accessibility-based media clicking

## Phase 7 — Multi-step agent

Tasks:

- durable plan state
- step dependencies
- cancellation
- retries
- confirmations
- verification
- partial failure recovery
- user-visible progress

## Phase 8 — Premium product polish

Tasks:

- UI polish
- accessibility
- latency optimization
- animation
- voice visualizer
- lock-screen UX
- diagnostics
- battery behavior
- onboarding

## Phase 9 — Release hardening

Tasks:

- signed release
- R8 verification
- API 26/33/36 testing
- privacy review
- permission review
- Play policy review
- secret scanning
- dependency review
- crash/error telemetry
- final end-to-end device validation

---

# 43. Feature decision matrix

| Feature/component | Decision |
|---|---|
| Compose UI | KEEP |
| MainActivity | FIX |
| ChatViewModel | REBUILD |
| ActionPlanner | REBUILD |
| OfflineActionHandler | KEEP → FIX |
| IntentManager | REBUILD |
| VoiceInteractionManager | REBUILD |
| TextToSpeech | KEEP → FIX |
| SpeechRecognizer foreground mode | KEEP → FIX |
| Regex wake-word loop | REBUILD |
| AccessibilityService | GATE/OPTIONAL |
| Screen capture | GATE/OPTIONAL |
| ContactsManager | KEEP → FIX |
| CallActionManager | KEEP → FIX |
| SmsActionManager | KEEP |
| WhatsAppManager | FIX |
| MusicActionManager | REBUILD |
| DeviceControlManager | FIX |
| TaskManager | KEEP → FIX |
| Room TaskDatabase | KEEP → FIX |
| WorkManager reminders | KEEP |
| UserMemoryManager | KEEP → FIX |
| VisionAnalyzer | KEEP → FIX |
| Gemini Retrofit client | REBUILD |
| Firebase AI dependency | FIX |
| Direct client API key | REMOVE |
| .env.example | KEEP |
| tracked .env | REMOVE |
| QUERY_ALL_PACKAGES | REMOVE |
| SYSTEM_ALERT_WINDOW | GATE/OPTIONAL |
| WRITE_SETTINGS | GATE/OPTIONAL |
| CALL_PHONE | KEEP |
| POST_NOTIFICATIONS | KEEP |
| CrashPreventionManager | FIX |
| AssistantLogger | FIX |
| existing unit tests | KEEP |
| existing instrumentation tests | KEEP |
| R8 unsigned release CI | KEEP |
| debug APK CI | KEEP |
| API 26 instrumentation | KEEP |
| API 33 instrumentation | ADD |
| API 36 instrumentation | KEEP |
| release signing | REBUILD/CONFIGURE |
| App Functions | GATE/OPTIONAL |
| VoiceInteractionService | REBUILD |
| default assistant role | REBUILD |
| foreground assistant service | REBUILD only through official assistant architecture |

---

# 44. Critical risks

## P0 — Secret exposure

A real Gemini API key was tracked.

**Action:** rotate/revoke it and assess Git history.

## P0 — Production AI security model

Client-side Gemini key architecture is not an acceptable final secret boundary.

**Action:** Firebase AI Logic/App Check or backend proxy.

## P0 — Assistant architecture mismatch

Current app is not a real system assistant.

**Action:** VoiceInteractionService + RoleManager.

## P1 — Accessibility overreach risk

Broad AccessibilityService permissions could become policy-sensitive.

**Action:** narrow scope and remove it as the general automation engine.

## P1 — False success semantics

Opening an app/panel is sometimes reported as completing an action.

**Action:** typed action result + verification.

## P1 — No universal cancellation

TTS can be interrupted, but multi-step execution is not globally cancellable.

**Action:** cancellation-aware tool execution.

## P1 — No API 33 CI matrix

**Action:** add API 33 instrumentation.

## P1 — Plain SharedPreferences API key storage

**Action:** remove user-entered privileged API-key architecture.

## P2 — Stale/dead configuration

Firebase/plugin/schema remnants should be cleaned after dependency/reference analysis.

## P2 — Room destructive migration

Do not use destructive migration for final user data.

---

# 45. Android mechanism mapping for the MJ vision

| Capability | Preferred mechanism |
|---|---|
| System assistant | RoleManager + VoiceInteractionService |
| Hotword/background assistant | VoiceInteractionService/system-supported hotword |
| App launch | Intent/deep link |
| Web search | ACTION_WEB_SEARCH / browser intent |
| Calling | Telecom/official call intents |
| Contacts | ContactsContract / user picker |
| SMS | ACTION_SENDTO |
| WhatsApp | official supported intent/deep link/App Function if available |
| Media | MediaSession/MediaController + supported app APIs |
| Alarm | AlarmClock intents / AlarmManager only when appropriate |
| Timer | AlarmClock intent |
| Tasks | Room + WorkManager |
| Notifications | NotificationManager |
| Device settings | Settings panels / official APIs |
| Brightness | Settings.System with special access only when justified |
| Vision | CameraX / consented screenshot APIs |
| Cross-app AI tools | App Functions where available |
| Accessibility | only legitimate accessibility use cases |

---

# 46. Final audit conclusion

The NEW MJ project is a substantial prototype with a useful foundation, not a clean production assistant architecture yet.

The correct next step is **not** to add more commands immediately.

The correct next step is:

1. secure the AI/provider boundary,
2. introduce typed tools and verified action results,
3. separate assistant session architecture from UI,
4. implement the official Android assistant role/VoiceInteractionService path,
5. then rebuild voice and cross-app actions on top of those foundations.

The existing functionality should be preserved until its purpose is understood and each component is migrated or explicitly removed.

No major MJ implementation should begin from the current raw `ActionPlanner → IntentManager` design.

---

# 47. Audit status

**AUDIT COMPLETE**

**AUDIT FILE:** `MJ_NEW_PROJECT_AUDIT.md`

**CURRENT BUILD STATUS:**  
Last verified GitHub Actions run `34028343043` on commit `4ff0fee3d936bc3885eda1ea752d1c52766c679c` passed all five Android CI jobs: unit tests/debug build, lint, API 26 instrumentation, API 36 instrumentation, and unsigned R8 release build. The subsequent secret-removal changes have not yet been CI-verified.

**CURRENT MJ CAPABILITIES:**  
In-app Compose assistant with offline command routing, Gemini planning, speech recognition, TTS, contacts/calling, SMS preparation, WhatsApp intents, music/app search intents, alarms/timers, Room/WorkManager tasks, memory, device settings controls, Accessibility global actions, and vision analysis.

**TOP CRITICAL PROBLEMS:**
1. Previously tracked live Gemini API key — rotate/revoke.
2. Direct client-side Gemini API key architecture.
3. No VoiceInteractionService/default assistant architecture.
4. AccessibilityService is too broad to be the long-term automation foundation.
5. Action success is not consistently verified.
6. No universal cancellation/verification layer.
7. No API 33 CI matrix.
8. Plain SharedPreferences storage for user-provided API keys.

**NEXT IMPLEMENTATION PHASE:**  
**PHASE 1 — Architecture Foundation + Security Hardening.**

Do not start major feature expansion until Phase 1 is implemented, tested, verified, fixed where necessary, and re-tested.


---

# 48. PHASE 1 IMPLEMENTATION STATUS

**Status: COMPLETE**

Phase 1 — Architecture Foundation + Security Hardening — is implemented in the existing devmurariparth/pikachu project only.

No new project was created.

The following Phase 2+ features were intentionally not started:
- Hey MJ / true hotword
- background microphone
- VoiceInteractionService
- default assistant role
- YouTube automation
- Spotify automation
- WhatsApp automation
- advanced phone control

## Files changed

### New production architecture
- app/src/main/java/com/example/action/ActionContracts.kt
- app/src/main/java/com/example/action/PermissionPolicyGate.kt
- app/src/main/java/com/example/action/ActionRuntime.kt
- app/src/main/java/com/example/action/ActionPlanPipeline.kt
- app/src/main/java/com/example/action/PlannedActionMapper.kt
- app/src/main/java/com/example/data/SecureSecretStore.kt

### Updated production code
- app/src/main/java/com/example/ActionPlanner.kt
- app/src/main/java/com/example/viewmodel/ChatViewModel.kt
- app/src/main/java/com/example/data/AppSettingsManager.kt
- app/src/main/java/com/example/AssistantLogger.kt
- app/src/main/java/com/example/CrashPreventionManager.kt
- app/src/main/java/com/example/AssistantService.kt
- app/src/main/java/com/example/network/ApiResult.kt
- app/src/main/AndroidManifest.xml

### CI / testing
- .github/workflows/android-ci.yml
- app/src/test/java/com/example/action/ActionContractsTest.kt
- app/src/test/java/com/example/action/PermissionPolicyGateTest.kt
- app/src/test/java/com/example/action/ActionCancellationRegistryTest.kt
- app/src/test/java/com/example/action/ActionPlanPipelineTest.kt
- app/src/test/java/com/example/action/PlannedActionMapperTest.kt
- app/src/test/java/com/example/network/ApiResultTest.kt
- app/src/androidTest/java/com/example/data/SecureSecretStoreInstrumentedTest.kt

## Features fixed

### Typed action contract
Added:
- typed ActionName
- typed ActionParameters
- ActionRequest
- ActionResult
- ActionError
- verification state
- cancellation registry
- action policy metadata
- tool contract

Planner output is now converted through PlannedActionMapper before execution.

### Permission / policy gate
Added a centralized PermissionPolicyGate that checks:
- Android API support
- runtime permission requirements
- system-role requirements
- Accessibility policy
- explicit allow/deny policy

Blocked or unsupported actions do not execute.

### Verified-result semantics
The execution boundary distinguishes:
- verified success
- started but not verified
- failure
- policy blocked
- cancelled

Legacy execution success is deliberately represented as Started, not fake verified success, until a dedicated verifier exists.

Multi-step plans stop after an unverified Started result and cannot continue as though the step completed.

### Cancellation
Added:
- action cancellation registry
- coroutine cancellation propagation
- cancelCurrentAction() in ChatViewModel
- plan cancellation support

Cancellation is represented as a typed ActionResult.Cancelled.

### Planner foundation
The new plan pipeline supports:
plan → validate → execute → verify boundary → continue/fail/cancel.

Full autonomous multi-step agent behavior remains deferred.

### Accessibility boundary
The existing AccessibilityService remains available but its generic global-action bridge is now restricted to explicit global navigation:
- Home
- Back
- Notifications
- Recents
- Quick Settings

It is not exposed as a general UI automation engine.

### Secret handling
- Local .env remains ignored.
- No secret value was added to source.
- User-entered API keys are now encrypted with Android Keystore instead of plain SharedPreferences.
- Existing plaintext user key storage is migrated into the encrypted store on initialization.
- Provider error bodies are no longer copied into application error messages.
- Planner raw AI responses are no longer logged.
- Logcat output is sanitized before emission.

The previously tracked Gemini credential from the Phase 0 audit still requires rotation/revocation because it existed in repository history. No secret value is reproduced here.

### Crash handling
The old crash handler no longer suppresses uncaught background exceptions. It retains local crash telemetry but delegates uncaught exceptions to the original handler so broken state is not silently hidden.

### Package visibility
Removed broad QUERY_ALL_PACKAGES.

Added targeted package queries for the existing supported integrations:
- Spotify
- YouTube
- YouTube Music
- WhatsApp
- WhatsApp Business
- Google Maps

### Android 13 testing
CI now runs instrumentation on:
- API 26
- API 33
- API 36

Compile SDK and target SDK remain 36.

## Features removed

Only Phase-1 cleanup items were removed:
- broad QUERY_ALL_PACKAGES permission
- raw AI-response logging
- raw provider error-body propagation
- plaintext user API-key persistence
- suppression of uncaught background exceptions
- unrestricted Accessibility global-action execution

Useful MJ command functionality was preserved.

## Test status

Verified GitHub Actions run:

Run: 36309797834
Verified commit: 521cb7f54bc99fb061000a7b00089d5a02c9bb46

All six CI jobs passed:
- Unit tests + debug build — PASS
- Android Lint — PASS
- Unsigned release/R8 build — PASS
- Instrumented API 26 — PASS
- Instrumented API 33 — PASS
- Instrumented API 36 — PASS

Phase 1 added tests covering:
- ActionResult semantics
- typed action parameters
- permission/policy blocking
- unsupported API handling
- Accessibility policy boundary
- cancellation registry
- planner validation
- typed planner mapping
- provider error result typing
- Android Keystore secret storage

## Build status

GREEN for the verified Phase 1 code commit.

Verified:
- testDebugUnitTest
- assembleDebug
- lintDebug
- assembleRelease -PallowUnsignedRelease
- connected instrumentation tests on API 26
- connected instrumentation tests on API 33
- connected instrumentation tests on API 36

No Gradle/AGP/Kotlin version upgrade or downgrade was performed blindly. Existing versions remain the audited baseline.

## Known limitations

1. The current Gemini REST client is still a transitional provider boundary. The final production provider migration should use Firebase AI Logic/App Check or a controlled backend, as planned for the later provider-security phase.
2. The repository currently does not contain the Firebase configuration required for a production Firebase AI Logic runtime migration, so Phase 1 does not fabricate that configuration.
3. Some legacy action executors still return a request-started signal rather than a dedicated verified completion signal. The new runtime intentionally refuses to call that verified success.
4. Full per-tool verification adapters still need to be added during the later tool-system phase.
5. Accessibility remains optional and policy-gated; it is not a universal automation mechanism.
6. The existing in-app voice implementation remains in the project, but no new background/system-assistant voice architecture was introduced in Phase 1.
7. Production release signing remains a separate release-hardening concern.

## Next Phase

PHASE 2 — Official Android Assistant Integration

Planned next work:
- VoiceInteractionService
- VoiceInteractionSessionService
- assistant session lifecycle
- RoleManager.ROLE_ASSISTANT
- system-supported assistant invocation
- lock-screen assistant behavior where supported
- API 33+ assistant integration tests

Phase 2 must build on the Phase 1 typed action, policy, verification, and cancellation boundaries.

