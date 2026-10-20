package com.blueshield.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the app on each main screen and saves a screenshot, so CI can publish how the UI looks. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private fun shot(
        file: String, screen: String? = null, prefs: UiPrefs = UiPrefs(onboarded = true, seenVersion = BuildInfo.version, name = "ישראל"),
        photo: String? = null, waitMs: Long = 2500,
    ) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val store = ctx.getSharedPreferences("blueshield", android.content.Context.MODE_PRIVATE)
        // past the welcome pages and the "what's new" note: the screen itself
        prefs.save(store)
        com.blueshield.app.ui.I18n.load(ctx, prefs.language)
        try {
            val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (screen != null) intent.putExtra(MainActivity.EXTRA_SCREEN, screen)
            if (photo != null) {
                // shared the way another app shares: a content:// link (the app's own provider serves outputs/)
                val f = File(ctx.filesDir, "outputs/$photo").apply { parentFile?.mkdirs() }
                inst.context.assets.open(photo).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                intent.setAction(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val activity = inst.startActivitySync(intent)
            inst.waitForIdleSync()
            SystemClock.sleep(waitMs)
            val bmp = inst.uiAutomation.takeScreenshot()
            activity.finish()
            MainActivity.screen.value = null
            assertTrue("no screenshot", bmp != null && bmp.width > 0)
            File(ctx.filesDir, file).outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            UiPrefs(onboarded = true, seenVersion = BuildInfo.version).save(store)
            com.blueshield.app.ui.I18n.load(ctx, "he")
        }
    }

    @Test fun homeScreen() = shot("screen_home.png")
    @Test fun sideMenu() = shot("screen_drawer.png", "drawer")
    @Test fun toolsTab() = shot("screen_tools.png", "tools")
    @Test fun projectsTab() = shot("screen_projects.png", "projects")
    @Test fun settingsTab() = shot("screen_settings.png", "settings")
    @Test fun proPage() = shot("screen_pro.png", "pro")
    @Test fun plusMenu() = shot("screen_plus.png", "plus")

    /** "Ask whom to censor" on, a photo shared to the app: the people are found and the picker shows them. */
    @Test fun choosePeople() = shot(
        "screen_choose.png", "choose", UiPrefs(onboarded = true, seenVersion = BuildInfo.version, askWho = true),
        photo = "woman_and_man.jpg", waitMs = 45_000,
    )

    /** The home screen in the light theme, in English (left to right), with another accent colour. */
    @Test
    fun homeScreenLightEnglish() = shot(
        "screen_light_en.png",
        prefs = UiPrefs(onboarded = true, seenVersion = BuildInfo.version, theme = "light", language = "en", accent = 0xFF0EA5E9),
    )
}
