package com.blueshield.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueshield.app.engine.History
import com.blueshield.app.engine.JobState
import com.blueshield.app.service.ProcessingService
import com.blueshield.app.ui.AboutScreen
import com.blueshield.app.ui.ActiveWork
import com.blueshield.app.ui.FlowHeader
import com.blueshield.app.ui.PlusMenu
import com.blueshield.app.ui.ProScreen
import com.blueshield.app.ui.ProfileDialog
import com.blueshield.app.ui.Stepper
import com.blueshield.app.ui.ToolActions
import com.blueshield.app.ui.ToolsScreen
import com.blueshield.app.ui.BatchScreen
import com.blueshield.app.ui.BlueShieldTheme
import com.blueshield.app.ui.BottomNav
import com.blueshield.app.ui.BrushScreen
import com.blueshield.app.ui.ChooseScreen
import com.blueshield.app.ui.DrawerContent
import com.blueshield.app.ui.DrawerItem
import com.blueshield.app.ui.EntryActions
import com.blueshield.app.ui.ErrorBanner
import com.blueshield.app.ui.ExtraActions
import com.blueshield.app.ui.FaqScreen
import com.blueshield.app.ui.GlassBackground
import com.blueshield.app.ui.HistoryScreen
import com.blueshield.app.ui.I18n
import com.blueshield.app.ui.Onboarding
import com.blueshield.app.ui.Page
import com.blueshield.app.ui.Palette
import com.blueshield.app.ui.PhotoHome
import com.blueshield.app.ui.PhotoResultScreen
import com.blueshield.app.ui.PresetsScreen
import com.blueshield.app.ui.PrivacyScreen
import com.blueshield.app.ui.ProcessingScreen
import com.blueshield.app.ui.QueueScreen
import com.blueshield.app.ui.ReportDialog
import com.blueshield.app.ui.ResultScreen
import com.blueshield.app.ui.SettingsScreen
import com.blueshield.app.ui.StartScreen
import com.blueshield.app.ui.Tab
import com.blueshield.app.ui.TopBar
import com.blueshield.app.ui.VideoHome
import com.blueshield.app.ui.ViewerScreen
import com.blueshield.app.ui.WhatsNewDialog
import com.blueshield.app.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(vm) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Photos / videos shared to the app ("Share → BlueShield"), the "done" notification's buttons, and the home
     * screen widget / quick settings tile ("pick a photo / video").
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            ).let(vm::onPickedMany)
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty().let(vm::onPickedMany)
            ProcessingService.ACTION_OPEN_ENTRY, ProcessingService.ACTION_SHARE_ENTRY -> {
                val ids = intent.getLongArrayExtra(ProcessingService.EXTRA_ENTRIES) ?: return
                vm.openFromNotification(ids, intent.action == ProcessingService.ACTION_SHARE_ENTRY)?.let { runCatching { startActivity(it) } }
            }
            ACTION_PICK_PHOTO, ACTION_PICK_VIDEO -> pendingPick.value = intent.action
        }
        intent?.getStringExtra(EXTRA_SCREEN)?.let { screen.value = it }
    }

    companion object {
        /** On screen (between onStart and onStop): a batch that ends while hidden posts a notification. */
        @Volatile var visible = false
        const val ACTION_PICK_PHOTO = "com.blueshield.app.PICK_PHOTO"
        const val ACTION_PICK_VIDEO = "com.blueshield.app.PICK_VIDEO"
        /** A picker the widget or tile asked for, opened once the screen is up. */
        val pendingPick = MutableStateFlow<String?>(null)
        /** A screen to open at start ("drawer", "tools", "projects", "settings", "pro", "plus"): the UI screenshots use it. */
        const val EXTRA_SCREEN = "com.blueshield.app.SCREEN"
        val screen = MutableStateFlow<String?>(null)
    }
}

/** What the home tab shows while something is being worked on (otherwise the start screen). */
private enum class Flow { START, VIDEO, QUEUE, PROCESSING, CHOOSE_VIDEO, RESULT, PHOTO, CHOOSE_PHOTO, PHOTO_RESULT, BATCH }

