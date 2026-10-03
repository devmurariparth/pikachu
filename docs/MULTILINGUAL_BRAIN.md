# MJ multilingual understanding

Phase 4 adds a deterministic, local understanding result in `LanguageNormalizer`. The existing `AgentPipeline` computes it once and passes it to `SmartToolRouter` and the legacy local router. Planner use remains the fallback for reasoning and uncertain input. Direct actions still pass through the existing typed tool registry, action plan validation, policy gate, execution, and verification layers.

## Detection and script

`MultilingualInput` contains the original and normalized text, language (`gu`, `hi`, or `en`), detected script, transliterated/canonical text, mixed-language flag, confidence, category, preserved entities, and typed error codes. Gujarati and Devanagari scripts are detected directly. Latin-script Gujarati and Hindi use small, centralized command-vocabulary dictionaries; mixed script and ambiguous evidence are recorded rather than silently treated as high-confidence English. Other writing systems are marked unsupported for deterministic local routing.

## Transliteration and normalization

The phrase catalog maps common Gujarati, Hindi, and Latin transliteration variants to a small canonical command vocabulary, for example `vagadvo` → `play`, `mokale` → `send`, `shodho` → `search`, `खोलो` → `open`, and `लगाओ` → `set`. Replacements use Unicode letter/number boundaries and longest phrase first. This avoids substring rewrites such as changing a person's name because it happens to contain a command syllable.

## Entity preservation

Quoted spans, URLs, numeric/time values, known app names, and title-cased proper-name candidates are detected and masked while command phrases are normalized. The original entity text is restored byte-for-byte into the normalized command and separately exposed in `preservedEntities`. This is a conservative lexical safeguard, not a general-purpose named-entity recognizer; ambiguous recipients or content still require clarification through the router and action policy.

## Context and routing

`AgentPipeline` retains only its existing bounded, safe planner context. Follow-up references may request planner reasoning, but context is never copied into a tool parameter. Sensitive pronouns and incomplete recipients continue to yield clarification. Low-confidence or unsupported language results cannot become direct tool executions. `SmartToolRouter` selects `DirectTool`, `ClarificationRequired`, `PlannerRequired`, `Unsupported`, or `NoMatch`; execution remains behind the existing registry, action-plan checks, policy gate, and verification. No model-generated text is executed directly.

## Confidence and errors

- **HIGH**: single supported script or clear supported-language signal; deterministic routing may continue.
- **MEDIUM**: transliteration or mixed-language input with a sufficient vocabulary margin; only complete, validated parameters may continue.
- **LOW**: ambiguous, malformed, unsupported, or weak language evidence; planner fallback or clarification is required, and direct actions are rejected.

Typed errors include `UNSUPPORTED_LANGUAGE`, `INVALID_INPUT`, `AMBIGUOUS_LANGUAGE`, `MISSING_ENTITY`, `ENTITY_PARSE_FAILED`, `CONTEXT_UNSAFE`, and `NORMALIZATION_FAILED`. Input length is bounded, empty and malformed UTF-16 input returns a typed invalid result, and normalization does not issue network or AI requests.

## Limits

This is command phrase normalization, not speech recognition, translation, or a broad NLP model. Latin-only language detection is limited by a compact phrase vocabulary; unsupported/ambiguous phrasing must fall back to the configured planner or a clarification. Entity candidates are lexical safeguards and do not authorize contacts, messages, calls, or other sensitive actions.

Existing action schemas are intentionally unchanged. For example, alarm execution currently accepts an hour, not an arbitrary natural-language date/time expression; a request that cannot be represented safely must be clarified rather than having its date silently discarded.
