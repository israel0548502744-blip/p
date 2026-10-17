package com.blueshield.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blueshield.app.engine.Exporter
import com.blueshield.app.engine.History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The recent-results store: keeps the newest 20 photos, replaces a re-rendered result, survives its output's deletion. */
@RunWith(AndroidJUnit4::class)
class HistoryTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(n: Int): File {
        val b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() or n * 0x010203) }
        return File(ctx.filesDir, "outputs/test_$n.jpg").also { f ->
            f.parentFile?.mkdirs()
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }

    @Test fun keepsTheNewestAndReplaces() {
        History.list(ctx).forEach { History.delete(ctx, it.id) }
        val first = History.add(ctx, photo(0), false, "p0.jpg", "content://x/0")
        repeat(History.MAX_PHOTOS + 4) { History.add(ctx, photo(it + 1), false, "p${it + 1}.jpg", null) }
        val all = History.list(ctx)
        assertEquals(History.MAX_PHOTOS, all.size)
        assertFalse("the oldest photo is dropped", all.any { it.id == first.id })
        assertFalse(File(first.file).exists())
        assertEquals(64, all[0].width)
        assertTrue(File(all[0].thumb).exists())

        // a re-render takes the place of the earlier version, and keeps its gallery copy
        val out = photo(99)
        History.setGallery(ctx, all[0].id, "content://gallery/1")
        val again = History.add(ctx, out, false, all[0].name, null, replace = all[0].id)
        val after = History.list(ctx)
        assertEquals(History.MAX_PHOTOS, after.size)
        assertEquals("content://gallery/1", after[0].gallery)
        assertEquals(again.id, after[0].id)
        // the stored copy is independent of the output file (which the next render deletes)
        out.delete()
        assertTrue(File(after[0].file).length() > 100)
        after.forEach { History.delete(ctx, it.id) }
        assertTrue(History.list(ctx).isEmpty())
    }

    @Test fun saveOrReplaceWritesOverTheSameGalleryItem() {
        val uri = Exporter.saveOrReplace(ctx, photo(1), "e2e_replace.jpg", video = false, existing = null)
        val same = Exporter.saveOrReplace(ctx, photo(2), "e2e_replace.jpg", video = false, existing = uri.toString())
        assertEquals(uri, same)
        ctx.contentResolver.openInputStream(same)!!.use { assertTrue(it.read() >= 0) }
        ctx.contentResolver.delete(same, null, null)
    }
}
