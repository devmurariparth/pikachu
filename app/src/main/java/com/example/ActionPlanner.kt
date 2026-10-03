package com.example

import com.example.action.ActionName
import com.example.agent.AgentRiskLevel
import com.example.agent.AiModelConfig
import com.example.agent.FinalResponseMode
import com.example.network.InteractionGenerationConfig
import com.example.network.InteractionRequest
import com.example.network.InteractionResponseFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Builds stateless Gemini planner interactions. Response validation lives in StructuredActionParser. */
object ActionPlanner {
    private val systemPrompt = """
        You are MJ, an Android assistant action planner. Return exactly one JSON object with these fields:
        {"action":"SUPPORTED_ACTION","payload":"string or null","speech":"concise user-facing response","lang":"gu|hi|en"}.

        Action and payload rules:
        - CHAT: conversation, questions, or clarification; payload must be null.
        - OPEN_APP: package name, only when the user explicitly asks to open an app.
        - SEARCH_WEB: general web search query. OPEN_URL: an explicitly supplied HTTP(S) URL.
        - PLAY_MUSIC: song/music query, optionally with artist or requested music app. Never use it for videos.
        - PLAY_VIDEO: video search query, especially YouTube/watch requests. Never map a video request to PLAY_MUSIC.
        - CALL: contact name or phone number. SEND_SMS and SEND_WHATSAPP: recipient, optionally recipient|message.
        - NAVIGATE: destination. SET_ALARM: 24-hour hour from 0 to 23. SET_TIMER: duration in whole minutes.
        - TOGGLE_WIFI and TOGGLE_BLUETOOTH: on, off, toggle, or open the Android controls. SET_BRIGHTNESS: integer 0-100.
        - OPEN_SETTINGS, GO_HOME, GO_BACK, OPEN_NOTIFICATIONS, RECENT_APPS, OPEN_QUICK_SETTINGS, SHOW_MEMORIES: null payload.
        - CREATE_TASK: task details. COMPLETE_TASK: task title. REMEMBER_PREFERENCE: only a user-requested memory.
        - FORGET_MEMORY: the requested item or all. Memory actions are REMEMBER_PREFERENCE, SHOW_MEMORIES, and FORGET_MEMORY.
        Supported action names: ${ActionName.entries.joinToString(", ") { it.name }}.

        Understand Gujarati script, Hindi/Devanagari, English, transliterated Gujarati, transliterated Hindi,
        and mixed Gujarati-English or Hindi-English. Preserve the dominant user language in speech and lang.
        If the request is unclear or lacks required details, use CHAT to ask a concise clarification in the user's language.
        Only create, complete, or forget memories when the user explicitly requests it. Never store passwords,
        PINs, payment credentials, or other secrets. Use CHAT for unsafe or unsupported requests.
        Keep speech concise and conversational.
        Never claim that an action has succeeded; the Android action runtime performs execution and verification.
        Do not invent missing details. Use CHAT to ask for clarification.
        """.trimIndent()

    fun buildRequest(
        query: String,
        language: String,
        model: String = AiModelConfig.PRIMARY_PLANNER
    ): InteractionRequest {
        return InteractionRequest(
            model = model,
            input = query,
            systemInstruction = systemInstruction(language),
            responseFormat = listOf(
                InteractionResponseFormat(
                    type = "text",
                    mimeType = "application/json",
                    schema = actionResponseSchema()
                )
            ),
            store = false,
            generationConfig = InteractionGenerationConfig(thinkingLevel = "low")
        )
    }

