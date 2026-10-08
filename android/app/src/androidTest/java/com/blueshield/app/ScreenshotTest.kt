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

/** Opens the app and saves a screenshot of the home screen, so CI can publish how the UI looks. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @Test
    fun homeScreen() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val activity = inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        inst.waitForIdleSync()
        SystemClock.sleep(2500)
        val shot = inst.uiAutomation.takeScreenshot()
        activity.finish()
        assertTrue("no screenshot", shot != null && shot.width > 0)
        File(ctx.filesDir, "screen_home.png").outputStream().use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
