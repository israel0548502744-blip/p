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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueshield.app.engine.JobState
import com.blueshield.app.ui.BlueShieldTheme
import com.blueshield.app.ui.ErrorBanner
import com.blueshield.app.ui.HomeScreen
import com.blueshield.app.ui.Logo
import com.blueshield.app.ui.Palette
import com.blueshield.app.ui.ProcessingScreen
import com.blueshield.app.ui.ResultScreen
import com.blueshield.app.ui.AboutScreen
import com.blueshield.app.ui.BatchScreen
import com.blueshield.app.ui.PhotoHome
import com.blueshield.app.ui.PhotoResultScreen
import com.blueshield.app.ui.SettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The UI is Hebrew: right-to-left everywhere, whatever the phone's language is.
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { BlueShieldTheme { App(vm) } } }
    }
}

private enum class Screen { HOME, PROCESSING, RESULT, PHOTO, PHOTO_RESULT, BATCH, SETTINGS, ABOUT }

@Composable
private fun App(vm: AppViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val video by vm.video.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val showResult by vm.showResult.collectAsStateWithLifecycle()
    val draft by vm.draftOverrides.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val photo by vm.photo.collectAsStateWithLifecycle()
    val photoResult by vm.photoResult.collectAsStateWithLifecycle()
    val photoBusy by vm.photoBusy.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    /** A page opened from the menu, shown over whatever is current. */
    var page by remember { mutableStateOf<Screen?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var crashReport by remember { mutableStateOf(CrashReporter.pending(context)) }
    crashReport?.let { report ->
        CrashDialog(report, onShare = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }
            activity.startActivity(Intent.createChooser(send, null))
        }, onCopy = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BlueShield crash report", report))
        }, onClose = {
            CrashReporter.markSeen(context)
            crashReport = null
        })
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.onPicked(it) }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.onPickedMany(it) }
    val launchMany = { pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val startWithPermission = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val screen = page ?: when {
        job.running -> Screen.PROCESSING
        batch != null -> Screen.BATCH
        showResult && job.stage == JobState.Stage.COMPLETE && job.output != null && video != null -> Screen.RESULT
        photoResult != null -> Screen.PHOTO_RESULT
        photo != null -> Screen.PHOTO
        else -> Screen.HOME
    }
    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && screen == Screen.RESULT) { vm.adjust() }
    BackHandler(enabled = page == null && screen == Screen.PHOTO_RESULT) { vm.adjustPhoto() }
    BackHandler(enabled = page == null && screen == Screen.BATCH) { vm.closeBatch() }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Palette.Shield600.copy(alpha = 0.10f), Color.Transparent), radius = 1400f))
            .background(Palette.Ink950)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != null) {
                IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White) }
            }
            Logo()
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "תפריט", tint = Color.White) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("הגדרות") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { menuOpen = false; page = Screen.SETTINGS })
                    DropdownMenuItem(text = { Text("סרטון או תמונה חדשים") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                        enabled = !job.running, onClick = {
                            menuOpen = false; page = null
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                    DropdownMenuItem(text = { Text("כמה תמונות בבת אחת") }, leadingIcon = { Icon(Icons.Filled.Collections, null) },
                        enabled = !job.running && batch == null, onClick = { menuOpen = false; page = null; launchMany() })
                    DropdownMenuItem(text = { Text("אודות") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { menuOpen = false; page = Screen.ABOUT })
                }
            }
        }
        error?.let { ErrorBanner(it, vm::dismissError) }
        AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen", modifier = Modifier.weight(1f)) { s ->
            when (s) {
                Screen.HOME -> HomeScreen(
                    video, settings, loading, error,
                    onPick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onClear = vm::clearVideo, onEditSettings = { page = Screen.SETTINGS }, onStart = startWithPermission,
                    onPickMany = launchMany,
                )
                Screen.BATCH -> batch?.let { b -> BatchScreen(b, onClose = vm::closeBatch, onMore = { vm.closeBatch(); launchMany() }) }
                Screen.PHOTO -> photo?.let { p ->
                    PhotoHome(p.preview, p.name, settings, photoBusy, onEditSettings = { page = Screen.SETTINGS }, onClear = vm::clearPhoto, onStart = vm::startPhoto)
                }
                Screen.PHOTO_RESULT -> photoResult?.let { r ->
                    PhotoResultScreen(
                        r, draft, vm::thumbnail, photoBusy, vm::setOverride, vm::applyPhotoOverrides, vm::savePhoto,
                        onShare = { vm.sharePhotoIntent()?.let(activity::startActivity) }, onAdjust = vm::adjustPhoto,
                        onNew = { vm.clearPhoto(); pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        saved = saved, settings = settings,
                    )
                }
                Screen.SETTINGS -> SettingsScreen(settings, enabled = !job.running && !photoBusy, onChange = vm::updateSettings)
                Screen.ABOUT -> AboutScreen()
                Screen.PROCESSING -> ProcessingScreen(job, vm::pause, vm::resume, vm::cancel)
                Screen.RESULT -> ResultScreen(
                    video!!, job, job.output!!.toUri(), draft, vm::thumbnail, vm::setOverride, vm::applyOverrides, vm::save,
                    onShare = { vm.shareIntent()?.let(activity::startActivity) }, onAdjust = vm::adjust, onNew = vm::clearVideo,
                    saved = saved, settings = settings,
                )
            }
        }
    }
}


/** Shown on the launch after a crash: the report can be copied or shared so the cause can be fixed. */
@Composable
private fun CrashDialog(report: String, onShare: () -> Unit, onCopy: () -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Ink900,
        title = { androidx.compose.material3.Text("האפליקציה קרסה בפעם הקודמת", color = Color.White) },
        text = {
            Column {
                androidx.compose.material3.Text(
                    "כדי שאוכל לתקן, לחץ \"שיתוף\" ושלח לי את הטקסט (למשל בוואטסאפ לעצמך, ומשם הדבק לי בשיחה).",
                    color = Palette.Ink200, fontSize = 13.sp,
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(
                        report, color = Palette.Ink300, fontSize = 10.sp, lineHeight = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(top = 10.dp).heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onShare) { androidx.compose.material3.Text("שיתוף") } },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onCopy) { androidx.compose.material3.Text("העתקה") }
                androidx.compose.material3.TextButton(onClick = onClose) { androidx.compose.material3.Text("סגירה") }
            }
        },
    )
}
