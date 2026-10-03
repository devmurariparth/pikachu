Warning: truncated output (original token count: 18340)
Total output lines: 2307

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

The current featu…8340 tokens truncated… commit.

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



---

# 49. PHASE 2 IMPLEMENTATION STATUS

**Implementation status: COMPLETE**  
**Verification status: PENDING GitHub Actions execution**

Phase 2 implementation was performed in the existing devmurariparth/pikachu project only. Phase 1 architecture was preserved.

## Files changed

### Voice
- app/src/main/java/com/example/voice/VoiceState.kt
- app/src/main/java/com/example/voice/VoiceInteractionManager.kt
- app/src/main/java/com/example/voice/TtsEngine.kt
- app/src/main/java/com/example/voice/LanguageNormalizer.kt

### Agent / AI
- app/src/main/java/com/example/agent/LocalIntentRouter.kt
- app/src/main/java/com/example/agent/AiProvider.kt
- app/src/main/java/com/example/agent/ProviderImplementations.kt
- app/src/main/java/com/example/agent/AgentPipeline.kt
- app/src/main/java/com/example/ActionPlanner.kt
- app/src/main/java/com/example/viewmodel/ChatViewModel.kt
- app/src/main/java/com/example/data/AppSettingsManager.kt

### Phase 1 result contract extension
- app/src/main/java/com/example/action/ActionContracts.kt
- app/src/main/java/com/example/action/ActionRuntime.kt
- app/src/main/java/com/example/action/ActionPlanPipeline.kt

### Tests
- app/src/test/java/com/example/voice/LanguageNormalizerTest.kt
- app/src/test/java/com/example/voice/VoiceStateTest.kt
- app/src/test/java/com/example/agent/LocalIntentRouterTest.kt
- app/src/test/java/com/example/agent/AiProviderRouterTest.kt

## Features implemented

### Voice state machine
Added explicit:
- IDLE
- LISTENING
- PROCESSING
- EXECUTING
- SPEAKING
- ERROR
- CANCELLED

Voice input now has:
- microphone permission detection
- safe SpeechRecognizer initialization
- tap-to-talk start/stop/cancel
- retry
- recognition timeout
- no-speech handling
- network/server recognition errors
- recognizer-busy recovery
- safe destruction
- no fake Hey MJ background loop

Speech failures are converted into state/error results instead of crashing the app.

### Multilingual handling
Added support for:
- Gujarati
- Hindi
- English
- Gujarati-English mixed commands
- Hindi-English mixed commands
- transliterated Gujarati/Hindi

Examples covered by tests include:
- "હમણાં YouTube ખોલ"
- "अभी YouTube खोलो"
- "Open YouTube now"
- "કાલે 8 AM nu alarm set kar"
- "कल सुबह 8 बजे alarm लगा दो"

Equivalent common commands are normalized before local routing or AI planning.

### TTS
Added TtsEngine abstraction with AndroidTtsEngine:
- Gujarati locale selection where device TTS supports it
- Hindi locale selection
- English fallback
- interruption
- completion/error callbacks
- safe initialization/shutdown
- graceful unsupported-language handling

### AI providers
Added AiProvider abstraction and router with:
- Gemini adapter
- OpenAI Responses API adapter
- provider fallback
- timeout
- retry
- cancellation propagation
- structured JSON planning contract
- sanitized provider errors
- no secret values in logs

OpenAI provider uses the current Responses API architecture and a configurable provider key stored through the Phase 1 Android Keystore-backed secret store. OpenAI's current documentation states that its latest models support multilingual text and are available through the Responses API. 

### Local fast path
Safe/high-confidence commands now route locally before cloud planning:
- Home
- Back
- Recents
- Quick Settings
- Wi-Fi settings path
- Bluetooth settings path
- Settings
- app launching for existing safe app-open commands
- timer
- alarm

Unknown/low-confidence commands fall through to AI planning.

### Shared agent path
Text and voice continue through the same ChatViewModel/action architecture.

The Phase 1 typed ActionRuntime remains the execution boundary, so actions still pass through:
- policy
- permission
- API support
- cancellation
- typed ActionResult

### Action results
Added explicit typed outcomes:
- Success
- Started
- PermissionRequired
- Unsupported
- Failure
- TimedOut
- Cancelled
- Blocked

Unverified action starts are not reported as verified completion.

### Cancellation
Stop/cancel phrases are normalized across supported languages:
- Stop
- Cancel
- રોક
- બંધ
- रुको
- related common transliterations

