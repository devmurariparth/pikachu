# MJ Smart Tool Router

## Request path

`SmartToolRouter` is a pure routing layer called by `AgentPipeline` after one
language-normalization pass. It returns a sealed `RouteDecision`; it does not
call Android APIs, execute tools, call Gemini, or interpret model output as an
action.

```text
request
  → language normalization and phrase catalog
  → deterministic local fast paths
  → typed candidate + parameter validation against ToolRegistry
  → direct action OR clarification OR planner fallback
  → AgentPipeline / ActionPlanPipeline / ActionRuntime
  → existing policy gate, action result, and response handling
```

The existing `LocalIntentRouter` remains the deterministic compatibility path
for settings, navigation, alarms, timers, and established action parsers. App
aliases and package names are defined once in `RoutingPhraseCatalog`. The smart
router adds natural-language media and sensitive-action extraction, then checks
all direct candidates against the registered tool and its parameter type.

## Decisions and safety

- `DirectTool` carries a registered `ActionRequest` with typed parameters,
  confidence, reason, and detected language. It proceeds through the existing
  plan/runtime/policy pipeline; the router never executes it.
- `ClarificationRequired` handles missing parameters, conflicting media/search
  cues, pronoun targets, malformed URLs/packages, and incomplete message bodies.
  It returns a user prompt and does not call the planner.
- `Unsupported` means the selected `ActionName` is not registered. It returns a
  local unsupported response rather than inventing or planning an unregistered
  action.
- `PlannerRequired` and `NoMatch` pass normalized text and only the existing
  safe follow-up goal to the planner. Context is a reasoning hint and is never
  used as an executable parameter by the router.
- The registry is the source of truth. No arbitrary package command, shell,
  executable code, or raw AI response can become a route.

Examples kept local include app launches, music/video queries, web search,
alarms, and timers. Gujarati, Hindi, mixed-script, and common transliterated
forms share the existing normalizer plus one centralized routing phrase
catalog. Ambiguous calls and messages are clarified; the runtime still owns
permissions, contact resolution, policy checks, execution, and verification.

This router does not add microphone behavior, background recording, new
accessibility automation, or a new Android control surface.
