package com.blueshield.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blueshield.app.engine.BrushStroke
import com.blueshield.app.engine.Exporter
import com.blueshield.app.engine.History
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.PhotoLoader
import com.blueshield.app.engine.Processor
import com.blueshield.app.engine.Results
import com.blueshield.app.engine.VideoMeta
import com.blueshield.app.service.ProcessingRepository
import com.blueshield.app.service.ProcessingService
import com.blueshield.app.ui.I18n
import com.blueshield.app.ui.tr
import com.blueshield.core.CensorSettings
import com.blueshield.core.gender.Override
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

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
    /** A short confirmation shown at the bottom of the screen ("saved"). */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val _showResult = MutableStateFlow(false)
    val showResult: StateFlow<Boolean> = _showResult.asStateFlow()
    private val _draft = MutableStateFlow<Map<Int, Override>>(emptyMap())
    val draftOverrides: StateFlow<Map<Int, Override>> = _draft.asStateFlow()
    /** Video: people censored only within a time span (seconds), from the people list. */
    private val _ranges = MutableStateFlow<Map<Int, ClosedFloatingPointRange<Double>>>(emptyMap())
    val ranges: StateFlow<Map<Int, ClosedFloatingPointRange<Double>>> = _ranges.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    val job: StateFlow<JobState> = ProcessingRepository.state

    private val _ui = MutableStateFlow(UiPrefs.load(prefs))
    val ui: StateFlow<UiPrefs> = _ui.asStateFlow()
    private val _presets = MutableStateFlow(Presets.load(prefs))
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    /** A picked photo (processed in-app: one frame takes seconds, no background service needed). */
    data class PhotoItem(val uri: Uri, val name: String, val preview: Bitmap)
    private val _photo = MutableStateFlow<PhotoItem?>(null)
    val photo: StateFlow<PhotoItem?> = _photo.asStateFlow()
    private val _photoResult = MutableStateFlow<Processor.PhotoResult?>(null)
    val photoResult: StateFlow<Processor.PhotoResult?> = _photoResult.asStateFlow()
    private val _photoBusy = MutableStateFlow(false)
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()
    /** "Ask whom to censor" is on: the photo is analysed and waits for the choice before it is kept. */
    private val _choosingPhoto = MutableStateFlow(false)
    val choosingPhoto: StateFlow<Boolean> = _choosingPhoto.asStateFlow()
    /** Strokes painted by hand on the censored photo (kept across re-renders of the same photo). */
    private val _overview = MutableStateFlow<Processor.Overview?>(null)
    /** "Whom to censor": a picture with the people found marked on it. */
    val overview: StateFlow<Processor.Overview?> = _overview
    private val _strokes = MutableStateFlow<List<BrushStroke>>(emptyList())
    val strokes: StateFlow<List<BrushStroke>> = _strokes.asStateFlow()
    private val thumbs = HashMap<Int, Bitmap?>()

    /** The part of the picked video to censor (seconds); null = all of it. */
    private val _trim = MutableStateFlow<Pair<Double, Double>?>(null)
    val trim: StateFlow<Pair<Double, Double>?> = _trim.asStateFlow()
    /** Several videos picked at once, waiting to be censored one after another. */
    private val _queue = MutableStateFlow<List<VideoMeta>>(emptyList())
    val queue: StateFlow<List<VideoMeta>> = _queue.asStateFlow()

    /** Several photos at once: each is censored with the current settings and kept (and saved with auto-save on). */
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
    /** The swipeable viewer over recent results: which ones, and where it opens. */
    data class Viewer(val ids: List<Long>, val start: Int)
    private val _viewer = MutableStateFlow<Viewer?>(null)
    val viewer: StateFlow<Viewer?> = _viewer.asStateFlow()
    /** The recent-results entry of the photo / video on screen: a re-render with changes takes its place. */
    private var photoEntry: Long? = null
    private var videoEntry: Long? = null
    /** The last video job whose finish was handled (the state outlives this view model). */
    private var handledOutput: String? = prefs.getString("handledOutput", null)

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
                        thumbs.clear()
                        videoEntry = s.entryId
                        refreshHistory()
                        _saved.value = _history.value.firstOrNull { it.id == s.entryId }?.gallery != null
                        val key = s.output?.path
                        if (key != null && key != handledOutput) {
                            handledOutput = key
                            prefs.edit().putString("handledOutput", key).apply()
                            s.saveError?.let { _error.value = tr("השמירה בגלריה נכשלה: {0}", it) }
                            if (s.queueIds.size > 1) {
                                if (s.queueFailed.isNotEmpty()) _error.value = tr("לא הצליחו ({0}): {1}", s.queueFailed.size, s.queueFailed.joinToString(", "))
                                _queue.value = emptyList()
                                _viewer.value = Viewer(s.queueIds, 0)
                            } else if (_saved.value) {
                                _toast.value = tr("נשמר בגלריה").also { Growth.recordSuccess(getApplication()) }
                            }
                        }
                    }
                    JobState.Stage.CHOOSING -> {
                        thumbs.clear()
                        _overview.value = null
                        launch(Dispatchers.IO) { _overview.value = runCatching { ProcessingRepository.processor?.videoOverview() }.getOrNull() }
                    }
                    JobState.Stage.ERROR -> _error.value = tr("העיבוד נכשל: {0}", s.error?.lines()?.take(4)?.joinToString("\n"))
                    JobState.Stage.CANCELLED -> _error.value = tr("העיבוד בוטל.")
                    else -> Unit
                }
            }
        }
    }

    private suspend fun refreshHistory() {
        _history.value = withContext(Dispatchers.IO) { History.list(getApplication()) }
    }

    // ── preferences ─────────────────────────────────────────────────────────────────────────────

    fun updateUi(change: (UiPrefs) -> UiPrefs) {
        val old = _ui.value
        val u = change(old)
        _ui.value = u
        u.save(prefs)
        if (u.language != old.language) I18n.load(getApplication(), u.language)
    }

    private fun loadSettings(): CensorSettings = prefs.getString("settings", null)
        ?.let { runCatching { json.decodeFromString(CensorSettings.serializer(), it).validated() }.getOrNull() } ?: CensorSettings()

    fun updateSettings(s: CensorSettings) {
        _settings.value = s
        prefs.edit().putString("settings", json.encodeToString(CensorSettings.serializer(), s)).apply()
    }

    fun applyPreset(p: Preset) {
        updateSettings(p.settings.copy(color = if (p.name in Presets.BUILT_IN.map { it.name } && p.settings.fill != "clothing") _settings.value.color else p.settings.color))
        _toast.value = tr("התבנית \"{0}\" הופעלה", tr(p.name))
    }

    fun savePreset(name: String) {
        val n = name.trim().ifEmpty { return }
        _presets.value = _presets.value.filter { it.name != n } + Preset(n, _settings.value)
        Presets.save(prefs, _presets.value)
        _toast.value = tr("התבנית נשמרה")
    }

    fun deletePreset(p: Preset) {
        _presets.value = _presets.value - p
        Presets.save(prefs, _presets.value)
    }

    fun dismissToast() {
        _toast.value = null
    }

    // ── picking ─────────────────────────────────────────────────────────────────────────────────

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        _error.value = null
        if (isImage(uri)) {
            loadPhoto(uri)
            return
        }
        clearPhoto()
        videoEntry = null
        _queue.value = emptyList()
        _loading.value = true
        viewModelScope.launch {
            try {
                _video.value = withContext(Dispatchers.IO) { VideoMeta.probe(getApplication(), uri) }
                _trim.value = null
                _ranges.value = emptyMap()
                _showResult.value = false
                ProcessingRepository.reset()
            } catch (e: Throwable) {
                _error.value = e.message ?: tr("לא ניתן לקרוא את הסרטון.")
            } finally {
                _loading.value = false
            }
        }
    }

    private fun isImage(uri: Uri) = runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull()?.startsWith("image/") == true

    /** One file opens as picked; several photos run as a batch; several videos wait in a queue. */
    fun onPickedMany(uris: List<Uri>) {
        if (uris.size <= 1) return onPicked(uris.firstOrNull())
        val (images, videos) = uris.partition(::isImage)
        when {
            images.isNotEmpty() -> startBatch(images)
            videos.size == 1 -> onPicked(videos[0])
            else -> loadQueue(videos)
        }
    }

    private fun loadQueue(uris: List<Uri>) {
        clearPhoto()
        clearVideo()
        _loading.value = true
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { VideoMeta.probe(getApplication(), it) }.getOrNull() } }
            if (metas.size < uris.size) _error.value = tr("{0} מהקבצים לא נקראו כסרטון.", uris.size - metas.size)
            _queue.value = metas
            _loading.value = false
        }
    }

    fun removeFromQueue(m: VideoMeta) {
        _queue.value = _queue.value - m
    }

    fun clearQueue() {
        _queue.value = emptyList()
    }

    fun startQueue() {
        val q = _queue.value.ifEmpty { return }
        if (!allowed(Entitlements.Feature.VIDEO_QUEUE)) return
        _error.value = null
        ProcessingService.start(getApplication(), ProcessingService.Job.Queue(q, _settings.value.validated()))
    }

    private fun processor(): Processor =
        ProcessingRepository.processor ?: Processor(getApplication()).also { ProcessingRepository.processor = it }

    /** The format and JPEG quality censored photos are written in (the settings' quality: high / normal / small). */
    private fun preparePhotoOutput(uri: Uri) {
        val p = processor()
        val sourcePng = runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull() == "image/png"
        p.photoFormat = when (_ui.value.photoFormat) {
            "png" -> "png"
            "jpg" -> "jpg"
            else -> if (sourcePng) "png" else "jpg"
        }
        p.photoQuality = when (_settings.value.quality) {
            "high" -> 97
            "small" -> 85
            else -> 93
        }
    }

    private fun loadPhoto(uri: Uri, replace: Long? = null, autoStart: Boolean = false) {
        clearVideo()
        _queue.value = emptyList()
        photoEntry = replace
        _strokes.value = emptyList()
        _choosingPhoto.value = false
        _loading.value = true
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { PhotoLoader.load(getApplication(), uri, maxSide = 1600) }
                _photo.value = PhotoItem(uri, displayName(uri) ?: "photo.jpg", bmp)
                _photoResult.value = null
                _draft.value = emptyMap()
                if (autoStart) startPhoto()
            } catch (e: Throwable) {
                _error.value = e.message ?: tr("לא ניתן לקרוא את התמונה.")
            } finally {
                _loading.value = false
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ── batches of photos ───────────────────────────────────────────────────────────────────────

    private fun startBatch(uris: List<Uri>) {
        if (uris.size > Entitlements.FREE_BATCH && !allowed(Entitlements.Feature.BIG_BATCH)) return
        clearVideo()
        clearPhoto()
        _queue.value = emptyList()
        _error.value = null
        val settings = _settings.value.validated()
        _batch.value = BatchState(uris.size)
        batchJob = viewModelScope.launch {
            for ((i, uri) in uris.withIndex()) {
                val name = displayName(uri) ?: "photo_${i + 1}.jpg"
                _batch.value = _batch.value?.copy(current = name)
                try {
                    preparePhotoOutput(uri)
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
            val b = _batch.value
            if (b != null && !MainActivity.visible && b.ids.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    ProcessingService.notifyResults(getApplication(), b.ids.toLongArray(), tr("{0} תמונות צונזרו", b.ids.size), tr("הקישו כדי לדפדף ביניהן."))
                }
            }
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

    /**
     * Keeps a finished photo in the recent results and, with auto-save on, in the phone's gallery (an earlier
     * version of the same result is replaced in both).
     */
    private suspend fun keep(file: File, video: Boolean, name: String, source: String?, replace: Long?, protect: Set<Long> = emptySet()): History.Entry {
        val k = withContext(Dispatchers.IO) { Results.keep(getApplication(), file, video, name, source, replace, protect) }
        k.saveError?.let { _error.value = tr("השמירה בגלריה נכשלה: {0}", it) }
        refreshHistory()
        return k.entry
    }

    // ── the viewer over recent results ──────────────────────────────────────────────────────────

    fun openViewer(ids: List<Long>, start: Int) {
        _viewer.value = Viewer(ids, start)
    }

    fun closeViewer() {
        _viewer.value = null
    }

    /** From the "done" notification: open (or share) the results it names. */
    fun openFromNotification(ids: LongArray, share: Boolean): Intent? {
        if (ids.isEmpty()) return null
        viewModelScope.launch {
            refreshHistory()
            _viewer.value = Viewer(ids.toList(), 0)
        }
        if (!share) return null
        val files = History.list(getApplication()).filter { it.id in ids }.map { File(it.file) }
        return when (files.size) {
            0 -> null
            1 -> Exporter.shareIntent(getApplication(), files[0])
            else -> Exporter.shareManyIntent(getApplication(), files)
        }
    }

    /** Saves a recent result (full size, every change included) to the phone's gallery. */
    fun saveEntry(e: History.Entry) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                // already in the gallery: one more copy (the phone names it "… (1)"), the first one stays as it is
                val again = e.gallery != null
                val uri = withContext(Dispatchers.IO) { Exporter.saveOrReplace(app, File(e.file), Results.galleryName(e), e.video, null) }
                if (!again) withContext(Dispatchers.IO) { History.setGallery(app, e.id, uri.toString()) }
                refreshHistory()
                _batch.value = _batch.value?.let { b -> if (e.id in b.ids && !again) b.copy(saved = b.saved + 1) else b }
                if (e.id == photoEntry || e.id == videoEntry) _saved.value = true
                _toast.value = (if (again) tr("נשמר עותק נוסף בגלריה") else tr("נשמר בגלריה")).also { Growth.recordSuccess(getApplication()) }
            } catch (t: Throwable) {
                _error.value = tr("השמירה נכשלה: {0}", t.message)
            }
        }
    }

    fun saveEntries(ids: List<Long>) = _history.value.filter { it.id in ids && it.gallery == null }.forEach(::saveEntry)

    fun shareEntryIntent(e: History.Entry): Intent = Exporter.shareIntent(getApplication(), File(e.file))

    fun shareEntriesIntent(ids: List<Long>): Intent? =
        _history.value.filter { it.id in ids }.map { File(it.file) }.takeIf { it.isNotEmpty() }?.let { Exporter.shareManyIntent(getApplication(), it) }

    /** Straight to WhatsApp (or WhatsApp Business); null when neither is installed. */
    fun whatsAppIntent(file: File): Intent? {
        val pm = getApplication<Application>().packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { p ->
            runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false)
        } ?: return null
        val uri = androidx.core.content.FileProvider.getUriForFile(getApplication(), getApplication<Application>().packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = Exporter.mime(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun whatsAppFile(f: File): Intent? = whatsAppIntent(f).also {
        if (it == null) _error.value = tr("וואטסאפ לא מותקן בטלפון הזה.")
    }

    fun whatsAppEntry(e: History.Entry): Intent? = whatsAppFile(File(e.file))

    /** Removes a result from the app's recent results (a copy saved in the gallery stays there). */
    fun deleteEntry(e: History.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { History.delete(getApplication(), e.id) }
            refreshHistory()
            _viewer.value = _viewer.value?.let { v -> v.copy(ids = v.ids - e.id).takeIf { it.ids.isNotEmpty() } }
            _batch.value = _batch.value?.let { b -> b.copy(ids = b.ids - e.id) }
        }
    }

    /**
     * The system's "allow BlueShield to delete this photo?" request for the original of a result (Android 11+), or
     * null (with a message) when the original can't be deleted from here.
     */
    fun deleteOriginalRequest(e: History.Entry): IntentSender? {
        val src = e.source?.let(Uri::parse)
        if (src == null || Build.VERSION.SDK_INT < 30) {
            _error.value = tr("כאן אי אפשר למחוק את המקור. אפשר למחוק אותו בגלריה של הטלפון.")
            return null
        }
        val cr = getApplication<Application>().contentResolver
        // the photo picker hands out its own links; the gallery item behind it has the same number
        val candidates = buildList {
            add(src)
            src.lastPathSegment?.toLongOrNull()?.let { id ->
                add(android.content.ContentUris.withAppendedId(
                    if (e.video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id,
                ))
            }
        }
        for (u in candidates) {
            runCatching { return MediaStore.createDeleteRequest(cr, listOf(u)).intentSender }
        }
        _error.value = tr("כאן אי אפשר למחוק את המקור. אפשר למחוק אותו בגלריה של הטלפון.")
        return null
    }

    fun originalDeleted() {
        _toast.value = tr("המקור נמחק")
    }

    /** Opens a recent photo again from its original, for per-person changes; the new version replaces it. */
    fun editEntry(e: History.Entry) {
        val src = e.source?.let(Uri::parse) ?: return
        val readable = runCatching { getApplication<Application>().contentResolver.openInputStream(src)?.close() != null }.getOrDefault(false)
        if (!readable) {
            _error.value = tr("התמונה המקורית כבר לא זמינה (אולי נמחקה, או שהגיעה בשיתוף מאפליקציה אחרת). אפשר לבחור אותה שוב.")
            return
        }
        closeViewer()
        closeBatch()
        loadPhoto(src, replace = e.id, autoStart = true)
    }

    // ── one photo ───────────────────────────────────────────────────────────────────────────────

    /** Censor the picked photo; with "ask whom to censor" on, the result waits for the choice. */
    fun startPhoto() {
        val p = _photo.value ?: return
        if (_photoBusy.value) return // a second tap before the busy screen shows
        _error.value = null
        _photoBusy.value = true
        viewModelScope.launch {
            try {
                preparePhotoOutput(p.uri)
                val r = withContext(Dispatchers.Default) { processor().processPhoto(p.uri, _settings.value.validated()) }
                _draft.value = emptyMap()
                thumbs.clear()
                if (_ui.value.askWho && r.people.isNotEmpty()) {
                    _overview.value = withContext(Dispatchers.Default) { runCatching { processor().photoOverview() }.getOrNull() }
                    _photoResult.value = r
                    _choosingPhoto.value = true
                } else {
                    val rr = if (_strokes.value.isNotEmpty()) withContext(Dispatchers.Default) { processor().renderPhoto(emptyMap(), _strokes.value) } else r
                    _photoResult.value = rr
                    keepPhoto(rr)
                }
            } catch (e: Throwable) {
                _error.value = tr("העיבוד נכשל: {0}", e.message ?: e.javaClass.simpleName)
            } finally {
                _photoBusy.value = false
            }
        }
    }

    private suspend fun keepPhoto(r: Processor.PhotoResult) {
        val p = _photo.value
        val e = keep(r.file, false, p?.name ?: "photo.jpg", p?.uri?.toString(), photoEntry)
        photoEntry = e.id
        _saved.value = e.gallery != null
        // (gallery is set only when auto-save wrote this very version: see Results.keep)
        if (_saved.value) _toast.value = tr("נשמר בגלריה").also { Growth.recordSuccess(getApplication()) }
    }

    /** Re-paint the photo with the manual per-person choices and the hand-painted strokes. */
    fun applyPhotoOverrides() {
        val overrides = _draft.value.filterValues { it != Override.AUTO }
        if (_photoBusy.value) return
        _photoBusy.value = true
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { processor().renderPhoto(overrides, _strokes.value) }
                _photoResult.value = r
                _choosingPhoto.value = false
                keepPhoto(r)
            } catch (e: Throwable) {
                _error.value = tr("לא ניתן להחיל את השינויים: {0}", e.message)
            } finally {
                _photoBusy.value = false
            }
        }
    }

    /** The brush editor's strokes, applied at once. */
    fun setStrokes(list: List<BrushStroke>) {
        if (!allowed(Entitlements.Feature.BRUSH)) return
        if (list == _strokes.value && _photoResult.value != null) return // nothing changed: no re-render, no new copy
        _strokes.value = list
        applyPhotoOverrides()
    }

    /** Before anything is drawn: censor exactly the people ticked ([censor]), the others not. */
    fun confirmWho(censor: Map<Int, Boolean>) {
        _draft.value = censor.mapValues { if (it.value) Override.CENSOR else Override.KEEP }
        if (_choosingPhoto.value) applyPhotoOverrides() else applyOverrides()
    }

    fun savePhoto() = saveCurrent(photoEntry)

    private fun saveCurrent(id: Long?) {
        val e = _history.value.firstOrNull { it.id == id } ?: return
        saveEntry(e)
    }

    fun currentPhotoEntry(): History.Entry? = _history.value.firstOrNull { it.id == photoEntry }
    fun currentVideoEntry(): History.Entry? = _history.value.firstOrNull { it.id == videoEntry }

    fun sharePhotoIntent(): Intent? = _photoResult.value?.file?.let { Exporter.shareIntent(getApplication(), it) }

    /** Back from the photo result to the photo + settings. */
    fun adjustPhoto() {
        _photoResult.value = null
        _choosingPhoto.value = false
    }

    fun clearPhoto() {
        _photo.value = null
        _photoResult.value = null
        _choosingPhoto.value = false
        _strokes.value = emptyList()
    }

    // ── one video ───────────────────────────────────────────────────────────────────────────────

    fun clearVideo() {
        _video.value = null
        _showResult.value = false
        _trim.value = null
        _ranges.value = emptyMap()
        ProcessingRepository.processor?.releaseAnalysis()
        ProcessingRepository.reset()
    }

    fun setTrim(t: Pair<Double, Double>?) {
        _trim.value = t
    }

    fun start() {
        val v = _video.value ?: return
        _error.value = null
        _showResult.value = false
        _ranges.value = emptyMap()
        ProcessingService.start(getApplication(), ProcessingService.Job.Full(v, _settings.value.validated(), analyseOnly = _ui.value.askWho, trim = _trim.value))
    }

    fun pause() = ProcessingRepository.processor?.pause()
    fun resume() = ProcessingRepository.processor?.resume()
    fun cancel() = ProcessingRepository.processor?.cancel()

    fun setOverride(id: Int, o: Override) {
        _draft.value = _draft.value + (id to o)
    }

    /** Censor person [id] only between two moments of the video (null: the whole time, as decided). */
    fun setRange(id: Int, r: ClosedFloatingPointRange<Double>?) {
        _ranges.value = if (r == null) _ranges.value - id else _ranges.value + (id to r)
    }

    fun applyOverrides() {
        val overrides = _draft.value.filterValues { it != Override.AUTO }
        val v = _video.value
        ProcessingService.start(
            getApplication(),
            ProcessingService.Job.Rerender(overrides, _ranges.value, v?.name ?: "video.mp4", v?.uri?.toString()),
        )
    }

    fun adjust() {
        _showResult.value = false
    }

    fun thumbnail(id: Int): Bitmap? = thumbs.getOrPut(id) { ProcessingRepository.processor?.personThumbnail(id) }

    fun save() = saveCurrent(videoEntry)

    fun shareIntent(): Intent? = job.value.output?.let { Exporter.shareIntent(getApplication(), it) }

    // ── telling others ──────────────────────────────────────────────────────────────────────────

    /** "Share BlueShield": a message with the download link. */
    fun appShareIntent(): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                tr("היי! מצאתי אפליקציה שמצנזרת אוטומטית תמונות וסרטונים: מכסה עור חשוף של נשים בצבע, והפנים נשארות גלויות. הכול נעשה בטלפון עצמו, בלי אינטרנט ובלי להעלות כלום לשום מקום.") +
                    "\n\n" + tr("להורדה (אנדרואיד):") + "\n" + BuildInfo.LATEST_APK +
                    "\n\n" + tr("אחרי ההורדה: פותחים את הקובץ ומאשרים התקנה ממקור זה."),
            )
        },
        null,
    )

    /**
     * "Report a mistake": only what the user ticked goes into the message, which they send themselves (e-mail,
     * WhatsApp…). The app itself has no internet access.
     */
    fun reportIntent(note: String, withCensored: Boolean, withOriginal: Boolean, withDetails: Boolean): Intent {
        val app = getApplication<Application>()
        val r = _photoResult.value
        val files = ArrayList<Uri>()
        fun provide(f: File) = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", f)
        if (withCensored) (r?.file ?: job.value.output)?.let { files += provide(it) }
        if (withOriginal) _photo.value?.uri?.let { files += it } ?: _video.value?.uri?.let { files += it }
        val text = buildString {
            append(tr("דיווח על טעות ב-BlueShield")).append("\n\n")
            if (note.isNotBlank()) append(note.trim()).append("\n\n")
            if (withDetails) {
                append("BlueShield ${BuildInfo.version} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
                append(json.encodeToString(CensorSettings.serializer(), _settings.value)).append('\n')
                val people = r?.people ?: job.value.people
                for (p in people) append("#${p.id} ${p.gender} ${(p.confidence * 100).toInt()}% age=${p.age?.toInt()} censored=${p.censored}\n")
            }
        }
        val send = if (files.size > 1) Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, files); type = "*/*" }
        else Intent(Intent.ACTION_SEND).apply { type = if (files.isEmpty()) "text/plain" else "*/*"; files.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) } }
        send.putExtra(Intent.EXTRA_SUBJECT, tr("דיווח על טעות ב-BlueShield"))
        send.putExtra(Intent.EXTRA_TEXT, text)
        if (SUPPORT_EMAIL.isNotEmpty()) send.putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, tr("שליחת הדיווח"))
    }

    /** "Contact us": an e-mail to the developer when an address is set, otherwise any app the user picks. */
    fun contactIntent(): Intent {
        val body = "\n\n—\nBlueShield ${BuildInfo.version} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val i = if (SUPPORT_EMAIL.isNotEmpty()) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
        else Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        i.putExtra(Intent.EXTRA_SUBJECT, "BlueShield")
        i.putExtra(Intent.EXTRA_TEXT, body)
        return Intent.createChooser(i, tr("יצירת קשר"))
    }

    /** A feature of the (future) paid plan: everything is open for now, see [Entitlements]. */
    private fun allowed(f: Entitlements.Feature): Boolean {
        if (Entitlements.has(f)) return true
        _error.value = tr("זו תכונה של BlueShield Pro.")
        return false
    }

    fun dismissError() {
        _error.value = null
    }

    companion object {
        /** The developer's address for "contact us" and mistake reports; empty = the user picks where to send. */
        const val SUPPORT_EMAIL = "israel05466313522@gmail.com"
    }
}