The active action runtime is cancelled through the existing coroutine cancellation architecture.

### Background preparation
Only extension points were prepared.

Not implemented:
- unrestricted background microphone
- fake Hey MJ
- VoiceInteractionService
- default assistant role
- lock-screen system assistant
- WhatsApp automation
- YouTube automation
- Spotify automation
- unrestricted Accessibility automation
- advanced phone control

## Test status

Phase 2 test files have been added for:
- Gujarati detection
- Hindi detection
- English detection
- mixed-language normalization
- transliterated language handling
- cancellation phrase detection
- local fast-path routing
- multilingual YouTube open routing
- multilingual alarm routing
- timer routing
- AI provider fallback
- voice state coverage

**GitHub Actions verification is currently pending.**

Latest run:
- Run: 36324272355
- Head commit: 4fefeb2027a45d3bf72442ecf98cdfd5673a86ce
- Status: pending
- Jobs: not started yet

Therefore no Phase 2 build/test result is being claimed as PASS until GitHub executes the run.

## Build status

**NOT YET VERIFIED GREEN.**

Phase 1's previously verified green baseline remains unchanged, but Phase 2 must be re-verified after these source changes.

Required verification:
- unit tests
- lint
- assembleDebug
- unsigned R8 release
- API 26 instrumentation
- API 33 instrumentation
- API 36 instrumentation

## Known limitations

1. Speech recognition language selection still depends on the Android/device speech provider; post-recognition normalization is used to handle mixed/transliterated text.
2. Gujarati TTS depends on the installed device TTS engine. English is used as a graceful fallback when Gujarati is unavailable.
3. OpenAI provider configuration requires the user to supply an OpenAI API key through secure settings; no key is bundled in source.
4. Gemini remains the existing provider implementation and OpenAI is a fallback provider; production provider selection/remote secret proxying can be hardened further later.
5. Multi-step agent planning is structurally prepared, but full autonomous multi-step verification is intentionally limited by Phase 1's requirement that unverified actions cannot unlock subsequent steps.
6. No background wake-word implementation was added.

## PHASE 3 STATUS

**IMPLEMENTED LOCALLY; NOT VERIFIED GREEN.** Phase 3 foundation changes are present in the working tree. Android build, lint, unit tests, and emulator instrumentation have not run successfully in this workspace because Java/JDK is missing; see BUILD STATUS. Do not treat this as a completed or released phase.

### FILES CHANGED

Phase 3 implementation and test files in this working tree:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/voice_interaction_service.xml`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `app/src/main/java/com/example/voice/AssistantCapabilities.kt`
- `app/src/main/java/com/example/voice/AssistantStateMachine.kt`
- `app/src/main/java/com/example/voice/MjVoiceInteractionService.kt`
- `app/src/main/java/com/example/voice/MjVoiceInteractionSessionService.kt`
- `app/src/main/java/com/example/voice/SystemAssistantInvocation.kt`
- `app/src/main/java/com/example/voice/VoiceInteractionManager.kt`
- `app/src/main/java/com/example/voice/VoiceState.kt`
- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/ui/AssistantOverlayScreen.kt`
- `app/src/main/java/com/example/ui/AssistantSetupScreen.kt`
- `app/src/main/java/com/example/ui/PermissionExplanationCard.kt`
- `app/src/main/java/com/example/ui/SettingsScreen.kt`
- `app/src/main/java/com/example/ui/VoiceVisualizer.kt`
- `app/src/main/java/com/example/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/example/agent/AgentPipeline.kt`
- `app/src/main/java/com/example/agent/LocalIntentRouter.kt`
- `app/src/main/java/com/example/action/PermissionPolicyGate.kt`
- `app/src/main/java/com/example/AssistantService.kt`
- `app/src/main/java/com/example/IntentManager.kt`
- `app/src/main/java/com/example/device/DeviceControlManager.kt`
- `app/src/main/java/com/example/AssistantLogger.kt`
- `app/src/main/java/com/example/ActionPlanner.kt`
- `app/src/main/java/com/example/ErrorCategory.kt`
- `app/src/main/java/com/example/OfflineActionHandler.kt`
- `app/src/main/java/com/example/WhatsAppManager.kt`
- `app/src/main/java/com/example/contact/CallActionManager.kt`
- `app/src/main/java/com/example/contact/ContactsManager.kt`
- `app/src/main/java/com/example/data/AppSettingsManager.kt`
- `app/src/main/java/com/example/data/UserMemoryManager.kt`
- `app/src/main/java/com/example/data/task/ReminderNotificationWorker.kt`
- `app/src/main/java/com/example/data/task/TaskManager.kt`
- `app/src/main/java/com/example/music/MusicActionManager.kt`
- `app/src/main/java/com/example/network/NetworkConnectivityManager.kt`
- `app/src/main/java/com/example/repository/ConnectionManager.kt`
- `app/src/main/java/com/example/vision/VisionAnalyzer.kt`
- `app/src/main/java/com/example/ui/DiagnosticsLogsDialog.kt`
- `app/src/test/java/com/example/AssistantLoggerTest.kt`
- `app/src/test/java/com/example/action/PermissionPolicyGateTest.kt`
- `app/src/test/java/com/example/agent/AgentPipelineCancellationTest.kt`
- `app/src/test/java/com/example/agent/LocalIntentRouterTest.kt`
- `app/src/test/java/com/example/voice/AssistantCapabilitiesTest.kt`
- `app/src/test/java/com/example/voice/VoiceInteractionLifecycleTest.kt`
- `app/src/test/java/com/example/voice/VoiceStateTest.kt`
- `app/src/androidTest/java/com/example/AssistantInstrumentedTest.kt`

