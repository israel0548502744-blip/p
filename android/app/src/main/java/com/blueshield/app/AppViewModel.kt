package com.blueshield.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blueshield.app.engine.Exporter
import com.blueshield.app.engine.History
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.PhotoLoader
import com.blueshield.app.engine.Processor
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

    /** A picked photo (processed in-app: one frame takes seconds, no background service needed). */
    data class PhotoItem(val uri: Uri, val name: String, val preview: Bitmap)
    private val _photo = MutableStateFlow<PhotoItem?>(null)
    val photo: StateFlow<PhotoItem?> = _photo.asStateFlow()
    private val _photoResult = MutableStateFlow<Processor.PhotoResult?>(null)
    val photoResult: StateFlow<Processor.PhotoResult?> = _photoResult.asStateFlow()
    private val _photoBusy = MutableStateFlow(false)
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()
    private val thumbs = HashMap<Int, Bitmap?>()

    /** Several photos at once: each is censored with the current settings and saved to the gallery. */
    data class BatchState(
        val total: Int, val done: Int = 0, val saved: Int = 0, val failed: List<String> = emptyList(),
        val current: String? = null, val last: Bitmap? = null, val finished: Boolean = false,
        /** The batch's results in the recent-results store, in order: shown one after another when it ends. */
        val ids: List<Long> = emptyList(),
    )
    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()
    private var batchJob: kotlinx.coroutines.Job? = null

    /** Recent results kept in the app (last 20 photos, last 5 videos), newest first. */
    private val _history = MutableStateFlow<List<History.Entry>>(emptyList())
    val history: StateFlow<List<History.Entry>> = _history.asStateFlow()
    /** Every finished photo and video goes straight to the phone's gallery (on by default). */
    private val _autoSave = MutableStateFlow(prefs.getBoolean("autoSave", true))
    val autoSave: StateFlow<Boolean> = _autoSave.asStateFlow()
    /** The swipeable viewer over recent results: which ones, and where it opens. */
    data class Viewer(val ids: List<Long>, val start: Int)
    private val _viewer = MutableStateFlow<Viewer?>(null)
    val viewer: StateFlow<Viewer?> = _viewer.asStateFlow()
    /** The recent-results entry of the photo / video on screen: a re-render with changes takes its place. */
    private var photoEntry: Long? = null
    private var videoEntry: Long? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { History.cleanOutputs(getApplication(), ProcessingRepository.state.value.output) }
            refreshHistory()
        }
        viewModelScope.launch {
            ProcessingRepository.state.collect { s ->
                when (s.stage) {
                    JobState.Stage.COMPLETE -> {
                        _showResult.value = true
                        _draft.value = s.overrides
                        _saved.value = false
                        thumbs.clear()
                        s.output?.let { keepVideo(it) }
                    }
                    JobState.Stage.ERROR -> _error.value = "העיבוד נכשל: ${s.error?.lines()?.take(4)?.joinToString("\n")}"
                    JobState.Stage.CANCELLED -> _error.value = "העיבוד בוטל."
                    else -> Unit
                }
            }
        }
    }

    private suspend fun refreshHistory() {
        _history.value = withContext(Dispatchers.IO) { History.list(getApplication()) }
    }

    fun setAutoSave(on: Boolean) {
        _autoSave.value = on
        prefs.edit().putBoolean("autoSave", on).apply()
    }

    /**
     * Keeps a finished result in the recent results and, with auto-save on, in the phone's gallery (an earlier
     * version of the same result is replaced in both). Returns the entry.
     */
    private suspend fun keep(file: java.io.File, video: Boolean, name: String, source: String?, replace: Long?, protect: Set<Long> = emptySet()): History.Entry {
        val app = getApplication<Application>()
        var e = withContext(Dispatchers.IO) { History.add(app, file, video, name, source, replace, protect) }
        if (_autoSave.value) {
            try {
                val uri = withContext(Dispatchers.IO) { Exporter.saveOrReplace(app, java.io.File(e.file), galleryName(e), video, e.gallery) }
                withContext(Dispatchers.IO) { History.setGallery(app, e.id, uri.toString()) }
                e = e.copy(gallery = uri.toString())
            } catch (t: Throwable) {
                _error.value = "השמירה בגלריה נכשלה: ${t.message}"
            }
        }
        refreshHistory()
        return e
    }

    private fun galleryName(e: History.Entry) = e.name.substringBeforeLast('.') + if (e.video) "_blueshield.mp4" else "_blueshield.jpg"

    private fun keepVideo(output: java.io.File) {
        // the state survives this view model (it lives in the service's repository): keep each output once
        if (prefs.getString("keptOutput", null) == output.path) return
        prefs.edit().putString("keptOutput", output.path).apply()
        val v = _video.value
        viewModelScope.launch {
            val e = keep(output, true, v?.name ?: "video.mp4", v?.uri?.toString(), videoEntry)
            videoEntry = e.id
            _saved.value = e.gallery != null
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
        val mime = runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull()
        if (mime?.startsWith("image/") == true) {
            loadPhoto(uri)
            return
        }
        clearPhoto()
        videoEntry = null
        _loading.value = true
        viewModelScope.launch {
            try {
                _video.value = withContext(Dispatchers.IO) { VideoMeta.probe(getApplication(), uri) }
                _showResult.value = false
                ProcessingRepository.reset()
            } catch (e: Throwable) {
                _error.value = e.message ?: "לא ניתן לקרוא את הסרטון."
            } finally {
                _loading.value = false
            }
        }
    }

    private fun processor(): Processor =
        ProcessingRepository.processor ?: Processor(getApplication()).also { ProcessingRepository.processor = it }

    private fun loadPhoto(uri: Uri, replace: Long? = null, autoStart: Boolean = false) {
        clearVideo()
        photoEntry = replace
        _loading.value = true
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { PhotoLoader.load(getApplication(), uri, maxSide = 1600) }
                _photo.value = PhotoItem(uri, displayName(uri) ?: "photo.jpg", bmp)
                _photoResult.value = null
                _draft.value = emptyMap()
                if (autoStart) startPhoto()
            } catch (e: Throwable) {
                _error.value = e.message ?: "לא ניתן לקרוא את התמונה."
            } finally {
                _loading.value = false
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun onPickedMany(uris: List<Uri>) {
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> onPicked(uris[0])
            else -> startBatch(uris)
        }
    }

    private fun startBatch(uris: List<Uri>) {
        clearVideo()
        clearPhoto()
        _error.value = null
        val settings = _settings.value.validated()
        _batch.value = BatchState(uris.size)
        batchJob = viewModelScope.launch {
            for ((i, uri) in uris.withIndex()) {
                val name = displayName(uri) ?: "photo_${i + 1}.jpg"
                _batch.value = _batch.value?.copy(current = name)
                try {
                    val r = withContext(Dispatchers.Default) { processor().processPhoto(uri, settings) }
                    val e = keep(r.file, false, name, uri.toString(), null, protect = _batch.value?.ids.orEmpty().toSet())
                    // keep only a small preview: a batch of large photos must not fill the memory
                    val thumb = withContext(Dispatchers.Default) {
                        val s = 480f / maxOf(r.censored.width, r.censored.height)
                        Bitmap.createScaledBitmap(r.censored, maxOf(1, (r.censored.width * s).toInt()), maxOf(1, (r.censored.height * s).toInt()), true)
                    }
                    _batch.value = _batch.value?.let {
                        it.copy(done = it.done + 1, saved = it.saved + if (e.gallery != null) 1 else 0, last = thumb, ids = it.ids + e.id)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _batch.value = _batch.value?.let { it.copy(done = it.done + 1, failed = it.failed + name) }
                }
            }
            _batch.value = _batch.value?.copy(current = null, finished = true)
        }
    }

    /** Stop the batch (photos already saved stay in the gallery) or close the finished summary. */
    fun closeBatch() {
        batchJob?.cancel()
        batchJob = null
        _batch.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { History.trim(getApplication()) }
            refreshHistory()
        }
    }

    fun openViewer(ids: List<Long>, start: Int) {
        _viewer.value = Viewer(ids, start)
    }

    fun closeViewer() {
        _viewer.value = null
    }

    /** Saves a recent result (full size, every change included) to the phone's gallery. */
    fun saveEntry(e: History.Entry) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) { Exporter.saveOrReplace(app, java.io.File(e.file), galleryName(e), e.video, e.gallery) }
                withContext(Dispatchers.IO) { History.setGallery(app, e.id, uri.toString()) }
                refreshHistory()
                _batch.value = _batch.value?.let { b -> if (e.id in b.ids && e.gallery == null) b.copy(saved = b.saved + 1) else b }
            } catch (t: Throwable) {
                _error.value = "השמירה נכשלה: ${t.message}"
            }
        }
    }

    fun saveEntries(ids: List<Long>) = _history.value.filter { it.id in ids && it.gallery == null }.forEach(::saveEntry)

    fun shareEntryIntent(e: History.Entry): Intent = Exporter.shareIntent(getApplication(), java.io.File(e.file))

    fun shareEntriesIntent(ids: List<Long>): Intent? =
        _history.value.filter { it.id in ids }.map { java.io.File(it.file) }.takeIf { it.isNotEmpty() }?.let { Exporter.shareManyIntent(getApplication(), it) }

    /** Removes a result from the app's recent results (a copy saved in the gallery stays there). */
    fun deleteEntry(e: History.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { History.delete(getApplication(), e.id) }
            refreshHistory()
            _viewer.value = _viewer.value?.let { v -> v.copy(ids = v.ids - e.id).takeIf { it.ids.isNotEmpty() } }
            _batch.value = _batch.value?.let { b -> b.copy(ids = b.ids - e.id) }
        }
    }

    /** Opens a recent photo again from its original, for per-person changes; the new version replaces it. */
    fun editEntry(e: History.Entry) {
        val src = e.source?.let(Uri::parse) ?: return
        val readable = runCatching { getApplication<Application>().contentResolver.openInputStream(src)?.close() != null }.getOrDefault(false)
        if (!readable) {
            _error.value = "התמונה המקורית כבר לא זמינה (אולי נמחקה, או שהגיעה בשיתוף מאפליקציה אחרת). אפשר לבחור אותה שוב."
            return
        }
        closeViewer()
        closeBatch()
        loadPhoto(src, replace = e.id, autoStart = true)
    }

    /** Censor the picked photo. */
    fun startPhoto() {
        val p = _photo.value ?: return
        _error.value = null
        _photoBusy.value = true
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { processor().processPhoto(p.uri, _settings.value.validated()) }
                _photoResult.value = r
                _draft.value = emptyMap()
                val e = keep(r.file, false, p.name, p.uri.toString(), photoEntry)
                photoEntry = e.id
                _saved.value = e.gallery != null
            } catch (e: Throwable) {
                _error.value = "העיבוד נכשל: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _photoBusy.value = false
            }
        }
    }

    /** Re-paint the photo with the manual per-person choices. */
    fun applyPhotoOverrides() {
        val overrides = _draft.value.filterValues { it != Override.AUTO }
        _photoBusy.value = true
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { processor().renderPhoto(overrides) }
                _photoResult.value = r
                val p = _photo.value
                val e = keep(r.file, false, p?.name ?: "photo.jpg", p?.uri?.toString(), photoEntry)
                photoEntry = e.id
                _saved.value = e.gallery != null
            } catch (e: Throwable) {
                _error.value = "לא ניתן להחיל את השינויים: ${e.message}"
            } finally {
                _photoBusy.value = false
            }
        }
    }

    fun savePhoto() = saveCurrent(photoEntry)

    private fun saveCurrent(id: Long?) {
        val e = _history.value.firstOrNull { it.id == id } ?: return
        saveEntry(e)
        _saved.value = true
    }

    fun sharePhotoIntent(): Intent? = _photoResult.value?.file?.let { Exporter.shareIntent(getApplication(), it) }

    /** Back from the photo result to the photo + settings. */
    fun adjustPhoto() {
        _photoResult.value = null
    }

    fun clearPhoto() {
        _photo.value = null
        _photoResult.value = null
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

    fun save() = saveCurrent(videoEntry)

    fun shareIntent(): Intent? = job.value.output?.let { Exporter.shareIntent(getApplication(), it) }

    fun dismissError() {
        _error.value = null
    }
}