@Composable
private fun App(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (ui.theme) { "light" -> false; "system" -> systemDark; else -> true }
    // the colours are applied before the first frame that uses them
    remember(dark, ui.accent) { Palette.apply(dark, Color(ui.accent.toInt())); 0 }
    val lang = I18n.lang
    CompositionLocalProvider(LocalLayoutDirection provides if (I18n.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        BlueShieldTheme(ui.textScale) {
            androidx.compose.runtime.key(lang) {
                if (!ui.onboarded) Onboarding { vm.updateUi { it.copy(onboarded = true, seenVersion = BuildInfo.version) } }
                else Main(vm, ui)
            }
        }
    }
}

@Composable
private fun Main(vm: AppViewModel, ui: UiPrefs) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val context = androidx.compose.ui.platform.LocalContext.current
    val video by vm.video.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val showResult by vm.showResult.collectAsStateWithLifecycle()
    val draft by vm.draftOverrides.collectAsStateWithLifecycle()
    val ranges by vm.ranges.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val photo by vm.photo.collectAsStateWithLifecycle()
    val photoResult by vm.photoResult.collectAsStateWithLifecycle()
    val photoBusy by vm.photoBusy.collectAsStateWithLifecycle()
    val choosingPhoto by vm.choosingPhoto.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val viewer by vm.viewer.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val trim by vm.trim.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val pendingPick by MainActivity.pendingPick.collectAsStateWithLifecycle()
    val shotScreen by MainActivity.screen.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.HOME) }
    var page by remember { mutableStateOf<Page?>(null) }
    /** The work in progress was put aside (back during processing): the home screen shows a card back to it. */
    var minimized by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(ui.seenVersion != BuildInfo.version) }
    var appliedRanges by remember { mutableStateOf(ranges) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var crashReport by remember { mutableStateOf(CrashReporter.pending(context)?.also { CrashReporter.markSeen(context) }) }
    crashReport?.let { report ->
        CrashDialog(report, onShare = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }
            activity.startActivity(Intent.createChooser(send, null))
        }, onCopy = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BlueShield crash report", report))
        }, onClose = { crashReport = null })
    }
    if (whatsNew && crashReport == null && shotScreen == null) WhatsNewDialog {
        whatsNew = false
        vm.updateUi { it.copy(seenVersion = BuildInfo.version) }
    }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.dismissToast()
            if (Growth.shouldAskForRating(context)) Growth.askForRating(activity)
        }
    }

    // one picker for photos (one or several) and one for videos (one, or several in a queue)
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.onPickedMany(it) }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { vm.onPickedMany(it) }
    val choosePhotos = { tab = Tab.HOME; page = null; plusOpen = false; pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val chooseVideos = { tab = Tab.HOME; page = null; plusOpen = false; pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    LaunchedEffect(pendingPick) {
        when (pendingPick) {
            MainActivity.ACTION_PICK_PHOTO -> choosePhotos()
            MainActivity.ACTION_PICK_VIDEO -> chooseVideos()
        }
        MainActivity.pendingPick.value = null
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val withNotifications = { action: () -> Unit ->
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        action()
    }
    val deleteOriginal = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) vm.originalDeleted()
    }
    val askDeleteOriginal = { e: History.Entry? ->
        e?.let(vm::deleteOriginalRequest)?.let { deleteOriginal.launch(IntentSenderRequest.Builder(it).build()) }
        Unit
    }
    // Android 8–9 need the storage permission to save into the gallery
    if (Build.VERSION.SDK_INT < 29) {
        val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
    val start: (Intent) -> Unit = { runCatching { activity.startActivity(it) } }
    val entryActions = EntryActions(
        open = { i -> vm.openViewer(history.map { it.id }, i) },
        save = vm::saveEntry,
        share = { start(vm.shareEntryIntent(it)) },
        whatsApp = { e -> vm.whatsAppEntry(e)?.let(start) },
        delete = vm::deleteEntry,
    )
    val toProjects = { tab = Tab.PROJECTS; page = null }

    val flow = when {
        job.running -> Flow.PROCESSING
        job.stage == JobState.Stage.CHOOSING && video != null -> Flow.CHOOSE_VIDEO
        batch != null -> Flow.BATCH
        showResult && job.stage == JobState.Stage.COMPLETE && job.output != null && video != null -> Flow.RESULT
        choosingPhoto && photoResult != null -> Flow.CHOOSE_PHOTO
        photoResult != null -> Flow.PHOTO_RESULT
        photo != null -> Flow.PHOTO
        queue.isNotEmpty() -> Flow.QUEUE
        video != null -> Flow.VIDEO
        else -> Flow.START
    }
    // anything new to work on (picked, shared, finished) brings the home tab forward
    LaunchedEffect(flow) {
        minimized = false
        if (flow != Flow.START) { tab = Tab.HOME; page = null }
    }
    LaunchedEffect(viewer) { if (viewer != null) page = null }
    // the screenshots of the instrumented test open a tab, a page or the side menu directly
    LaunchedEffect(shotScreen) {
        when (shotScreen) {
            "drawer" -> drawer.open()
            "tools" -> tab = Tab.TOOLS
            "projects" -> tab = Tab.PROJECTS
            "settings" -> tab = Tab.SETTINGS
            "pro" -> page = Page.PRO
            "plus" -> plusOpen = true
        }
    }

    /** One step back inside a photo / video: what the arrow at the top and the phone's back button do. */
    val flowBack: () -> Unit = {
        when (flow) {
            Flow.RESULT -> vm.adjust()
            Flow.PHOTO_RESULT, Flow.CHOOSE_PHOTO -> vm.adjustPhoto()
            Flow.BATCH -> vm.closeBatch()
            Flow.PHOTO -> if (!photoBusy) vm.clearPhoto() else minimized = true
            Flow.VIDEO, Flow.CHOOSE_VIDEO -> vm.clearVideo()
            Flow.QUEUE -> vm.clearQueue()
            Flow.PROCESSING -> minimized = true
            Flow.START -> Unit
        }
    }
    val inFlow = viewer == null && page == null && tab == Tab.HOME && flow != Flow.START && !minimized

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = plusOpen) { plusOpen = false }
    BackHandler(enabled = viewer != null) { vm.closeViewer() }
    BackHandler(enabled = viewer == null && page != null) { page = null }
    BackHandler(enabled = viewer == null && page == null && tab != Tab.HOME) { tab = Tab.HOME }
    BackHandler(enabled = viewer == null && page == null && tab == Tab.HOME) {
        if (inFlow) flowBack() else activity.finish()
    }

    val drawerSelected = when {
        page == Page.PRESETS -> DrawerItem.PRESETS
        page == Page.PRIVACY -> DrawerItem.PRIVACY
        page == Page.FAQ -> DrawerItem.FAQ
        tab == Tab.FILES -> DrawerItem.FILES
        tab == Tab.PROJECTS -> DrawerItem.PROJECTS
        tab == Tab.TOOLS -> DrawerItem.TOOLS
        tab == Tab.SETTINGS -> DrawerItem.SETTINGS
        tab == Tab.HOME && page == null -> DrawerItem.HOME
        else -> null
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        // opened with the menu button only: a sideways swipe belongs to the photo viewer, sliders and the compare handle
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Palette.Ink900, drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
                DrawerContent(drawerSelected, ui.name, ui.email, onClose = { scope.launch { drawer.close() } }) { item ->
                    scope.launch { drawer.close() }
                    vm.closeViewer()
                    when (item) {
                        DrawerItem.HOME -> { tab = Tab.HOME; page = null; minimized = false }
                        DrawerItem.FILES -> { tab = Tab.FILES; page = null }
                        DrawerItem.TOOLS -> { tab = Tab.TOOLS; page = null }
                        DrawerItem.PROJECTS -> toProjects()
                        DrawerItem.BATCH -> choosePhotos()
                        DrawerItem.PRESETS -> page = Page.PRESETS
                        DrawerItem.SETTINGS -> { tab = Tab.SETTINGS; page = null }
                        DrawerItem.PRIVACY -> page = Page.PRIVACY
                        DrawerItem.FAQ -> page = Page.FAQ
                        DrawerItem.CONTACT -> runCatching { start(vm.contactIntent()) }
                        DrawerItem.SHARE -> runCatching { start(vm.appShareIntent()) }
                        DrawerItem.PROFILE -> profileOpen = true
                    }
                }
            }
        },
    ) {
        GlassBackground {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                when {
                    viewer != null -> FlowHeader(if (batch != null) tr("עריכה קבוצתית") else tr("הקבצים שלי"), vm::closeViewer)
                    page != null -> FlowHeader(pageTitle(page!!)) { page = null }
                    inFlow -> {
                        FlowHeader(flowTitle(flow), flowBack)
                        val (step, running) = when (flow) {
                            Flow.VIDEO, Flow.QUEUE -> 0 to false
                            Flow.PHOTO -> if (photoBusy) 1 to true else 0 to false
                            Flow.PROCESSING -> (if (job.passIndex == 2) 3 else 1) to !job.paused
                            Flow.CHOOSE_VIDEO, Flow.CHOOSE_PHOTO -> 2 to false
                            Flow.BATCH -> if (batch?.finished == true) 4 to false else 1 to true
                            else -> 4 to false
                        }
                        Stepper(step, running, Modifier.padding(bottom = 6.dp))
                    }
                    else -> TopBar(onMenu = { scope.launch { drawer.open() } }, onPro = { page = Page.PRO })
                }
                error?.let { ErrorBanner(it, vm::dismissError) }
                val screenKey: Any = when {
                    viewer != null -> "viewer"
                    page != null -> page!!
                    tab != Tab.HOME -> tab
                    minimized -> Flow.START
                    else -> flow
                }
                Box(Modifier.weight(1f)) {
                    AnimatedContent(screenKey, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen", modifier = Modifier.fillMaxSize()) { key ->
                        when (key) {
                            "viewer" -> viewer?.let { v ->
                                val entries = v.ids.mapNotNull { id -> history.firstOrNull { it.id == id } }
                                ViewerScreen(
                                    entries, v.start, batch = null,
                                    onSave = vm::saveEntry, onShare = { start(vm.shareEntryIntent(it)) },
                                    onEdit = vm::editEntry, onDelete = vm::deleteEntry,
                                    onSaveAll = { vm.saveEntries(v.ids) }, onShareAll = { vm.shareEntriesIntent(v.ids)?.let(start) },
                                    onClose = vm::closeViewer, onMore = null,
                                    onWhatsApp = { e -> vm.whatsAppEntry(e)?.let(start) }, onDeleteOriginal = askDeleteOriginal,
                                )
                            }
                            Page.PRIVACY -> PrivacyScreen()
                            Page.FAQ -> FaqScreen()
                            Page.PRO -> ProScreen()
                            Page.ABOUT -> AboutScreen(
                                onWhatsNew = { whatsNew = true }, onFaq = { page = Page.FAQ }, onPrivacy = { page = Page.PRIVACY },
                                onOnboarding = { vm.updateUi { it.copy(onboarded = false) } },
                            )
                            Page.PRESETS -> PresetsScreen(presets, settings, vm::applyPreset, vm::savePreset, vm::deletePreset)
                            Page.BRUSH -> photoResult?.let { r ->
                                if (photoBusy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(color = Palette.Shield400)
                                } else BrushScreen(
                                    r.censored, Color(com.blueshield.core.CensorSettings.parseColor(settings.color) or -0x1000000), strokes,
                                    onApply = { vm.setStrokes(it); page = null }, onCancel = { page = null },
                                )
                            }
                            Tab.PROJECTS, Tab.FILES -> HistoryScreen(
                                history, ui.autoSave, { v -> vm.updateUi { it.copy(autoSave = v) } },
                                actions = entryActions, onOpen = vm::openViewer, galleryOnly = key == Tab.FILES,
                            )
                            Tab.TOOLS -> ToolsScreen(
                                settings, ui, vm::updateSettings, vm::updateUi,
                                ToolActions(
                                    photo = choosePhotos, video = chooseVideos, batch = choosePhotos, queue = chooseVideos,
                                    presets = { page = Page.PRESETS }, privacy = { page = Page.PRIVACY },
                                    guide = { vm.updateUi { it.copy(onboarded = false) } }, faq = { page = Page.FAQ },
                                    report = { reportOpen = true }, about = { page = Page.ABOUT },
                                ),
                            )
                            Tab.SETTINGS -> SettingsScreen(
                                settings, enabled = !job.running && !photoBusy, onChange = vm::updateSettings,
                                ui = ui, onUi = vm::updateUi, onPresets = { page = Page.PRESETS },
                                onProfile = { profileOpen = true }, onAbout = { page = Page.ABOUT },
                            )
                            Flow.START -> StartScreen(
                                loading, history, ui.name, onPhoto = choosePhotos, onVideo = chooseVideos, onAll = toProjects,
                                actions = entryActions,
                                onGuide = { vm.updateUi { it.copy(onboarded = false) } }, onSettings = { tab = Tab.SETTINGS },
                                onPrivacy = { page = Page.PRIVACY }, onPresets = { page = Page.PRESETS },
                                active = if (flow != Flow.START) ActiveWork(
                                    if (flow == Flow.PROCESSING) tr("עיבוד הסרטון ממשיך ברקע") else tr("חזרה לעבודה שהתחלת"),
                                    if (flow == Flow.PROCESSING) job.percent.toInt() else null,
                                ) { minimized = false } else null,
                            )
                            Flow.VIDEO -> video?.let { v ->
                                VideoHome(
                                    v, settings, trim, vm::setTrim, onClear = vm::clearVideo, onEditSettings = { tab = Tab.SETTINGS },
                                    onStart = { withNotifications(vm::start) }, askWho = ui.askWho,
                                )
                            }
                            Flow.QUEUE -> QueueScreen(queue, settings, vm::removeFromQueue, onStart = { withNotifications(vm::startQueue) }, onClear = vm::clearQueue,
                                onEditSettings = { tab = Tab.SETTINGS })
                            Flow.PROCESSING -> ProcessingScreen(job, vm::pause, vm::resume, vm::cancel)
                            Flow.CHOOSE_VIDEO -> ChooseScreen(
                                job.people, vm::thumbnail, busy = false, onConfirm = vm::confirmWho, onCancel = vm::clearVideo, video = true, overview = overview,
                            )
                            Flow.CHOOSE_PHOTO -> photoResult?.let { r ->
                                ChooseScreen(
                                    r.people, vm::thumbnail, photoBusy, onConfirm = vm::confirmWho, onCancel = vm::adjustPhoto, video = false, overview = overview,
                                    onAddArea = { pick -> vm.confirmWho(pick); page = Page.BRUSH },
                                )
                            }
                            Flow.RESULT -> {
                                LaunchedEffect(job.version) { appliedRanges = ranges }
                                ResultScreen(
                                    video!!, job, job.output!!.toUri(), draft, vm::thumbnail, vm::setOverride, vm::applyOverrides, vm::save,
                                    onShare = { vm.shareIntent()?.let(start) }, onAdjust = vm::adjust, onNew = { vm.clearVideo(); chooseVideos() },
                                    saved = saved, settings = settings,
                                    ranges = ranges, onRange = vm::setRange, rangesChanged = ranges != appliedRanges,
                                    extra = ExtraActions(
                                        onWhatsApp = { job.output?.let(vm::whatsAppFile)?.let(start) },
                                        onReport = { reportOpen = true },
                                        onDeleteOriginal = { askDeleteOriginal(vm.currentVideoEntry()) },
                                    ),
                                    onProjects = { vm.clearVideo(); toProjects() },
                                )
                            }
                            Flow.PHOTO -> photo?.let { p ->
                                PhotoHome(p.preview, p.name, settings, photoBusy, onEditSettings = { tab = Tab.SETTINGS }, onClear = vm::clearPhoto, onStart = vm::startPhoto)
                            }
                            Flow.PHOTO_RESULT -> photoResult?.let { r ->
                                PhotoResultScreen(
                                    r, draft, vm::thumbnail, photoBusy, vm::setOverride, vm::applyPhotoOverrides, vm::savePhoto,
                                    onShare = { vm.sharePhotoIntent()?.let(start) }, onAdjust = vm::adjustPhoto,
                                    onNew = { vm.clearPhoto(); choosePhotos() },
                                    saved = saved, settings = settings,
                                    extra = ExtraActions(
                                        onWhatsApp = { vm.whatsAppFile(r.file)?.let(start) },
                                        onReport = { reportOpen = true },
                                        onDeleteOriginal = { askDeleteOriginal(vm.currentPhotoEntry()) },
                                        onBrush = { page = Page.BRUSH },
                                    ),
                                    onProjects = { vm.clearPhoto(); toProjects() },
                                )
                            }
                            Flow.BATCH -> batch?.let { b ->
                                val entries = b.ids.mapNotNull { id -> history.firstOrNull { it.id == id } }
                                // when the batch ends: every photo, one after another
                                if (b.finished && entries.isNotEmpty()) ViewerScreen(
                                    entries, 0, batch = b,
                                    onSave = vm::saveEntry, onShare = { start(vm.shareEntryIntent(it)) },
                                    onEdit = vm::editEntry, onDelete = vm::deleteEntry,
                                    onSaveAll = { vm.saveEntries(b.ids) }, onShareAll = { vm.shareEntriesIntent(b.ids)?.let(start) },
                                    onClose = vm::closeBatch, onMore = { vm.closeBatch(); choosePhotos() },
                                    onWhatsApp = { e -> vm.whatsAppEntry(e)?.let(start) }, onDeleteOriginal = askDeleteOriginal,
                                ) else BatchScreen(b, onClose = vm::closeBatch, onMore = { vm.closeBatch(); choosePhotos() })
                            }
                            else -> Unit
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
                if (!inFlow && viewer == null && page != Page.BRUSH) {
                    BottomNav(tab, onTab = { t -> tab = t; page = null }, onPlus = { plusOpen = true })
                }
            }
            if (plusOpen) PlusMenu(onPhoto = choosePhotos, onVideo = chooseVideos, onDismiss = { plusOpen = false })
        }
    }

    if (profileOpen) ProfileDialog(ui.name, ui.email, onSave = { n, e -> profileOpen = false; vm.updateUi { it.copy(name = n, email = e) } }, onDismiss = { profileOpen = false })
    if (reportOpen) ReportDialog(
        hasResult = photoResult != null || job.output != null,
        onSend = { note, c, o, d -> reportOpen = false; runCatching { start(vm.reportIntent(note, c, o, d)) } },
        onDismiss = { reportOpen = false },
    )
}

private fun pageTitle(p: Page) = when (p) {
    Page.PRIVACY -> tr("פרטיות ואבטחה")
    Page.FAQ -> tr("שאלות נפוצות")
    Page.PRESETS -> tr("תבניות שמירה")
    Page.BRUSH -> tr("תיקון במכחול")
    Page.ABOUT -> tr("אודות")
    Page.PRO -> "BlueShield Pro"
}

private fun flowTitle(f: Flow) = when (f) {
    Flow.VIDEO, Flow.CHOOSE_VIDEO -> tr("צנזור סרטון")
    Flow.PHOTO, Flow.CHOOSE_PHOTO -> tr("צנזור תמונה")
    Flow.QUEUE -> tr("תור סרטונים")
    Flow.PROCESSING -> tr("עיבוד הסרטון")
    Flow.BATCH -> tr("עריכה קבוצתית")
    Flow.RESULT, Flow.PHOTO_RESULT -> tr("העיבוד הושלם")
    Flow.START -> ""
}

/** Shown on the launch after a crash: the report can be copied or shared so the cause can be fixed. */
@Composable
private fun CrashDialog(report: String, onShare: () -> Unit, onCopy: () -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Ink850, shape = RoundedCornerShape(26.dp),
        title = { Text(tr("האפליקציה קרסה בפעם הקודמת"), color = Palette.Text) },
        text = {
            Column {
                Text(
                    tr("כדי שאוכל לתקן, לחץ \"שיתוף\" ושלח לי את הטקסט (למשל בוואטסאפ לעצמך, ומשם הדבק לי בשיחה)."),
                    color = Palette.Ink200, fontSize = 13.sp,
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        report, color = Palette.Ink300, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 10.dp).heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onShare) { Text(tr("שיתוף")) } },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onCopy) { Text(tr("העתקה")) }
                androidx.compose.material3.TextButton(onClick = onClose) { Text(tr("סגירה")) }
            }
        },
    )
}