`app/lint-baseline.xml` and `gradlew.bat` were already locally modified before Phase 3 work began and are preserved; they are not attributed to the Phase 3 implementation.

### VOICEINTERACTIONSERVICE STATUS

Declared with `BIND_VOICE_INTERACTION` and service metadata. `MjVoiceInteractionService` only tracks system readiness and performs no microphone capture. A separate `VoiceInteractionSessionService` creates an interaction session on explicit system invocation. The session routes into the existing `MainActivity`/Phase 2 voice pipeline; no duplicate speech or agent pipeline was added.

### ASSISTANT ROLE STATUS

`RoleManager` availability/held-state checks and an Android system-mediated request flow are implemented. Grant, denial, retryable/unknown result, and unavailable states are represented. Android controls role selection; MJ does not change it silently.

### BACKGROUND ASSISTANT STATUS

The system-owned lightweight service has ready/shutdown lifecycle handling. Session work is placed in a separate `:voice_session` process and only created for explicit invocation. Existing assistant invocation can request microphone permission through the app's runtime permission UI. There is no hidden always-on microphone or wake-word listener. Process-death recovery depends on a fresh Android invocation and existing Activity/ViewModel initialization; persistent in-flight commands are not resumed.

### LOCK SCREEN STATUS

Session metadata disables launch from keyguard. The session checks `KeyguardManager.isDeviceLocked` and displays an unlock prompt without launching the app, capturing voice, or executing actions while locked. Actual device/OEM keyguard behavior remains unverified locally.

### CAPABILITY MODEL

`AssistantCapabilityReport` reports voice interaction, assistant role, microphone, notification posting permission, accessibility, contacts, calling, media intents, and Android API support. Each uses `AVAILABLE`, `NEEDS_PERMISSION`, `NEEDS_ROLE`, `UNSUPPORTED`, or `BLOCKED`. Notification capability currently means permission to post app notifications; it does not claim privileged access to read other apps' notifications.

### TEST STATUS

Added unit tests for capability states, role outcomes, VoiceInteractionService ready/shutdown/restart, explicit invocation contract, state transition protection, and cancellation. Added instrumentation coverage for voice-service manifest declarations, process separation, capability detection, and invocation routing. Existing normalized cancellation phrases are covered by the language/cancellation tests. **None of these new or existing tests are verified in this environment.**

### BUILD STATUS

**BLOCKED BEFORE GRADLE STARTUP:** `.\gradlew.bat :app:testDebugUnitTest --no-daemon` returned `JAVA_HOME is not set and no 'java' command could be found in your PATH`. JDK and Android Studio runtime were not found in the workspace environment. API 26/33/36 emulator images are also absent locally. Unit tests, lint, `assembleDebug`, unsigned `assembleRelease -PallowUnsignedRelease`, and connected API 26/33/36 instrumentation therefore remain unverified. No claim of green status is made.

### KNOWN ANDROID LIMITATIONS

