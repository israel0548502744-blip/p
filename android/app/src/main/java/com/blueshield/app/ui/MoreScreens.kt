package com.blueshield.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.BuildInfo
import com.blueshield.app.engine.Processor
import com.blueshield.core.CensorSettings
import com.blueshield.core.gender.Override

/** Full settings page (from the menu, or "Edit" on the home screen). */
@Composable
fun SettingsScreen(settings: CensorSettings, enabled: Boolean, onChange: (CensorSettings) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Saved automatically and used for every video and photo.", color = Palette.Ink300, fontSize = 13.sp)
        if (!enabled) Text("Processing is running — changes apply to the next job.", color = Palette.Amber, fontSize = 12.5.sp)
        SettingsSection(settings, hasAudio = true, enabled = true, onChange = onChange)
    }
}

/** A one-glance summary of the current settings with a button to the settings page. */
@Composable
fun SettingsSummary(s: CensorSettings, onEdit: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(CensorSettings.parseColor(s.color) or -0x1000000))
                    .border(1.dp, Palette.Border, RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Censor settings", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (s.target == "female") "Adult women only" else "Everyone") + " · " +
                        (if (s.uncertainPolicy == "censor") "unsure → censor" else "unsure → keep") + " · " +
                        when (s.speed) { "quality" -> "max quality"; "fast" -> "fast"; else -> "balanced" },
                    color = Palette.Ink300, fontSize = 12.sp,
                )
            }
            Icon(Icons.Filled.Settings, null, tint = Palette.Ink300)
        }
    }
}

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("About BlueShield", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Version ${BuildInfo.version}", color = Palette.Ink300, fontSize = 13.sp)
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("How it works")
                for (line in listOf(
                    "People are detected and tracked through the video; each person is classified from their face (two models, decided over many frames).",
                    "Only adult women are censored by default. Girls, men and anyone you mark “Don't” are left as they are.",
                    "Bare skin of each censored person is covered — arms, shoulders, a low neckline from just below the chin. Faces and necks stay visible.",
                    "On-screen text and captions are never painted over.",
                    "Unsure? The safe default censors. You can override every person and re-render without re-analysing.",
                )) Text("• $line", color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Privacy")
                Text("Everything runs on this phone. Videos and photos are never uploaded.", color = Palette.Ink100, fontSize = 13.sp)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Models")
                for (m in listOf(
                    "MediaPipe Selfie Multiclass, EfficientDet-Lite0, BlazeFace (Apache-2.0)",
                    "FaceRes gender model, @vladmandic/human-models (MIT)",
                    "face-api.js AgeGenderNet, @vladmandic/face-api (MIT)",
                    "NudeNet v3 (MIT)",
                )) Text(m, color = Palette.Ink300, fontSize = 12.sp)
            }
        }
    }
}

/** A picked photo: preview, settings summary, and the start button. */
@Composable
fun PhotoHome(preview: Bitmap, name: String, settings: CensorSettings, busy: Boolean, onEditSettings: () -> Unit, onClear: () -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                preview.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height).clip(RoundedCornerShape(16.dp)),
            )
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Image, null, tint = Palette.Shield400)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                        Text("Photo · ${preview.width}×${preview.height} preview", color = Palette.Ink400, fontSize = 12.sp)
                    }
                    Text("Change", color = Palette.Shield400, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClear).padding(6.dp))
                }
            }
            SettingsSummary(settings, onEditSettings)
        }
        Box(Modifier.background(Palette.Ink950).padding(16.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(color = Palette.Shield400)
            else PrimaryButton("Censor Photo", Icons.Filled.PlayArrow, onClick = onStart)
        }
    }
}

/** The censored photo: compare, people list with overrides, save / share. */
@Composable
fun PhotoResultScreen(
    result: Processor.PhotoResult, draft: Map<Int, Override>, thumbnail: (Int) -> Bitmap?, busy: Boolean,
    onOverride: (Int, Override) -> Unit, onApply: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onAdjust: () -> Unit, onNew: () -> Unit,
    saved: Boolean, settings: CensorSettings,
) {
    var showOriginal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Emerald.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Emerald)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Photo censored.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("Review the result, then save or share.", color = Palette.Ink300, fontSize = 12.5.sp)
            }
        }
        Segmented(listOf(false to "Censored", true to "Original"), showOriginal, { showOriginal = it })
        val bmp = if (showOriginal) result.original else result.censored
        Image(
            bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(16.dp)),
        )
        if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Shield400) }
        PrimaryButton(if (saved) "Saved to gallery" else "Save to gallery", Icons.Filled.Download, enabled = !saved, onClick = onSave)
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.Share, null)
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
        PeoplePanel(result.people, draft, thumbnail, onOverride, onApply, settings)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Adjust") }
            OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text("New") }
        }
        PrivacyPill()
    }
}
