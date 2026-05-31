package com.example.ui.viewmodel

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.data.database.AppDatabase
import com.example.data.database.Meeting
import com.example.data.remote.GeminiHelper
import com.example.data.remote.SummaryResult
import com.example.data.repository.MeetingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: MeetingRepository
    private val audioRecorder by lazy { AudioRecorder(application) }
    val audioPlayer by lazy { AudioPlayer() }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = MeetingRepository(database.meetingDao())
        prepopulateDatabaseIfEmpty()
    }

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Meetings Stream
    val meetings: StateFlow<List<Meeting>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                repository.allMeetings
            } else {
                repository.searchMeetings(query)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedMeeting = MutableStateFlow<Meeting?>(null)
    val selectedMeeting: StateFlow<Meeting?> = _selectedMeeting.asStateFlow()

    fun selectMeeting(meeting: Meeting?) {
        _selectedMeeting.value = meeting
    }

    // --- Recording & Simulation State ---
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0) // seconds
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _recordingMode = MutableStateFlow<RecordingMode>(RecordingMode.None)
    val recordingMode: StateFlow<RecordingMode> = _recordingMode.asStateFlow()

    private val _transcriptBuffer = MutableStateFlow("")
    val transcriptBuffer: StateFlow<String> = _transcriptBuffer.asStateFlow()

    private val _currentAmplitudes = MutableStateFlow<List<Float>>(List(15) { 0.1f })
    val currentAmplitudes: StateFlow<List<Float>> = _currentAmplitudes.asStateFlow()

    // Simulated speech lines remaining
    private val _simulatedDialogueLines = MutableStateFlow<List<String>>(emptyList())
    val simulatedDialogueLines: StateFlow<List<String>> = _simulatedDialogueLines.asStateFlow()

    private var recordingJob: Job? = null
    private var visualizerJob: Job? = null
    private var activeAudioFile: File? = null

    // Tracking synthesis state
    private val _synthesisState = MutableStateFlow<SynthesisState>(SynthesisState.Idle)
    val synthesisState: StateFlow<SynthesisState> = _synthesisState.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // --- Recorder Actions ---

    fun startRealRecording(fileName: String = "meeting_rec_${System.currentTimeMillis()}") {
        try {
            _recordingDuration.value = 0
            _transcriptBuffer.value = ""
            _recordingMode.value = RecordingMode.Microphone
            activeAudioFile = audioRecorder.startRecording(fileName)
            
            if (activeAudioFile != null) {
                _isRecording.value = true
                playBleepTone()
                startTimer()
                startAmplitudeVisualizer()
            } else {
                _synthesisState.value = SynthesisState.Error("Could not initialize microphone. Ensure audio permissions are enabled.")
                _recordingMode.value = RecordingMode.None
            }
        } catch (e: Exception) {
            _synthesisState.value = SynthesisState.Error("Failed to start voice recording: ${e.localizedMessage}")
            _recordingMode.value = RecordingMode.None
        }
    }

    fun startSimulatedMeeting(scenario: SimulationScenario) {
        _recordingDuration.value = 0
        _transcriptBuffer.value = ""
        _recordingMode.value = RecordingMode.Simulation(scenario)
        _isRecording.value = true
        _simulatedDialogueLines.value = emptyList()
        activeAudioFile = null // no real file for simulation
        
        playBleepTone()
        startTimer()
        startSimulationDialogue(scenario)
        startAmplitudeVisualizer()
    }

    private fun startTimer() {
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000)
                _recordingDuration.value += 1
            }
        }
    }

    private fun startAmplitudeVisualizer() {
        visualizerJob?.cancel()
        visualizerJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(120)
                val baseAmp = if (_recordingMode.value is RecordingMode.Microphone) {
                    audioRecorder.getAmplitude().toFloat() / 32767f
                } else {
                    // Simulate random bounce for simulation mode
                    (0.15f + Math.random().toFloat() * 0.75f)
                }
                
                val rawValue = baseAmp.coerceIn(0.05f, 1.0f)
                // Shift visualizer array left, push new
                val newAmps = _currentAmplitudes.value.toMutableList()
                newAmps.removeAt(0)
                newAmps.add(rawValue)
                _currentAmplitudes.value = newAmps
            }
        }
    }

    private var dialogueJob: Job? = null
    private fun startSimulationDialogue(scenario: SimulationScenario) {
        dialogueJob?.cancel()
        dialogueJob = viewModelScope.launch {
            val lines = scenario.dialogue.toMutableList()
            var delayMs = 2800L // show lines every ~3 seconds
            
            while (lines.isNotEmpty() && _isRecording.value) {
                val nextLine = lines.removeAt(0)
                
                // Add to transcripts text and queue list
                _transcriptBuffer.value += nextLine + "\n\n"
                _simulatedDialogueLines.value = _simulatedDialogueLines.value + nextLine
                
                delay(delayMs)
            }
        }
    }

    fun stopRecording(customTitle: String? = null) {
        if (!_isRecording.value) return
        
        playBleepTone()
        _isRecording.value = false
        recordingJob?.cancel()
        visualizerJob?.cancel()
        dialogueJob?.cancel()

        val finalTitle = customTitle ?: when (val mode = _recordingMode.value) {
            is RecordingMode.Simulation -> "Mock Sync: ${mode.scenario.title}"
            else -> "Meeting - ${SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date())}"
        }

        viewModelScope.launch {
            _synthesisState.value = SynthesisState.Loading("Synthesizing meeting insights using Gemini...")
            
            val recordedFile = if (_recordingMode.value is RecordingMode.Microphone) {
                audioRecorder.stopRecording()
            } else {
                null
            }

            val finalTranscript = _transcriptBuffer.value.trim()
            if (finalTranscript.isEmpty()) {
                _synthesisState.value = SynthesisState.Error("Transcription is empty. Could not process.")
                return@launch
            }

            // Call Gemini
            when (val result = GeminiHelper.summarizeMeeting(finalTranscript)) {
                is SummaryResult.Success -> {
                    val meeting = Meeting(
                        title = finalTitle,
                        timestamp = System.currentTimeMillis(),
                        transcript = finalTranscript,
                        summary = result.summary,
                        actionItems = result.actionItems,
                        audioPath = recordedFile?.absolutePath,
                        durationSec = _recordingDuration.value,
                        isFavorite = false
                    )
                    
                    val newId = repository.insertMeeting(meeting)
                    _selectedMeeting.value = meeting.copy(id = newId.toInt())
                    _synthesisState.value = SynthesisState.Success
                    Log.d("MeetingViewModel", "Meeting analyzed and saved successfully. ID: $newId")
                }
                is SummaryResult.Error -> {
                    // Fallback to offline raw record saving even if Gemini failed
                    val fallbackMeeting = Meeting(
                        title = finalTitle,
                        timestamp = System.currentTimeMillis(),
                        transcript = finalTranscript,
                        summary = "Unable to generate summary right now because:\n${result.message}",
                        actionItems = "Gemini synthesis was offline. You can click 'Re-synthesize' inside the meeting notes later.",
                        audioPath = recordedFile?.absolutePath,
                        durationSec = _recordingDuration.value,
                        isFavorite = false
                    )
                    val newId = repository.insertMeeting(fallbackMeeting)
                    _selectedMeeting.value = fallbackMeeting.copy(id = newId.toInt())
                    _synthesisState.value = SynthesisState.Error("Could not generate summary: ${result.message}\nYour meeting record has been saved locally.")
                }
            }
            _recordingMode.value = RecordingMode.None
        }
    }

    fun manuallyInsertMeeting(title: String, transcript: String) {
        viewModelScope.launch {
            _synthesisState.value = SynthesisState.Loading("Generating summaries for custom transcript...")
            when (val result = GeminiHelper.summarizeMeeting(transcript)) {
                is SummaryResult.Success -> {
                    val meeting = Meeting(
                        title = title,
                        timestamp = System.currentTimeMillis(),
                        transcript = transcript,
                        summary = result.summary,
                        actionItems = result.actionItems,
                        durationSec = 0
                    )
                    val newId = repository.insertMeeting(meeting)
                    _selectedMeeting.value = meeting.copy(id = newId.toInt())
                    _synthesisState.value = SynthesisState.Success
                }
                is SummaryResult.Error -> {
                    val fallback = Meeting(
                        title = title,
                        timestamp = System.currentTimeMillis(),
                        transcript = transcript,
                        summary = "Synthesis failed: ${result.message}",
                        actionItems = "Click 'Re-synthesize' above to try resolving.",
                        durationSec = 0
                    )
                    val newId = repository.insertMeeting(fallback)
                    _selectedMeeting.value = fallback.copy(id = newId.toInt())
                    _synthesisState.value = SynthesisState.Error("Generation failed: ${result.message}. Meeting saved locally.")
                }
            }
        }
    }

    fun resynthesizeMeeting(meeting: Meeting) {
        viewModelScope.launch {
            _synthesisState.value = SynthesisState.Loading("Re-transcribing & analyzing notes using Gemini...")
            when (val result = GeminiHelper.summarizeMeeting(meeting.transcript)) {
                is SummaryResult.Success -> {
                    val updated = meeting.copy(
                        summary = result.summary,
                        actionItems = result.actionItems
                    )
                    repository.updateMeeting(updated)
                    _selectedMeeting.value = updated
                    _synthesisState.value = SynthesisState.Success
                }
                is SummaryResult.Error -> {
                    _synthesisState.value = SynthesisState.Error("Re-synthesis failed: ${result.message}")
                }
            }
        }
    }

    fun toggleFavorite(meeting: Meeting) {
        viewModelScope.launch {
            val updated = meeting.copy(isFavorite = !meeting.isFavorite)
            repository.updateMeeting(updated)
            if (_selectedMeeting.value?.id == meeting.id) {
                _selectedMeeting.value = updated
            }
        }
    }

    fun deleteMeeting(meeting: Meeting) {
        viewModelScope.launch {
            audioPlayer.stopAudio()
            repository.deleteMeeting(meeting)
            _selectedMeeting.value = null
            
            // Delete audio file on disk, if exists
            meeting.audioPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    fun clearSynthesisError() {
        _synthesisState.value = SynthesisState.Idle
    }

    private fun playBleepTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_SYSTEM, 60)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Handler(Looper.getMainLooper()).postDelayed({
                toneGen.release()
            }, 500)
        } catch (_: Exception) {}
    }

    fun cancelActiveSession() {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingJob?.cancel()
        visualizerJob?.cancel()
        dialogueJob?.cancel()
        
        if (_recordingMode.value is RecordingMode.Microphone) {
            audioRecorder.stopRecording()
        }
        
        activeAudioFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        _recordingMode.value = RecordingMode.None
        _recordingDuration.value = 0
        _transcriptBuffer.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stopAudio()
        audioRecorder.stopRecording()
    }

    // --- Pre-population of database if empty ---
    private fun prepopulateDatabaseIfEmpty() {
        viewModelScope.launch {
            val database = AppDatabase.getDatabase(getApplication())
            // Collect first value of meetings
            val currentList = database.meetingDao().getAllMeetings()
            
            withContext(Dispatchers.IO) {
                val itemsCount = database.meetingDao().getAllMeetings().first().size

                if (itemsCount == 0) {
                    val prepopulated = listOf(
                        Meeting(
                            title = "Strategic Brand Launch Brief",
                            timestamp = System.currentTimeMillis() - 86400000L * 2, // 2 days ago
                            transcript = """
                                Marketing Lead: "Good morning. We need to lock in the social strategy for the Android launch. Our targeting needs to scale. What do you think, Sarah?"
                               Sarah: "We should focus our spend on short-video platforms for the first 14 days, driving download links directly to the store page."
                               Developer David: "I will make sure the Play build is finalized and signed by Friday afternoon. We must confirm the referral links are active."
                               Marketing Lead: "Perfect. Let's draft the PR release by Wednesday and submit for review. Thank you!"
                            """.trimIndent(),
                            summary = """
                                ### Executive Summary
                                The brand marketing and development teams coordinated on the launching strategies for the new Android build, locking in key timelines and campaign targets over the next few weeks.
                                
                                ### Key Highlights
                                * **Video Campaign**: Agreed on prioritising short-form videos for the first 14 days post-launch.
                                * **Store Redirection**: Verified that ads will lead directly to the Google Play Store installation page.
                                * **Referral System**: Highlighted the urgency of testing referral links live.
                            """.trimIndent(),
                            actionItems = """
                                [ ] Submit PR draft for management appraisal - Marketing Team (June 3)
                                [ ] Polishing & signing of production APK keys - David (June 5)
                                [ ] Finalise visual templates for video creatives - Sarah (June 2)
                            """.trimIndent(),
                            durationSec = 114
                        ),
                        Meeting(
                            title = "Auth Security Audit Sync",
                            timestamp = System.currentTimeMillis() - 3600000L * 5, // 5 hours ago
                            transcript = """
                                Tech Lead: "Security audit is ongoing. We identified a potential injection lock on historical database index queries. How is that fixed?"
                               Engineer Alice: "Yes, I created a parameterized query mapping yesterday. Tests are running. It completely prevents SELECT indexing bypasses."
                               Tech Lead: "Great. Alice, please verify session timeout duration is set to 15 minutes instead of the default 3 hours."
                               Engineer Alice: "Updating config file in standard profiles today. Will merge package in an hour."
                            """.trimIndent(),
                            summary = """
                                ### Executive Summary
                                The security engineering squad met to evaluate patch requirements highlighted in the security audit. The focus was database index queries and session timeout variables.
                                
                                ### Key Highlights
                                * **Query Safety**: Alice added query parameterization logic, resolving database injection risks.
                                * **Session Lifespan**: Agreed to reduce user session timeout from 3 hours to 15 minutes to prevent potential device token exposure.
                            """.trimIndent(),
                            actionItems = """
                                [ ] Set session duration properties to 900 seconds - Alice (June 1)
                                [ ] Run integration suite verifying query indexing indexes - Tech Lead (June 1)
                            """.trimIndent(),
                            durationSec = 230,
                            isFavorite = true
                        )
                    )
                    for (meeting in prepopulated) {
                        database.meetingDao().insertMeeting(meeting)
                    }
                    Log.i("MeetingViewModel", "Prepopulated local Room database with 2 standard meetings.")
                }
            }
        }
    }
}

