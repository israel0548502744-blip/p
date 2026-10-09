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
 *   gradle test --tests '*PhotoCheck*' -Dblueshield.photo=in.jpg:out.png[:settings-json][>alpha.png][;next job...]
 *   [-Dblueshield.photoScale=area|bilinear: scale the photo to the analysis size as the phone does, not by ffmpeg]
 * Runs the photo pipeline exactly as the app does and writes out.png = censored | debug (raw skin
 * probability in red, person boxes in green) side by side; prints every person's decision.
 */
class PhotoCheck {
    @Test fun photo() {
        val arg = System.getProperty("blueshield.photo")
        assumeTrue("no photo job", !arg.isNullOrBlank())
        // several jobs in one run: "in1:out1[:json][>alpha1];in2:out2..." (the alpha path after '>' overrides photoAlpha)
        val repo = File(System.getProperty("blueshield.repo") ?: "../..")
        // one model store for all jobs (a store per job kept every job's sessions alive: the test JVM ran out of memory)
        ModelStore({ File(repo, "models/onnx/$it").readBytes() }).use { models ->
            for (job in arg!!.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                val (spec, alpha) = job.split(">", limit = 2).let { it[0] to it.getOrNull(1) }
                run(models, spec, alpha ?: System.getProperty("blueshield.photoAlpha"))
            }
        }
    }

    private fun run(models: ModelStore, arg: String, alphaPath: String?) {
        val parts = arg.split(":", limit = 3)
        val settings = (if (parts.size > 2) kotlinx.serialization.json.Json.decodeFromString(CensorSettings.serializer(), parts[2]) else CensorSettings()).validated()
        // -Dblueshield.spec=file.json: a variant of shared/pipeline.json (ablations)
        val spec = System.getProperty("blueshield.spec")?.takeIf { it.isNotBlank() }?.let { PipelineSpec.parse(File(it).readText()) } ?: PipelineSpec.bundled
        val (w, h, _) = PipelineIntegrationTest.probe(File(parts[0]))
        val (aw, ah) = Analyzer.scaledSize(w, h, spec.analysisMaxSide)
        val (mw, mh) = Analyzer.scaledSize(w, h, spec.maskMaxSide)
        val full = PipelineIntegrationTest.decode(File(parts[0]), w, h).first()
        // -Dblueshield.photoScale=area|bilinear: the analysis image scaled from the full-size photo the way the phone
        // does it (area: PhotoLoader.toRgb; bilinear: createScaledBitmap's filtering, which the app used before)
        // instead of by ffmpeg's area scaler
        val scale = System.getProperty("blueshield.photoScale")
        val img = when {
            // (a small odd-sized photo's analysis size is 1 px larger: the phone scales that bilinearly too)
            scale == "area" && aw <= w && ah <= h -> com.blueshield.core.image.AreaScaler(w, h, aw, ah).scale { y, row ->
                for (x in 0 until w) row[x] = ((full.data[(y * w + x) * 3].toInt() and 0xFF) shl 16) or
                    ((full.data[(y * w + x) * 3 + 1].toInt() and 0xFF) shl 8) or (full.data[(y * w + x) * 3 + 2].toInt() and 0xFF)
            }
            scale == "area" || scale == "bilinear" -> bilinear(full, aw, ah)
            else -> PipelineIntegrationTest.decode(File(parts[0]), aw, ah).first()
        }
        for (d0 in com.blueshield.core.ml.PersonDetector(models).detect(img, 0.2f)) println("DET ${"%.2f".format(d0.score)} ${d0.box}")
        val t0 = System.nanoTime()
        var prob: com.blueshield.core.image.FloatMask? = null
        var analyzer: Analyzer? = null
        // -Dblueshield.photoMaps=prefix: intermediate skin maps as 8-bit PNGs (prefix_<out>_selfie.png, _clothes, _vetoed, _refined)
        val maps = System.getProperty("blueshield.photoMaps")?.takeIf { it.isNotBlank() }
        val a = StillImage.analyze(models, settings, spec, img, mw, mh, File.createTempFile("photo", ".bin"), prepare = { an ->
            analyzer = an
            maps?.let { pre ->
                val tag = File(parts[1]).nameWithoutExtension
                an.debugMaps = { name, m ->
                    val g = java.awt.image.BufferedImage(m.width, m.height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
                    for (y in 0 until m.height) for (x in 0 until m.width) g.raster.setSample(x, y, 0, (m.data[y * m.width + x] * 255).toInt().coerceIn(0, 255))
                    javax.imageio.ImageIO.write(g, "png", File("${pre}_${tag}_$name.png"))
                }
            }
        }) { prob = it.lastSkinProbability }
        val an = analyzer!!
        println("PHOTO ${w}x$h analysed at ${aw}x$ah in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s; skin runs ${an.segmenterRuns}, clothes runs ${an.clothesRuns}")
        println("TIMINGS " + an.timings.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${"%.0f".format(it.value / 1e6)}ms" })
        val d = a.decisions(settings, emptyMap())
        for (p in a.summaries(settings, emptyMap())) {
            val box = a.people[p.id]?.box
            println("PERSON #${p.id} ${p.gender} pFemale=${"%.2f".format(p.pFemale)} votes=${p.votes} age=${p.age} child=${p.child} censored=${d[p.id]} box=$box")
        }
        val px = IntArray(w * h) { (0xFF shl 24) or ((full.data[it * 3].toInt() and 0xFF) shl 16) or ((full.data[it * 3 + 1].toInt() and 0xFF) shl 8) or (full.data[it * 3 + 2].toInt() and 0xFF) }
        val cloth = if (settings.fill == "clothing") a.clothFor(0) else null
        val t1 = System.nanoTime()
        val alpha = StillImage.alpha(a, settings, d, w, h, px.copyOf())
        println("ALPHA ${w}x$h in ${"%.2f".format((System.nanoTime() - t1) / 1e9)} s")
        alpha?.let { StillImage.paint(px, w, h, it, CensorSettings.parseColor(settings.color), cloth, a.clothWidth, a.clothHeight) }
        // -Dblueshield.photoAlpha=alpha.png: the final censor alpha (grey 0..255, photo size) for scoring against a ground truth
        alphaPath?.takeIf { it.isNotBlank() }?.let { path ->
            val g = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            val r = g.raster
            for (y in 0 until h) for (x in 0 until w) r.setSample(x, y, 0, alpha?.let { (it.data[y * w + x].toInt() and 0xFF) } ?: 0)
            javax.imageio.ImageIO.write(g, "png", File(path))
        }
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

    /** Plain bilinear sampling (pixel centres, 2 × 2 taps, no prefilter), like Skia's linear bitmap filtering. */
    private fun bilinear(src: RgbImage, w: Int, h: Int): RgbImage {
        val out = RgbImage(w, h)
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        for (y in 0 until h) for (x in 0 until w) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, src.width - 1f)
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, src.height - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = minOf(x0 + 1, src.width - 1); val y1 = minOf(y0 + 1, src.height - 1)
            val ax = fx - x0; val ay = fy - y0
            for (c in 0 until 3) {
                fun v(xx: Int, yy: Int) = (src.data[(yy * src.width + xx) * 3 + c].toInt() and 0xFF).toFloat()
                val top = v(x0, y0) + (v(x1, y0) - v(x0, y0)) * ax
                val bot = v(x0, y1) + (v(x1, y1) - v(x0, y1)) * ax
                out.data[(y * w + x) * 3 + c] = (top + (bot - top) * ay + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
