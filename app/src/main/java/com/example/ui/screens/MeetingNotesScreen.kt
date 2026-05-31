package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.data.database.AppDatabase
import com.example.data.database.Meeting
import kotlinx.coroutines.launch
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.SkyBlue
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.viewmodel.MeetingViewModel
import com.example.ui.viewmodel.RecordingMode
import com.example.ui.viewmodel.SimulationScenario
import com.example.ui.viewmodel.SynthesisState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingNotesScreen(
    viewModel: MeetingViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val meetings by viewModel.meetings.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedMeeting by viewModel.selectedMeeting.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val recordingMode by viewModel.recordingMode.collectAsState()
    val synthesisState by viewModel.synthesisState.collectAsState()
    val currentAmplitudes by viewModel.currentAmplitudes.collectAsState()
    val simulatedLines by viewModel.simulatedDialogueLines.collectAsState()
    val transcriptBuffer by viewModel.transcriptBuffer.collectAsState()

    var showRecordingSetup by remember { mutableStateOf(false) }
    var activeCategoryFilter by remember { mutableStateOf("All") }
    var customMeetingTitle by remember { mutableStateOf("") }
    var showManualAddDialog by remember { mutableStateOf(false) }

    // Speech permissions trigger
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRealRecording()
            showRecordingSetup = false
        } else {
            Toast.makeText(context, "Microphone access is required to record speech notes", Toast.LENGTH_LONG).show()
        }
    }

    // Filter meeting items dynamically beside search
    val filteredMeetings = remember(meetings, activeCategoryFilter) {
        when (activeCategoryFilter) {
            "Favorites" -> meetings.filter { it.isFavorite }
            "Audio Notes" -> meetings.filter { it.audioPath != null }
            "Simulations" -> meetings.filter { it.audioPath == null }
            else -> meetings
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                isRecording -> {
                    // Active recording and live transcription view
                    ActiveRecorderHUD(
                        durationSec = recordingDuration,
                        mode = recordingMode,
                        amplitudes = currentAmplitudes,
                        dialogueLines = simulatedLines,
                        transcript = transcriptBuffer,
                        onStop = { viewModel.stopRecording(customMeetingTitle.takeIf { it.isNotBlank() }) },
                        onCancel = {
                            viewModel.cancelActiveSession() // stops and cancels
                            Toast.makeText(context, "Session cancelled.", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                selectedMeeting != null -> {
                    // Detailed meeting viewer
                    MeetingDetailView(
                        meeting = selectedMeeting!!,
                        viewModel = viewModel,
                        onBack = {
                            viewModel.audioPlayer.stopAudio()
                            viewModel.selectMeeting(null)
                        }
                    )
                }
                else -> {
                    // Dashboard standard layout
                    DashboardLayout(
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        activeFilter = activeCategoryFilter,
                        onFilterChange = { activeCategoryFilter = it },
                        meetings = filteredMeetings,
                        onMeetingClick = { viewModel.selectMeeting(it) },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        onRecordClick = { showRecordingSetup = true },
                        onManualAddClick = { showManualAddDialog = true }
                    )
                }
            }
        }

        // Setup recording session floating overlay
        if (showRecordingSetup) {
            RecordingSetupDialog(
                onDismiss = { showRecordingSetup = false },
                onChooseMicrophone = {
                    customMeetingTitle = ""
                    val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRealRecording()
                        showRecordingSetup = false
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onChooseScenario = { scenario ->
                    customMeetingTitle = "Simulation: ${scenario.title}"
                    viewModel.startSimulatedMeeting(scenario)
                    showRecordingSetup = false
                }
            )
        }

        // Manual transcript paste Dialog
        if (showManualAddDialog) {
            ManualAddDialog(
                onDismiss = { showManualAddDialog = false },
                onSave = { title, content ->
                    viewModel.manuallyInsertMeeting(title, content)
                    showManualAddDialog = false
                }
            )
        }

        // Dynamic Loading state of Gemini Synthesizing
        when (val state = synthesisState) {
            is SynthesisState.Loading -> {
                GeminiSynthesisOverlay(message = state.message)
            }
            is SynthesisState.Error -> {
                ErrorDisplayDialog(
                    message = state.message,
                    onDismiss = { viewModel.clearSynthesisError() }
                )
            }
            else -> {}
        }
    }
}

// FORMAT HELPER: convert seconds of meeting duration into formatted summary e.g 2m 14s
fun formatDuration(seconds: Int): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hrs > 0 -> "${hrs}h ${mins}m"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}

// --- SUB-SCREEN COMPOSABLES ---

@Composable
fun DashboardLayout(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    activeFilter: String,
    onFilterChange: (String) -> Unit,
    meetings: List<Meeting>,
    onMeetingClick: (Meeting) -> Unit,
    onFavoriteToggle: (Meeting) -> Unit,
    onRecordClick: () -> Unit,
    onManualAddClick: () -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onRecordClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .padding(8.dp)
                    .testTag("record_meeting_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "New Meeting Record session",
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Branding details
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Meeting Notes",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Summarize conversations with Gemini AI",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate400
                    )
                }

                IconButton(
                    onClick = onManualAddClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Slate800,
                        contentColor = SkyBlue
                    ),
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Manual Note Add", modifier = Modifier.size(22.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Custom Glassmorphic Search Bar
            TextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search title, transcripts, summaries...", color = Slate400) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = Slate400) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear search", tint = Slate400)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Slate800,
                    unfocusedContainerColor = Slate800,
                    disabledContainerColor = Slate800,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_meetings_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Filters horizontal row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf("All", "Favorites", "Audio Notes", "Simulations")
                filters.forEach { filter ->
                    val isSelected = activeFilter == filter
                    val background = if (isSelected) SkyBlue else Slate800
                    val textColor = if (isSelected) Color.White else Slate300
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(background)
                            .clickable { onFilterChange(filter) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = filter,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section Info
            Text(
                text = "${activeFilter} Sessions (${meetings.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Dynamic view content list
            if (meetings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.RecordVoiceOver,
                            contentDescription = "Empty icon state",
                            tint = Slate700,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matches found" else "Ready to Record",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                "Try adjusting your query words or spelling."
                            } else {
                                "Launch a standard speech session or click the + key to manually paste draft meeting minutes."
                            },
                            fontSize = 14.sp,
                            color = Slate400,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(meetings, key = { it.id }) { meeting ->
                        MeetingCard(
                            meeting = meeting,
                            onClick = { onMeetingClick(meeting) },
                            onFavoriteToggle = { onFavoriteToggle(meeting) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(64.dp)) // padding for FAB offset
                    }
                }
            }
        }
    }
}

