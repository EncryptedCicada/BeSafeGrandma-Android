package com.citrushack.besafegrandma

// ──────────────────────────────────────────────────────────────────────────────
// API models
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Body sent to POST /api/analyze
 */
data class AnalyzeRequest(val transcript: String)

/**
 * Normalised response returned by the backend (matches your normalizeGeminiResponse shape).
 */
data class AnalysisResult(
    val scamLikelihoodScore: Int,       // 0–100
    val criticalIndicators: List<String>,
    val reasoningSummary: String,
)

// ──────────────────────────────────────────────────────────────────────────────
// UI state
// ──────────────────────────────────────────────────────────────────────────────

enum class RiskLevel(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical"),
}

fun scoreToRiskLevel(score: Int): RiskLevel = when {
    score >= 85 -> RiskLevel.CRITICAL
    score >= 60 -> RiskLevel.HIGH
    score >= 30 -> RiskLevel.MEDIUM
    else        -> RiskLevel.LOW
}

data class ScamAnalysis(
    val score: Int,
    val riskLevel: RiskLevel,
    val flaggedPhrases: List<String>,
    val summary: String,
)

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Listening : RecordingState
    data object Stopped : RecordingState
}
