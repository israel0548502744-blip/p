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
    private fun shot(file: String, screen: String? = null, prefs: UiPrefs = UiPrefs(onboarded = true, seenVersion = BuildInfo.version, name = "ישראל")) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val store = ctx.getSharedPreferences("blueshield", android.content.Context.MODE_PRIVATE)
        // past the welcome pages and the "what's new" note: the screen itself
        prefs.save(store)
        com.blueshield.app.ui.I18n.load(ctx, prefs.language)
        try {
            val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (screen != null) intent.putExtra(MainActivity.EXTRA_SCREEN, screen)
            val activity = inst.startActivitySync(intent)
            inst.waitForIdleSync()
            SystemClock.sleep(2500)
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

    /** The home screen in the light theme, in English (left to right), with another accent colour. */
    @Test
    fun homeScreenLightEnglish() = shot(
        "screen_light_en.png",
        prefs = UiPrefs(onboarded = true, seenVersion = BuildInfo.version, theme = "light", language = "en", accent = 0xFF0EA5E9),
    )
}
