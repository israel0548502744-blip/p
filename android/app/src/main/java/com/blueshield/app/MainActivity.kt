package com.blueshield.app

import android.Manifest
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BlueShieldTheme { App(vm) } }
    }
}

private enum class Screen { HOME, PROCESSING, RESULT }

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

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.onPicked(it) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val startWithPermission = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val screen = when {
        job.running -> Screen.PROCESSING
        showResult && job.stage == JobState.Stage.COMPLETE && job.output != null && video != null -> Screen.RESULT
        else -> Screen.HOME
    }
    BackHandler(enabled = screen == Screen.RESULT) { vm.adjust() }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Palette.Shield600.copy(alpha = 0.10f), Color.Transparent), radius = 1400f))
            .background(Palette.Ink950)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Spacer(Modifier.weight(1f))
        }
        error?.let { ErrorBanner(it, vm::dismissError) }
        AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen", modifier = Modifier.weight(1f)) { s ->
            when (s) {
                Screen.HOME -> HomeScreen(
                    video, settings, loading, error,
                    onPick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    onClear = vm::clearVideo, onSettings = vm::updateSettings, onStart = startWithPermission,
                )
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