1. Installing/selecting MJ as the default assistant requires Android's user-confirmed role flow and supported device firmware; declaring the service alone does not make it the default.
2. The app voice interaction path needs microphone permission and a speech-recognition provider when the user invokes it. It does not implement “Hey MJ” or always-on listening.
3. Launch from keyguard is disabled. The session requires unlocking before opening the app; no action bypasses device authentication.
4. OEM implementations may vary in assistant invocation and lock-screen presentation. Device verification is outstanding.
5. The accessibility service remains behind the action policy gate and its configuration does not enable window-content retrieval or gesture automation. Supported platform APIs/intents are preferred.
6. Full restart recovery resumes only on a new system/app invocation; active planner/action jobs are cancelled with process death and are not persisted.

## PHASE 2 CURRENT VERIFICATION ADDENDUM

This addendum records the current Phase 2 planner/executor/verifier implementation being prepared on `main`. Earlier entries describe other milestones and do not verify this implementation. The Phase 1 workflow run does not verify these changes.

### PHASE 2 STATUS

Implementation and regression tests are present in the local working tree. Phase 2 remains **UNVERIFIED** until a GitHub Actions run for the Phase 2 commit completes successfully.

### IMPLEMENTATION

- AI plans are parsed as strict JSON into typed `AgentPlan` and `ActionRequest` values. Unknown fields/tools, invalid payloads, inconsistent required tools, invalid dependencies, excessive steps, and understated risk fail before dispatch.
- A central allow-listed `ToolRegistry` maps existing `ActionName` values to typed parameter classes. Execution continues through the existing `ActionRuntime` and `PermissionPolicyGate`; Accessibility remains restricted by the existing policy.
- `ActionPlanPipeline` executes a bounded plan in order, enforces earlier-step dependencies, stops after failure or cancellation, and retries at most twice only for a transient failure from a retry-safe tool.
- `AgentPipeline` preserves local-first routing, normalizes commands, requests structured plans only on AI fallback, executes through the runtime, and reports direct response, verified, started, failed, partial, or cancelled outcomes. Started Android intents are not reported as completed.
- Prior context is limited to a short goal from an allow-listed set of low-risk tools for an unambiguous follow-up, with common credential terms and phone-like strings filtered out. Full message histories are not carried as planner context.
- Gemini continues to use the centralized `gemini-3.8-flash` model. Gemini and OpenAI use stateless structured planning requests; malformed plans fail closed. Provider logs contain categories and status codes rather than API keys or request bodies.

### FILES CHANGED

Phase 2 source changes are in `app/src/main/java/com/example/ActionPlanner.kt`, `app/src/main/java/com/example/action/ActionContracts.kt`, `app/src/main/java/com/example/action/ActionPlanPipeline.kt`, `app/src/main/java/com/example/action/ActionRuntime.kt`, `app/src/main/java/com/example/action/ToolRegistry.kt`, `app/src/main/java/com/example/agent/AgentPipeline.kt`, `app/src/main/java/com/example/agent/AgentPlan.kt`, `app/src/main/java/com/example/agent/AgentPlanParser.kt`, `app/src/main/java/com/example/agent/AiProvider.kt`, `app/src/main/java/com/example/agent/ProviderImplementations.kt`, and `app/src/main/java/com/example/viewmodel/ChatViewModel.kt`.

Phase 2 tests are in `app/src/test/java/com/example/action/ActionPlanPipelineTest.kt`, `app/src/test/java/com/example/action/ActionRuntimeTest.kt`, `app/src/test/java/com/example/agent/AgentPipelineCancellationTest.kt`, `app/src/test/java/com/example/agent/AgentPipelineTest.kt`, `app/src/test/java/com/example/agent/AgentPlanParserTest.kt`, `app/src/test/java/com/example/agent/AiProviderRouterTest.kt`, and `app/src/test/java/com/example/agent/GeminiAiProviderTest.kt`.

The pre-existing line-ending-only working-tree changes in `app/lint-baseline.xml` and `gradlew.bat` are not part of Phase 2 and are not included in the Phase 2 commit.

### TEST AND BUILD STATUS

Tests have been added for strict and malformed plans, unknown tools, typed parameter validation, dependency order, transient-only bounded retry, permission denial, timeout, cancellation, direct responses, local-first routing, Gujarati language normalization, safe follow-up context, provider fallback, and execution verification. No local Android test/build result is claimed. The current environment has no Java runtime, so unit tests, lint, `assembleDebug`, unsigned R8 release, and API 26/33/36 instrumentation require the Phase 2 GitHub Actions run.

### VERIFICATION GATE

Phase 2 is not GREEN and no Phase 3 work is authorized by this verification record until the new Phase 2 commit's GitHub Actions run succeeds. Any failing job must be investigated from that run's logs and fixed without disabling or weakening tests.
