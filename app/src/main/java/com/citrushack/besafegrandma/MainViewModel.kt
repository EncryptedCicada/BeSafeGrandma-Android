package com.citrushack.besafegrandma

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val MIN_WORDS_FOR_ANALYSIS = 6
private const val POLL_INTERVAL_MS = 5_000L

data class UiState(
    val recordingState: RecordingState = RecordingState.Idle,
    /** Final, committed sentences from the recognizer. */
    val committedTranscript: String = "",
    /** In-flight partial hypothesis — shown greyed out while the user is still speaking. */
    val partialTranscript: String = "",
    val analysis: ScamAnalysis? = null,
    val errorMessage: String? = null,
) {
    /** Combined text sent to the heuristic analyzer. */
    val fullTranscript: String
        get() = buildString {
            append(committedTranscript)
            if (partialTranscript.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(partialTranscript)
            }
        }.trim()
}

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Score ratchet state — reset each session.
    private var crossed50   = false
    private var peakScore   = 0
    private var sampleCount = 0
    private var sampleSum   = 0

    private var pollingJob: Job? = null

    // ── Called by the SpeechRecognizer in MainActivity ────────────────────────

    fun onPartialResult(partial: String) {
        _uiState.update { it.copy(partialTranscript = partial) }
    }

    /** Appends a finalised sentence to the committed transcript. */
    fun onRecognitionResult(text: String) {
        if (text.isBlank()) return
        _uiState.update { state ->
            val sep = if (state.committedTranscript.isNotBlank()) " " else ""
            state.copy(
                committedTranscript = state.committedTranscript + sep + text,
                partialTranscript   = "",
            )
        }
    }

    /** Surfaces a device/OS-level recognition error to the UI as a Snackbar. */
    fun onRecognitionError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    // ── Session lifecycle ──────────────────────────────────────────────────────

    fun onStartListening() {
        // Reset all session state.
        crossed50   = false
        peakScore   = 0
        sampleCount = 0
        sampleSum   = 0

        _uiState.update {
            UiState(
                recordingState = RecordingState.Listening,
                analysis = ScamAnalysis(
                    score         = 0,
                    riskLevel     = RiskLevel.LOW,
                    flaggedPhrases = emptyList(),
                    summary       = "Listening… gathering enough context to analyze.",
                ),
            )
        }

        startPolling()
    }

    fun onStopListening() {
        pollingJob?.cancel()
        pollingJob = null

        // Commit any in-flight partial text.
        _uiState.update { state ->
            val partial = state.partialTranscript.trim()
            val sep     = if (state.committedTranscript.isNotBlank() && partial.isNotBlank()) " " else ""
            state.copy(
                recordingState      = RecordingState.Stopped,
                committedTranscript = state.committedTranscript + sep + partial,
                partialTranscript   = "",
            )
        }

        // Compute final score: peak if we ever crossed 50, otherwise mean.
        val finalScore = if (crossed50) peakScore
        else if (sampleCount > 0) (sampleSum / sampleCount)
        else 0

        _uiState.update { state ->
            state.copy(
                analysis = state.analysis?.copy(
                    score     = finalScore,
                    riskLevel = scoreToRiskLevel(finalScore),
                )
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    // ── Polling loop ──────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                analyzeCurrentTranscript()
            }
        }
    }

    private fun analyzeCurrentTranscript() {
        val state = _uiState.value
        if (state.recordingState != RecordingState.Listening) return

        val transcript = state.fullTranscript
        val wordCount  = transcript.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (wordCount < MIN_WORDS_FOR_ANALYSIS) return

        val result = ScamHeuristics.analyze(transcript)

        if (result.score >= 50) crossed50 = true
        peakScore    = maxOf(peakScore, result.score)
        sampleCount++
        sampleSum   += result.score

        // Ratchet: don't let live score fall back below 50 once triggered.
        val liveScore = if (crossed50)
            maxOf(result.score, 50)
        else
            result.score

        val summary = if (result.matchedPhrases.isNotEmpty()) {
            "The following phrases frequently used in scam calls have been detected: " +
                result.matchedPhrases.joinToString(", ")
        } else {
            "The following phrases frequently used in scam calls have been detected: none yet."
        }

        _uiState.update {
            it.copy(
                analysis = ScamAnalysis(
                    score          = liveScore,
                    riskLevel      = scoreToRiskLevel(liveScore),
                    flaggedPhrases = result.matchedPhrases,
                    summary        = summary,
                ),
                errorMessage = null,
            )
        }
    }
}