    /** Builds the Phase 2 multi-step planner request using the Phase 1 centralized model. */
    fun buildAgentPlanRequest(
        query: String,
        language: String,
        recentGoal: String? = null,
        model: String = AiModelConfig.PRIMARY_PLANNER
    ): InteractionRequest {
        val input = buildJsonObject {
            put("request", JsonPrimitive(query.take(MAX_INPUT_LENGTH)))
            put("detected_language", JsonPrimitive(language.takeIf { it in SUPPORTED_LANGUAGES } ?: "en"))
            put("recent_context", recentGoal?.trim()?.take(MAX_CONTEXT_LENGTH)?.takeIf(String::isNotBlank)
                ?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
        return InteractionRequest(
            model = model,
            input = input,
            systemInstruction = agentPlanSystemInstruction(language),
            responseFormat = listOf(
                InteractionResponseFormat(
                    type = "text",
                    mimeType = "application/json",
                    schema = agentPlanResponseSchema()
                )
            ),
            store = false,
            generationConfig = InteractionGenerationConfig(thinkingLevel = "low")
        )
    }

    fun agentPlanSystemInstruction(language: String): String = buildString {
        append("""
            You are MJ, an Android assistant planner. Return exactly one JSON object using the agent-plan schema.
            Produce at most 8 sequential typed steps. Every action must use a listed ActionName and valid payload.
            Each step's depends_on must contain only ids of earlier steps that must be dispatched first.
            required_tools must exactly match the distinct actions used by the steps.
            Use DIRECT_RESPONSE for a conversational answer with no tools, ASK_CLARIFICATION for missing details,
            and SPEAK_RESULT only when steps exist. Classify step and plan risk honestly as LOW, MEDIUM, or HIGH.
            Never claim a step succeeded or completed; report only that its request can be started.
            Use CHAT only for safe conversational responses; represent questions with no tool steps.
            Use memory actions only when explicitly requested, and never store passwords, PINs, payment credentials,
            or other secrets. Do not invent Android capabilities or missing user details. Ask for clarification instead.
            Understand Gujarati, Hindi, English, transliteration, and mixed-language requests. Keep speech concise.
            If recent_context is null, do not invent context. If it is present, use it only to resolve clear references
            such as "it", "there", "that", "now", "આ", "ત્યાં", "તે", "यह", "वहाँ", or "अब".
            Supported actions: ${ActionName.entries.joinToString(", ") { it.name }}.
            """.trimIndent())
        append("\nDetected user language: ")
        append(language.takeIf { it in SUPPORTED_LANGUAGES } ?: "en")
    }

    fun systemInstruction(language: String): String {
        val memories = com.example.data.UserMemoryManager.getFormattedMemoriesForContext()
        return buildString {
            append(systemPrompt)
            append("\nDetected user language: ")
            append(language.takeIf { it in SUPPORTED_LANGUAGES } ?: "en")
            if (memories.isNotBlank()) {
                append("\n\nUser-approved memory context (use only when relevant):\n")
                append(memories)
            }
        }
    }

    private fun actionResponseSchema() = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { ActionName.entries.forEach { add(JsonPrimitive(it.name)) } })
            })
            put("payload", buildJsonObject {
                put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
            })
            put("speech", buildJsonObject { put("type", JsonPrimitive("string")) })
            put("lang", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", JsonArray(SUPPORTED_LANGUAGES.map { JsonPrimitive(it) }))
            })
        })
        put("required", buildJsonArray {
            listOf("action", "payload", "speech", "lang").forEach { add(JsonPrimitive(it)) }
        })
    }

    private fun agentPlanResponseSchema() = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("goal", stringSchema())
            put("required_tools", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { ActionName.entries.forEach { add(JsonPrimitive(it.name)) } })
                })
            })
            put("risk_level", enumSchema(AgentRiskLevel.entries.map { it.name }))
            put("expected_result", stringSchema())
            put("final_response_mode", enumSchema(FinalResponseMode.entries.map { it.name }))
            put("speech", stringSchema())
            put("lang", enumSchema(SUPPORTED_LANGUAGES.toList()))
            put("steps", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("id", stringSchema())
                        put("action", enumSchema(ActionName.entries.map { it.name }))
                        put("payload", buildJsonObject {
                            put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                        })
                        put("depends_on", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", stringSchema())
                        })
                        put("risk_level", enumSchema(AgentRiskLevel.entries.map { it.name }))
                        put("expected_result", stringSchema())
                    })
                    put("required", buildJsonArray {
                        listOf("id", "action", "payload", "depends_on", "risk_level", "expected_result")
                            .forEach { add(JsonPrimitive(it)) }
                    })
                    put("additionalProperties", JsonPrimitive(false))
                })
            })
        })
        put("required", buildJsonArray {
            listOf("goal", "required_tools", "risk_level", "expected_result", "final_response_mode", "speech", "lang", "steps")
                .forEach { add(JsonPrimitive(it)) }
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    private fun stringSchema() = buildJsonObject { put("type", JsonPrimitive("string")) }

    private fun enumSchema(values: List<String>) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private val SUPPORTED_LANGUAGES = setOf("gu", "hi", "en")
    private const val MAX_INPUT_LENGTH = 2_000
    private const val MAX_CONTEXT_LENGTH = 240
}
