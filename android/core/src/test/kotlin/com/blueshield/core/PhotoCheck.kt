package com.blueshield.core

import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.pipeline.Analyzer
import com.blueshield.core.pipeline.StillImage
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Opt-in photo diagnostics (not part of the normal test run):
 *   gradle test --tests '*PhotoCheck*' -Dblueshield.photo=in.jpg:out.png[:settings-json]
 * Runs the photo pipeline exactly as the app does and writes out.png = censored | debug (raw skin
 * probability in red, person boxes in green) side by side; prints every person's decision.
 */
class PhotoCheck {
    @Test fun photo() {
        val arg = System.getProperty("blueshield.photo")
        assumeTrue("no photo job", !arg.isNullOrBlank())
        val parts = arg!!.split(":", limit = 3)
        val settings = (if (parts.size > 2) kotlinx.serialization.json.Json.decodeFromString(CensorSettings.serializer(), parts[2]) else CensorSettings()).validated()
        val repo = File(System.getProperty("blueshield.repo") ?: "../..")
        val models = ModelStore({ File(repo, "models/onnx/$it").readBytes() })
        val spec = PipelineSpec.bundled
        val (w, h, _) = PipelineIntegrationTest.probe(File(parts[0]))
        val (aw, ah) = Analyzer.scaledSize(w, h, spec.analysisMaxSide)
        val (mw, mh) = Analyzer.scaledSize(w, h, spec.maskMaxSide)
        val img = PipelineIntegrationTest.decode(File(parts[0]), aw, ah).first()
        val full = PipelineIntegrationTest.decode(File(parts[0]), w, h).first()
        for (d0 in com.blueshield.core.ml.PersonDetector(models).detect(img, 0.2f)) println("DET ${"%.2f".format(d0.score)} ${d0.box}")
        val t0 = System.nanoTime()
        // Same as StillImage.analyze, keeping the analyzer to read its skin probability.
        val still = spec.copy(gender = spec.gender.copy(voteFactor = 1.0, minVotes = 1, minWeight = 0.3, minAgeVotes = 1))
        val analyzer = Analyzer(models, settings.copy(speed = "quality"), still, aw, ah, 1.0, mw, mh, File.createTempFile("photo", ".bin"), spec.personMasks.photoSize)
        var prob: com.blueshield.core.image.FloatMask? = null
        analyzer.process(listOf(img)) { prob = analyzer.lastSkinProbability }
        val a = analyzer.finish()
        println("PHOTO ${w}x$h analysed at ${aw}x$ah in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
        val d = a.decisions(settings, emptyMap())
        for (p in a.summaries(settings, emptyMap())) {
            val box = a.people[p.id]?.box
            println("PERSON #${p.id} ${p.gender} pFemale=${"%.2f".format(p.pFemale)} votes=${p.votes} age=${p.age} child=${p.child} censored=${d[p.id]} box=$box")
        }
        val px = IntArray(w * h) { (0xFF shl 24) or ((full.data[it * 3].toInt() and 0xFF) shl 16) or ((full.data[it * 3 + 1].toInt() and 0xFF) shl 8) or (full.data[it * 3 + 2].toInt() and 0xFF) }
        StillImage.alpha(a, settings, d, w, h, px.copyOf())?.let { StillImage.paint(px, w, h, it, CensorSettings.parseColor(settings.color)) }
        // Debug panel at analysis size, scaled to the photo size.
        val out = java.awt.image.BufferedImage(w * 2, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, w, h, px, 0, w)
        val dbg = java.awt.image.BufferedImage(aw, ah, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until ah) for (x in 0 until aw) {
            val i = y * aw + x
            var r = (img.data[i * 3].toInt() and 0xFF) / 3; var g = (img.data[i * 3 + 1].toInt() and 0xFF) / 3; var b = (img.data[i * 3 + 2].toInt() and 0xFF) / 3
            prob?.let { r = maxOf(r, (it.data[i] * 255).toInt().coerceIn(0, 255)) }
            dbg.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        // final raw mask (before feathering) in blue
        val raw = a.maskFor(0, d, settings, 0)
        for (y in 0 until ah) for (x in 0 until aw) {
            val mx = x * raw.width / aw; val my = y * raw.height / ah
            if (raw.data[my * raw.width + mx].toInt() != 0) dbg.setRGB(x, y, dbg.getRGB(x, y) or 0xFF)
        }
        val gr = dbg.createGraphics()
        gr.color = java.awt.Color.GREEN
        for (p in a.people.values) {
            gr.drawRect(p.box.x1.toInt(), p.box.y1.toInt(), p.box.w.toInt(), p.box.h.toInt())
            gr.drawString("#${p.id}", p.box.x1.toInt() + 3, p.box.y1.toInt() + 12)
        }
        out.createGraphics().drawImage(dbg, w, 0, w, h, null)
        javax.imageio.ImageIO.write(out, "png", File(parts[1]))
        a.close()
    }
}