sealed class RecordingMode {
    object None : RecordingMode()
    object Microphone : RecordingMode()
    data class Simulation(val scenario: SimulationScenario) : RecordingMode()
}

sealed class SynthesisState {
    object Idle : SynthesisState()
    data class Loading(val message: String) : SynthesisState()
    object Success : SynthesisState()
    data class Error(val message: String) : SynthesisState()
}

// Scenarios for simulation
enum class SimulationScenario(val title: String, val desc: String, val dialogue: List<String>) {
    StandaloneStandup(
        "Daily Technical Standup",
        "Short daily status sync between Project Lead, Backend Dev, and UX Engineer.",
        listOf(
            "Project Lead: Good morning team. Let's do a rapid update sync. Dave, how are we looking on the token validation fix?",
            "Backend Dev (Dave): The endpoint handshake issues are resolved. I modified the OkHttpClient timeouts to 60 seconds as per specifications, and checked that tokens are stored securely. Running sanity tests now.",
            "Project Lead: Excellent. Alice, any updates on edge-to-edge Compose formatting?",
            "UX Engineer (Alice): Handled perfectly! I implemented Scaffold layout offsets using standard dynamic WindowInsets padding. Everything looks sleek on foldable previews and screens of all aspect orientations.",
            "Project Lead: Perfect! Let's get the master branch merged by 2 PM for the sprint demo. Thanks everyone!"
        )
    ),
    MarketingLaunch(
        "CMO Launch Planning",
        "Growth strategy brainstorming between CMO, Performance Marketer, and Product Analyst.",
        listOf(
            "CMO: Thanks for joining. Let's configure the acquisition campaign. What channels are showing best click-through ratios in pre-trial tests?",
            "Growth Marketer (Rick): Visual banner channels are highly responsive. CTR is holding healthy at 4.2%. I suggest dedicating 70% of initial ad budgets to standard video banners with embedded application CTA clips.",
            "Product Analyst (Kate): Our onboarding conversion leaks if user onboarding takes more than 4 taps. We must optimize the welcome page layout.",
            "CMO: Rick, allocate the promo visuals accordingly by Thursday. Kate, prepare the layout funnel graphs for the review on Friday morning.",
            "Product Analyst (Kate): On it. I'll hook up detailed local analytical checkpoints by today."
        )
    ),
    IncidentPostmortem(
        "Server Outage Postmortem",
        "Urgent technical investigation review covering server CPU overload and query index patches.",
        listOf(
            "Lead Architect: Let's review yesterday's database CPU overloads. At 14:00 UTC, indexing locks blocked all CRUD logs. What triggered this?",
            "Devops Specialist (Max): A heavy, non-indexed SELECT filter on the historical meetings database was queried repeatedly. That maxed SQLite transaction bounds.",
            "Lead Architect: Oh, that makes sense. Do we have a direct mitigation path active?",
            "Database Engineer (Jane): Yes. I created custom composable index attributes on the meeting identifier columns. This immediately dropped SQLite latency values by 98%. Handled perfectly.",
            "Lead Architect: Fantastic. Let's schedule an automated regression test every Sunday at midnight to prevent future lockouts."
        )
    )
}