@Composable
fun MeetingCard(
    meeting: Meeting,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    val dateString = remember(meeting.timestamp) {
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        sdf.format(Date(meeting.timestamp))
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = Slate800,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Slate700),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("meeting_item_card_${meeting.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Status Icon based on recording mode
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (meeting.audioPath != null) SkyBlue.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (meeting.audioPath != null) Icons.Default.Audiotrack else Icons.Default.Forum,
                    contentDescription = "Meeting mode icon indicator",
                    tint = if (meeting.audioPath != null) SkyBlue else Slate300,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Middle Details Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = dateString,
                        fontSize = 12.sp,
                        color = Slate400
                    )
                    if (meeting.durationSec > 0) {
                        Text(
                            text = "•",
                            fontSize = 12.sp,
                            color = Slate400
                        )
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Duration indicator icon",
                            tint = Slate400,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = formatDuration(meeting.durationSec),
                            fontSize = 12.sp,
                            color = Slate400
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right Favorite toggle Button
            IconButton(
                onClick = onFavoriteToggle,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (meeting.isFavorite) EmeraldMint else Slate700
                ),
                modifier = Modifier.testTag("favorite_button_${meeting.id}")
            ) {
                Icon(
                    imageVector = if (meeting.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Favorite status toggle trigger",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun ActiveRecorderHUD(
    durationSec: Int,
    mode: RecordingMode,
    amplitudes: List<Float>,
    dialogueLines: List<String>,
    transcript: String,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val durationText = remember(durationSec) {
        val mins = durationSec / 60
        val secs = durationSec % 60
        String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
    }

    var showCancelConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Safe drawing top spacers
        Spacer(modifier = Modifier.height(16.dp))

        // Session Information Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when (mode) {
                            is RecordingMode.Microphone -> "LIVE AUDIO RECORDING"
                            is RecordingMode.Simulation -> "MEETING SIMULATOR ACTIVE"
                            else -> "RECORDING SESSION"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                        color = SkyBlue
                    )
                    Text(
                        text = if (mode is RecordingMode.Simulation) mode.scenario.title else "Capturing hardware speech input...",
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Pulse timer centerpiece
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val infiniteTransition = rememberInfiniteTransition()
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            Box(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(SkyBlue.copy(alpha = 0.40f), Color.Transparent),
                                center = center,
                                radius = size.minDimension / 1.5f
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Slate800)
                        .border(2.dp, SkyBlue, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Recording mic spotlight icon",
                        tint = Color.Red,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = durationText,
                fontSize = 42.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                letterSpacing = (-1).sp
            )
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Sound Spectrum Bouncing visualizer Canvas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            amplitudes.forEachIndexed { _, ampValue ->
                val calculatedHeight = (ampValue * 60f).coerceIn(4f, 60f).dp
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(calculatedHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(SkyBlue, EmeraldMint)
                            )
                        )
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Live dialogue transcripts display box
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Slate700),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.2f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "LIVE SPEECH CAPTION REEL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Slate300,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (mode is RecordingMode.Simulation) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            dialogueLines.forEach { line ->
                                // Parse speaker and text
                                val speakerDelimiter = ":"
                                if (line.contains(speakerDelimiter)) {
                                    val parts = line.split(speakerDelimiter, limit = 2)
                                    val speaker = parts.firstOrNull()?.trim() ?: "Speaker"
                                    val msg = parts.getOrNull(1)?.trim() ?: ""
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.04f))
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = speaker,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SkyBlue
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = msg,
                                                fontSize = 13.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                } else {
                                    Text(text = line, fontSize = 13.sp, color = Slate300)
                                }
                            }
                        }
                    } else {
                        // Standard mic transcription info
                        Text(
                            text = if (transcript.isBlank()) {
                                "Listening... Try speaking clearly into your hardware microphone to record your thoughts. When finished, tap 'Complete & Synthesize' for Gemini's summary feedback."
                            } else {
                                transcript
                            },
                            fontSize = 14.sp,
                            color = if (transcript.isBlank()) Slate400 else Color.White,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Trigger CTA bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldMint, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1.3f)
                    .height(52.dp)
                    .testTag("stop_recording_button")
            ) {
                Icon(imageVector = Icons.Filled.AutoAwesome, contentDescription = "AI summarize")
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Complete & Summarize", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            OutlinedButton(
                onClick = { showCancelConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate300),
                border = BorderStroke(1.dp, Slate700),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(0.7f)
                    .height(52.dp)
                    .testTag("cancel_recording_button")
            ) {
                Text(text = "Cancel", fontSize = 14.sp)
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel Meeting?") },
            text = { Text("All transcription buffer content and audio of this session will be discarded. Do you wish to proceed?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirm = false
                        onCancel()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Discard File")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Resume Recording")
                }
            },
            containerColor = Slate800,
            titleContentColor = Color.White,
            textContentColor = Slate300
        )
    }
}

