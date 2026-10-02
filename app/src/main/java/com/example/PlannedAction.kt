package com.example

/** Validated action returned by an AI planner before policy-gated execution. */
data class PlannedAction(
    val action: String,
    val payload: String?,
    val speechResponse: String,
    val language: String = "en"
)
