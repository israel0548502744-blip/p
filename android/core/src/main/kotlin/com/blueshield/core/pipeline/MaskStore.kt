package com.blueshield.core.pipeline

import com.blueshield.core.image.ByteMask
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Per-frame masks compressed on disk (Deflate), with only an offset index in RAM —
 * keeps memory flat for long videos. Empty masks cost nothing.
 */
class MaskStore(val file: File, val width: Int, val height: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
    private val offsets = LongArrayList()
    private val lengths = IntArrayList()
    private val deflater = Deflater(1)
    private val empty = ByteMask(width, height)

    val size get() = offsets.size

    @Synchronized
    fun append(mask: ByteMask) {
        require(mask.width == width && mask.height == height)
        if (!mask.any()) {
            offsets.add(0)
            lengths.add(0)
            return
        }
        deflater.reset()
        deflater.setInput(mask.data)
        deflater.finish()
        val bos = ByteArrayOutputStream(mask.data.size / 8 + 64)
        val buf = ByteArray(16 * 1024)
        while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
        val bytes = bos.toByteArray()
        val off = raf.length()
        raf.seek(off)
        raf.write(bytes)
        offsets.add(off)
        lengths.add(bytes.size)
    }

    @Synchronized
    operator fun get(i: Int): ByteMask {
        if (i < 0 || i >= size || lengths[i] == 0) return empty
        val bytes = ByteArray(lengths[i])
        raf.seek(offsets[i])
        raf.readFully(bytes)
        val inf = Inflater()
        inf.setInput(bytes)
        val out = ByteArray(width * height)
        inf.inflate(out)
        inf.end()
        return ByteMask(width, height, out)
    }

    override fun close() {
        runCatching { raf.close() }
        deflater.end()
        file.delete()
    }
}

internal class LongArrayList {
    private var a = LongArray(1024)
    var size = 0
        private set
    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    operator fun get(i: Int) = a[i]
}

internal class IntArrayList {
    private var a = IntArray(1024)
    var size = 0
        private set
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    operator fun get(i: Int) = a[i]
}
