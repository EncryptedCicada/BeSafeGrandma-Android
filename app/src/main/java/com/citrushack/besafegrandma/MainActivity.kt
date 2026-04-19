package com.citrushack.besafegrandma

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citrushack.besafegrandma.ui.theme.BeSafeGrandmaTheme
import java.util.Locale

private const val TAG = "BeSafeGrandma"

// ── Risk palette ──────────────────────────────────────────────────────────────
private val colorLow      = Color(0xFF22C55E)
private val colorMedium   = Color(0xFFEAB308)
private val colorHigh     = Color(0xFFF97316)
private val colorCritical = Color(0xFFEF4444)

private fun riskColor(level: RiskLevel) = when (level) {
    RiskLevel.LOW      -> colorLow
    RiskLevel.MEDIUM   -> colorMedium
    RiskLevel.HIGH     -> colorHigh
    RiskLevel.CRITICAL -> colorCritical
}

private fun requestMicPermission(
    context: Context,
    launcher: ManagedActivityResultLauncher<String, Boolean>,
    onGranted: () -> Unit
) {
    when {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED -> onGranted()

        else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Activity
// ──────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isSessionActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeSafeGrandmaTheme {
                BeSafeGrandmaScreen(
                    viewModel = viewModel,
                    onStartRequested = ::startSession,
                    onStopRequested  = ::stopSession,
                )
            }
        }
    }

    // ── Session control ────────────────────────────────────────────────────────

    private fun startSession() {
        // Hard guard: surface a message if the device has no recognition service
        // (common on AOSP emulators without Google app).
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            viewModel.onRecognitionError(
                "Speech recognition is not available on this device. " +
                        "Ensure Google app is installed and up to date."
            )
            return
        }

        isSessionActive = true
        viewModel.onStartListening()
        createRecognizer()
        listen()
    }

    private fun stopSession() {
        isSessionActive = false
        // Cancel any pending restart callbacks before we tear down.
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        viewModel.onStopListening()
    }

    /**
     * Creates (or recreates) a SpeechRecognizer with a fresh listener attached.
     * Called on session start and whenever ERROR_CLIENT / ERROR_RECOGNIZER_BUSY
     * leaves the existing instance in an unusable state.
     */
    private fun createRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(buildListener())
        }
        Log.d(TAG, "SpeechRecognizer (re)created")
    }

    /**
     * Assembles the RecognizerIntent and starts listening.
     * We intentionally omit the silence-length extras here — they are poorly
     * supported across OEMs and can prevent `onResults` from ever firing.
     */
    private fun listen() {
        if (!isSessionActive) return
        val recognizer = speechRecognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // Deliver in-progress words so the transcript card updates while speaking.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.startListening(intent)
        Log.d(TAG, "startListening called")
    }

    // ── RecognitionListener ────────────────────────────────────────────────────

    private fun buildListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech — mic open")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) viewModel.onPartialResult(partial)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.d(TAG, "onResults: \"$text\"")
            viewModel.onRecognitionResult(text)
            // Restart immediately — this is our continuous listening loop.
            if (isSessionActive) listen()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "onError: ${errorName(error)} ($error)")
            if (!isSessionActive) return

            when (error) {

                // ── Expected silence gaps: just restart ───────────────────────
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    listen()
                }

                // ── Recognizer is still finishing a previous request ───────────
                // Must cancel() before calling startListening() again.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    speechRecognizer?.cancel()
                    mainHandler.postDelayed({ listen() }, 150)
                }

                // ── "Already listening" or other client-side state error ───────
                // Safest fix: destroy and recreate the entire recognizer instance.
                SpeechRecognizer.ERROR_CLIENT -> {
                    createRecognizer()
                    mainHandler.postDelayed({ listen() }, 150)
                }

                // ── Microphone is genuinely unavailable ───────────────────────
                SpeechRecognizer.ERROR_AUDIO -> {
                    viewModel.onRecognitionError(
                        "Microphone unavailable — is another app using it?"
                    )
                    stopSession()
                }

                // ── Network hiccup: back off and retry ────────────────────────
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    mainHandler.postDelayed({ listen() }, 1_500)
                }

                // ── Anything else: short back-off retry ───────────────────────
                else -> {
                    mainHandler.postDelayed({ listen() }, 500)
                }
            }
        }

        // Unused callbacks required by the interface.
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO            -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT           -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_NETWORK          -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT  -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH         -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY  -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER           -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT   -> "ERROR_SPEECH_TIMEOUT"
        else                                    -> "UNKNOWN"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Root composable
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeSafeGrandmaScreen(
    viewModel: MainViewModel,
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartRequested()
        else viewModel.onRecognitionError("Microphone permission is required to analyze calls.")
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val isRecording = uiState.recordingState == RecordingState.Listening

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "BeSafeGrandma",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            Text(
                text = "Keeping your loved ones safe from phone scams",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))
            MicButton(
                isRecording = isRecording,
                onClick = {
                    if (isRecording) {
                        onStopRequested()
                    } else {
                        requestMicPermission(context, permissionLauncher, onStartRequested)
                    }
                }
            )

            StatusChip(isRecording = isRecording)

            Spacer(Modifier.height(4.dp))

            uiState.analysis?.let { RiskMeterCard(analysis = it) }
                ?: PlaceholderCard("Risk score will appear while recording.")

            TranscriptCard(
                committedTranscript = uiState.committedTranscript,
                partialTranscript   = uiState.partialTranscript,
                flaggedPhrases      = uiState.analysis?.flaggedPhrases ?: emptyList(),
                isRecording         = isRecording,
            )

            AnimatedVisibility(
                visible = uiState.analysis?.summary?.isNotBlank() == true,
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically(),
            ) {
                uiState.analysis?.let { SummaryCard(summary = it.summary) }
            }

            Text(
                text = "Built for CitrusHack '26 · Made with care for the people who raised us",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Mic button with Shazam-style ripple rings
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun MicButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (isRecording) {
            val transition = rememberInfiniteTransition(label = "micPulse")
            listOf(0f, 0.33f, 0.66f).forEach { offset ->
                PulseRing(transition, offset)
            }
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isRecording)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isRecording) "Stop" else "Start listening",
                modifier = Modifier.size(48.dp),
                tint = if (isRecording)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PulseRing(infiniteTransition: InfiniteTransition, delayFraction: Float) {
    val duration = 1800
    val fraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = duration,
                delayMillis    = (duration * delayFraction).toInt(),
                easing         = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring_$delayFraction",
    )
    val size: Dp = 96.dp + (200.dp - 96.dp) * fraction
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = (1f - fraction) * 0.25f))
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Status chip
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun StatusChip(isRecording: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isRecording)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        else
            MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isRecording) {
                val t = rememberInfiniteTransition(label = "dot")
                val a by t.animateFloat(
                    initialValue = 1f,
                    targetValue  = 0.2f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "dotAlpha",
                )
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = a))
                )
            } else {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
            Text(
                text = if (isRecording) "Live listening" else "Ready to analyze",
                style = MaterialTheme.typography.labelMedium,
                color = if (isRecording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Risk meter card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun RiskMeterCard(analysis: ScamAnalysis) {
    val segments = listOf(
        RiskLevel.LOW      to colorLow,
        RiskLevel.MEDIUM   to colorMedium,
        RiskLevel.HIGH     to colorHigh,
        RiskLevel.CRITICAL to colorCritical,
    )
    val color = riskColor(analysis.riskLevel)
    val animatedScore by animateFloatAsState(
        targetValue   = analysis.score.toFloat(),
        animationSpec = tween(800, easing = EaseOut),
        label         = "score",
    )

    ElevatedCard(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(2.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    "SCAM RISK",
                    style       = MaterialTheme.typography.labelSmall,
                    fontWeight  = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color       = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RiskLevelBadge(analysis.riskLevel, color)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
            ) {
                segments.forEach { (level, segColor) ->
                    val active = analysis.riskLevel == level
                    Box(
                        Modifier.weight(1f).fillMaxSize()
                            .background(if (active) segColor else segColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text       = level.label,
                            style      = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color      = if (active) Color.White else segColor,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = "${animatedScore.toInt()}%",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize   = 64.sp,
                    ),
                    color = color,
                )
                Text(
                    "${analysis.riskLevel.label} risk",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun RiskLevelBadge(level: RiskLevel, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text          = level.label.uppercase(),
            modifier      = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style         = MaterialTheme.typography.labelSmall,
            fontWeight    = FontWeight.Bold,
            color         = color,
            letterSpacing = 0.5.sp,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Transcript card — committed text highlighted, partial greyed out
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun TranscriptCard(
    committedTranscript: String,
    partialTranscript: String,
    flaggedPhrases: List<String>,
    isRecording: Boolean,
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(2.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    "LIVE TRANSCRIPT",
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color         = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isRecording) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colorCritical.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "LIVE",
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorCritical,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (committedTranscript.isBlank() && partialTranscript.isBlank()) {
                Text(
                    "Waiting for speech…",
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = MaterialTheme.colorScheme.outline,
                    fontStyle  = FontStyle.Italic,
                )
            } else {
                Text(
                    text       = buildAnnotatedString {
                        appendHighlighted(committedTranscript, flaggedPhrases)
                        if (partialTranscript.isNotBlank()) {
                            if (committedTranscript.isNotBlank()) append(" ")
                            withStyle(SpanStyle(color = Color.Gray)) { append(partialTranscript) }
                        }
                    },
                    style      = MaterialTheme.typography.bodyMedium,
                    lineHeight = 24.sp,
                )
            }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendHighlighted(
    text: String,
    phrases: List<String>,
) {
    if (phrases.isEmpty() || text.isBlank()) { append(text); return }
    val regex = Regex(
        phrases.filter { it.isNotBlank() }.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )
    var last = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(Color(0xFF1D1D1F), background = Color(0xFFFEF08A), fontWeight = FontWeight.SemiBold)) {
            append(match.value)
        }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

// ──────────────────────────────────────────────────────────────────────────────
// Summary / Placeholder cards
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun SummaryCard(summary: String) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(2.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    "WHY IT'S A SCAM",
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color         = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, lineHeight = 24.sp)
        }
    }
}

@Composable
fun PlaceholderCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape  = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(24.dp),
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            fontStyle = FontStyle.Italic,
        )
    }
}