@Composable
fun MeetingDetailView(
    meeting: Meeting,
    viewModel: MeetingViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf("Summary") }
    var searchQueryInsideTranscript by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val dateFormatted = remember(meeting.timestamp) {
        val sdf = SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
        sdf.format(Date(meeting.timestamp))
    }

    // Interactive Action Items parsing
    val actionChecklist = remember(meeting.actionItems) {
        parseActionItems(meeting.actionItems)
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back to dashboard list", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Meeting Details",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Row {
                    // Star favorite toggle
                    IconButton(
                        onClick = { viewModel.toggleFavorite(meeting) },
                        modifier = Modifier.testTag("detail_favorite_toggle")
                    ) {
                        Icon(
                            imageVector = if (meeting.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Toggle favorite status",
                            tint = if (meeting.isFavorite) EmeraldMint else Slate400
                        )
                    }

                    // Re-synthesize (Gemini retry) Action button
                    IconButton(
                        onClick = {
                            viewModel.resynthesizeMeeting(meeting)
                            Toast.makeText(context, "Resynthesizing with standard Gemini prompt...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("detail_resynthesize_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Re-Synthesize analysis with Gemini AI",
                            tint = SkyBlue
                        )
                    }

                    // Delete trash core Action
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("detail_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete meeting note draft permanently",
                            tint = Color.Red
                        )
                    }
                }
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Title Banner
            Text(
                text = meeting.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Sub-details row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.CalendarToday, contentDescription = "Date", tint = Slate400, modifier = Modifier.size(14.dp))
                Text(text = dateFormatted, fontSize = 13.sp, color = Slate400)
                
                if (meeting.durationSec > 0) {
                    Text(text = "•", fontSize = 13.sp, color = Slate400)
                    Icon(imageVector = Icons.Default.Timer, contentDescription = "Duration icon details", tint = Slate400, modifier = Modifier.size(14.dp))
                    Text(text = formatDuration(meeting.durationSec), fontSize = 13.sp, color = Slate400)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Audio Player card if audio file exists
            if (meeting.audioPath != null) {
                AudioPlayerCard(
                    filePath = meeting.audioPath,
                    audioPlayer = viewModel.audioPlayer
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Custom Tab selection selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Slate800)
                    .padding(4.dp)
            ) {
                val tabs = listOf("Summary", "Checklist", "Transcript")
                tabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    val tabBg = if (isSelected) SkyBlue else Color.Transparent
                    val tabColor = if (isSelected) Color.White else Slate300
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tabBg)
                            .clickable { selectedTab = tab }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = when (tab) {
                                    "Summary" -> Icons.Default.Description
                                    "Checklist" -> Icons.Default.FactCheck
                                    else -> Icons.Default.Notes
                                },
                                contentDescription = "$tab Tab Icon",
                                tint = tabColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = tab,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = tabColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab View viewport panels
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    "Summary" -> {
                        Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Slate800),
                                border = BorderStroke(1.dp, Slate700),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "AI EXECUTIVE BRIEF",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = SkyBlue,
                                        letterSpacing = 1.2.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    // Robust Render of Markdown Summary Text
                                    MarkdownBodyText(text = meeting.summary ?: "Synthesizing summary info...")
                                }
                            }
                        }
                    }
                    "Checklist" -> {
                        if (actionChecklist.isEmpty() || meeting.actionItems.isNullOrBlank()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No Action Checklist extracted from this sync.", color = Slate400, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(actionChecklist) { item ->
                                    if (item.isRealCheckbox) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Slate800),
                                            border = BorderStroke(1.dp, if (item.isChecked) EmeraldMint.copy(alpha = 0.4f) else Slate700),
                                            shape = RoundedCornerShape(12.dp),
                                            onClick = {
                                                val toggledMeeting = toggleActionItemLine(meeting, item, !item.isChecked)
                                                coroutineScope.launch {
                                                    AppDatabase.getDatabase(context).meetingDao().updateMeeting(toggledMeeting)
                                                    viewModel.selectMeeting(toggledMeeting) // immediate refresh
                                                }
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Checkbox(
                                                    checked = item.isChecked,
                                                    onCheckedChange = { isChecked ->
                                                        val toggledMeeting = toggleActionItemLine(meeting, item, isChecked)
                                                        coroutineScope.launch {
                                                            AppDatabase.getDatabase(context).meetingDao().updateMeeting(toggledMeeting)
                                                            viewModel.selectMeeting(toggledMeeting)
                                                        }
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = EmeraldMint,
                                                        checkmarkColor = Color.White,
                                                        uncheckedColor = Slate400
                                                    ),
                                                    modifier = Modifier.padding(end = 12.dp)
                                                )
                                                
                                                Text(
                                                    text = item.text,
                                                    fontSize = 14.sp,
                                                    fontWeight = if (item.isChecked) FontWeight.Normal else FontWeight.Medium,
                                                    color = if (item.isChecked) Slate400 else Color.White,
                                                    lineHeight = 20.sp,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        // Just raw checklist bullet lines, headers, or instructions split
                                        if (item.text.isNotBlank() && !item.text.contains("DIVISION")) {
                                            Text(
                                                text = item.text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SkyBlue,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "Transcript" -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Highlights searching filter
                            TextField(
                                value = searchQueryInsideTranscript,
                                onValueChange = { searchQueryInsideTranscript = it },
                                placeholder = { Text("Highlight words inside transcript...", color = Slate400, fontSize = 12.sp) },
                                leadingIcon = { Icon(imageVector = Icons.Default.YoutubeSearchedFor, contentDescription = "Highlight", tint = Slate400, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Slate800,
                                    unfocusedContainerColor = Slate800,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Slate800),
                                    border = BorderStroke(1.dp, Slate700),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        HighlightableBodyText(
                                            text = meeting.transcript,
                                            query = searchQueryInsideTranscript
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Meeting Record?") },
            text = { Text("These meeting notes, generated summary and action lists will be deleted permanently. This operation cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteMeeting(meeting)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Go Back")
                }
            },
            containerColor = Slate800,
            titleContentColor = Color.White,
            textContentColor = Slate300
        )
    }
}

@Composable
fun AudioPlayerCard(
    filePath: String,
    audioPlayer: AudioPlayer
) {
    val isPlaying = audioPlayer.isPlaying
    val progress = audioPlayer.currentProgress
    val position = audioPlayer.currentPositionState
    val duration = audioPlayer.currentDuration

    val elapsedText = remember(position, duration) {
        val elapsedSec = position / 1000
        val totalSec = duration / 1000
        val eMins = elapsedSec / 60
        val eSecs = elapsedSec % 60
        val tMins = totalSec / 60
        val tSecs = totalSec % 60
        String.format(Locale.getDefault(), "%02d:%02d / %02d:%02d", eMins, eSecs, tMins, tSecs)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Slate800),
        border = BorderStroke(1.dp, SkyBlue.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play Button Circular Spot
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SkyBlue)
                    .clickable {
                        if (isPlaying) {
                            audioPlayer.pauseAudio()
                        } else {
                            audioPlayer.playAudio(filePath) {}
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Playback toggle play pause clicker",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Player timeline slider
            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = progress,
                    onValueChange = {}, // read-only slider track reflecting MediaPlayer progress
                    colors = SliderDefaults.colors(
                        thumbColor = SkyBlue,
                        activeTrackColor = SkyBlue,
                        inactiveTrackColor = Slate700
                    ),
                    modifier = Modifier.height(20.dp).fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recorded Memo File",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                    Text(
                        text = elapsedText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// Render summary markdown simply but nicely using Jetpack Compose rich paragraphs
@Composable
fun MarkdownBodyText(text: String) {
    val lines = text.split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("###") -> {
                    Text(
                        text = trimmedLine.replace("###", "").trim(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkyBlue,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                trimmedLine.startsWith("##") -> {
                    Text(
                        text = trimmedLine.replace("##", "").trim(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SkyBlue,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                    )
                }
                trimmedLine.startsWith("#") -> {
                    Text(
                        text = trimmedLine.replace("#", "").trim(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = SkyBlue,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                    )
                }
                trimmedLine.startsWith("* ") || trimmedLine.startsWith("- ") -> {
                    val rawBody = trimmedLine.substring(2).trim()
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBlue,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        
                        // Bold formatting processing in markdown bullets e.g **Highlight**: details
                        Text(
                            text = parseBoldMarkdown(rawBody),
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 22.sp
                        )
                    }
                }
                trimmedLine.startsWith("[ ]") || trimmedLine.startsWith("[x]") -> {
                    // fallbacks
                    Text(text = trimmedLine, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
                }
                else -> {
                    if (trimmedLine.isNotBlank() && !trimmedLine.contains("======DIVISION======")) {
                        Text(
                            text = parseBoldMarkdown(trimmedLine),
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

fun parseBoldMarkdown(rawText: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val parts = rawText.split("**")
        var isBoldState = false
        parts.forEach { part ->
            if (isBoldState) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = SkyBlue)) {
                    append(part)
                }
            } else {
                append(part)
            }
            isBoldState = !isBoldState
        }
    }
}

@Composable
fun HighlightableBodyText(text: String, query: String) {
    if (query.isBlank()) {
        Text(text = text, fontSize = 14.sp, color = Color.White, lineHeight = 22.sp)
        return
    }

    val annotatedString = buildAnnotatedString {
        val lowerText = text.lowercase(Locale.getDefault())
        val lowerQuery = query.lowercase(Locale.getDefault())
        var currentIndex = 0
        
        while (currentIndex < text.length) {
            val nextIndex = lowerText.indexOf(lowerQuery, currentIndex)
            if (nextIndex == -1) {
                append(text.substring(currentIndex))
                break
            }
            
            // Append safe background text
            append(text.substring(currentIndex, nextIndex))
            
            // Append formatted highlight segment
            withStyle(
                style = SpanStyle(
                    background = Color(0x66F59E0B), // golden highlight glow
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(nextIndex, nextIndex + query.length))
            }
            
            currentIndex = nextIndex + query.length
        }
    }

    Text(
        text = annotatedString,
        fontSize = 14.sp,
        color = Color.White,
        lineHeight = 22.sp
    )
}

@Composable
fun RecordingSetupDialog(
    onDismiss: () -> Unit,
    onChooseMicrophone: () -> Unit,
    onChooseScenario: (SimulationScenario) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Slate700),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Launch Meeting Notes App Session",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Record with a physical voice memo or run high-fidelity simulations to test features quickly.",
                    fontSize = 13.sp,
                    color = Slate400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Standard Microphone trigger button
                Button(
                    onClick = onChooseMicrophone,
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("launch_microphone_session")
                ) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = "Recording mic toggle")
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Microphone Voice Recording", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Separation Bar divider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f).height(1.dp).background(Slate700))
                    Text(
                        text = "OR SIMULATE MEETINGS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f).height(1.dp).background(Slate700))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scenario simulation buttons Lazy scrolling details
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SimulationScenario.values().forEach { scenario ->
                        OutlinedButton(
                            onClick = { onChooseScenario(scenario) },
                            border = BorderStroke(1.dp, Slate700),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Slate900, contentColor = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("launch_simulation_${scenario.name}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(text = scenario.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SkyBlue)
                                Text(
                                    text = scenario.desc,
                                    fontSize = 11.sp,
                                    color = Slate400,
                                    textAlign = TextAlign.Start,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = Slate300),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Go Back")
                }
            }
        }
    }
}

@Composable
fun ManualAddDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Slate700),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "New Custom Transcript",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Meeting Title") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkyBlue,
                        unfocusedBorderColor = Slate700,
                        focusedLabelColor = SkyBlue,
                        unfocusedLabelColor = Slate400,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("manual_entry_title")
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Paste Meeting Transcript / Notes") },
                    shape = RoundedCornerShape(12.dp),
                    minLines = 4,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkyBlue,
                        unfocusedBorderColor = Slate700,
                        focusedLabelColor = SkyBlue,
                        unfocusedLabelColor = Slate400,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("manual_entry_content")
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (title.isNotBlank() && content.isNotBlank()) {
                                onSave(title, content)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("save_manual_note_button")
                    ) {
                        Text("Save & AI Summarize", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Slate700),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate300),
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun GeminiSynthesisOverlay(message: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, SkyBlue.copy(alpha = 0.4f)),
            modifier = Modifier
                .width(280.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = SkyBlue,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(56.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "AI SYNTHESIZER WORKING",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SkyBlue,
                    letterSpacing = 1.2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun ErrorDisplayDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = "Security alert warnings", tint = Color.Red)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gemini Analysis Offset")
            }
        },
        text = {
            Column {
                Text(text = message, color = Slate300)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Security reminder: API keys stored in raw APK layouts are susceptible to extraction. Do not share raw packages in unsecured networks.",
                    fontSize = 11.sp,
                    color = Slate400,
                    lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = SkyBlue)) {
                Text("Acknowledge")
            }
        },
        containerColor = Slate800,
        titleContentColor = Color.White,
        textContentColor = Slate300
    )
}

// Interactivity Checkbox structures
fun parseActionItems(actionItemsStr: String?): List<ActionChecklistItem> {
    if (actionItemsStr.isNullOrBlank()) return emptyList()
    val lines = actionItemsStr.split("\n")
    return lines.mapIndexed { index, line ->
        val trimmed = line.trim()
        val isCheckbox = trimmed.startsWith("[ ]") || trimmed.startsWith("[x]") || trimmed.startsWith("[X]") ||
                trimmed.startsWith("* [ ]") || trimmed.startsWith("* [x]") || trimmed.startsWith("* [X]")
        
        if (isCheckbox) {
            val isChecked = trimmed.contains("[x]") || trimmed.contains("[X]")
            val cleanText = trimmed
                .replace("* [ ]", "")
                .replace("* [x]", "")
                .replace("* [X]", "")
                .replace("[ ]", "")
                .replace("[x]", "")
                .replace("[X]", "")
                .trim()
            ActionChecklistItem(index = index, originalLine = line, text = cleanText, isChecked = isChecked, isRealCheckbox = true)
        } else {
            ActionChecklistItem(index = index, originalLine = line, text = line, isChecked = false, isRealCheckbox = false)
        }
    }
}

data class ActionChecklistItem(
    val index: Int,
    val originalLine: String,
    val text: String,
    val isChecked: Boolean,
    val isRealCheckbox: Boolean
)

fun toggleActionItemLine(meeting: Meeting, item: ActionChecklistItem, isChecked: Boolean): Meeting {
    val lines = (meeting.actionItems ?: "").split("\n").toMutableList()
    if (item.index in lines.indices) {
        val original = lines[item.index]
        val prefix = if (original.startsWith("* ")) "* " else ""
        val newCheck = if (isChecked) "[x] " else "[ ] "
        lines[item.index] = prefix + newCheck + item.text
    }
    return meeting.copy(actionItems = lines.joinToString("\n"))
}
