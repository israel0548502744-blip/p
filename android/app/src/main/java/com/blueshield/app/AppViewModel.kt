package com.blueshield.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blueshield.app.engine.Exporter
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.VideoMeta
import com.blueshield.app.service.ProcessingRepository
import com.blueshield.app.service.ProcessingService
import com.blueshield.core.CensorSettings
import com.blueshield.core.gender.Override
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("blueshield", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _video = MutableStateFlow<VideoMeta?>(null)
    val video: StateFlow<VideoMeta?> = _video.asStateFlow()
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<CensorSettings> = _settings.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _showResult = MutableStateFlow(false)
    val showResult: StateFlow<Boolean> = _showResult.asStateFlow()
    private val _draft = MutableStateFlow<Map<Int, Override>>(emptyMap())
    val draftOverrides: StateFlow<Map<Int, Override>> = _draft.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    val job: StateFlow<JobState> = ProcessingRepository.state
    private val thumbs = HashMap<Int, Bitmap?>()

    init {
        viewModelScope.launch {
            ProcessingRepository.state.collect { s ->
                when (s.stage) {
                    JobState.Stage.COMPLETE -> {
                        _showResult.value = true
                        _draft.value = s.overrides
                        _saved.value = false
                        thumbs.clear()
                    }
                    JobState.Stage.ERROR -> _error.value = "Processing failed: ${s.error}"
                    JobState.Stage.CANCELLED -> _error.value = "Processing cancelled."
                    else -> Unit
                }
            }
        }
    }

    private fun loadSettings(): CensorSettings = prefs.getString("settings", null)
        ?.let { runCatching { json.decodeFromString(CensorSettings.serializer(), it).validated() }.getOrNull() } ?: CensorSettings()

    fun updateSettings(s: CensorSettings) {
        _settings.value = s
        prefs.edit().putString("settings", json.encodeToString(CensorSettings.serializer(), s)).apply()
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        _error.value = null
        _loading.value = true
        viewModelScope.launch {
            try {
                _video.value = withContext(Dispatchers.IO) { VideoMeta.probe(getApplication(), uri) }
                _showResult.value = false
                ProcessingRepository.reset()
            } catch (e: Throwable) {
                _error.value = e.message ?: "Could not read this video."
            } finally {
                _loading.value = false
            }
        }
    }

    fun clearVideo() {
        _video.value = null
        _showResult.value = false
        ProcessingRepository.processor?.releaseAnalysis()
        ProcessingRepository.reset()
    }

    fun start() {
        val v = _video.value ?: return
        _error.value = null
        _showResult.value = false
        ProcessingService.start(getApplication(), ProcessingService.Job.Full(v, _settings.value.validated()))
    }

    fun pause() = ProcessingRepository.processor?.pause()
    fun resume() = ProcessingRepository.processor?.resume()
    fun cancel() = ProcessingRepository.processor?.cancel()

    fun setOverride(id: Int, o: Override) {
        _draft.value = _draft.value + (id to o)
    }

    fun applyOverrides() {
        val overrides = _draft.value.filterValues { it != Override.AUTO }
        ProcessingService.start(getApplication(), ProcessingService.Job.Rerender(overrides))
    }

    fun adjust() {
        _showResult.value = false
    }

    fun thumbnail(id: Int): Bitmap? = thumbs.getOrPut(id) { ProcessingRepository.processor?.personThumbnail(id) }

    fun save() {
        val out = job.value.output ?: return
        val name = (_video.value?.name?.substringBeforeLast('.') ?: "video") + "_blueshield.mp4"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Exporter.saveToGallery(getApplication(), out, name) }
                _saved.value = true
            } catch (e: Throwable) {
                _error.value = "Could not save: ${e.message}"
            }
        }
    }

    fun shareIntent(): Intent? = job.value.output?.let { Exporter.shareIntent(getApplication(), it) }

    fun dismissError() {
        _error.value = null
    }
}
