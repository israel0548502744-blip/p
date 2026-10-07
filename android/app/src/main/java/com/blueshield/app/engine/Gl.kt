package com.blueshield.app.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Minimal EGL wrapper bound to a MediaCodec encoder input surface. */
class EglSurface(surface: Surface) : AutoCloseable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No recordable EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swap() = EGL14.eglSwapBuffers(display, eglSurface)

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/**
 * Draws the decoded video frame (external OES texture) and composites the censor colour
 * wherever the feathered mask is set. The mask is in *display* orientation while the
 * frame is drawn in *coded* orientation (rotation is kept as container metadata), so the
 * shader rotates the mask lookup.
 */
class CensorShader(rotation: Int) {
    private val program: Int
    private val aPos: Int
    private val uSt: Int
    private val uVideo: Int
    private val uMask: Int
    private val uColor: Int
    private val uRot: Int
    private val uTime: Int
    private val uAnimated: Int
    private val uTexel: Int
    private val uStF: Int
    private val uRing: Int
    private val uSoft: Int
    private val rot = rotation
    val videoTex: Int
    private val maskTex: Int
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    init {
        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uVideo = GLES20.glGetUniformLocation(program, "uVideo")
        uMask = GLES20.glGetUniformLocation(program, "uMask")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uAnimated = GLES20.glGetUniformLocation(program, "uAnimated")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uStF = GLES20.glGetUniformLocation(program, "uStF")
        uRing = GLES20.glGetUniformLocation(program, "uRing")
        uSoft = GLES20.glGetUniformLocation(program, "uSoft")
        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        videoTex = tex[0]
        maskTex = tex[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    }

    private var maskW = 1
    private var maskH = 1
    private var interleaved = ByteArray(0)

    /**
     * Upload the mask (8-bit, display orientation, rows top-first) and its edge band ([band], same size, see
     * Composer.edgeMasks): luminance = mask, alpha = band.
     */
    fun uploadMask(width: Int, height: Int, data: ByteArray, band: ByteArray = data) {
        if (interleaved.size != width * height * 2) interleaved = ByteArray(width * height * 2)
        for (i in 0 until width * height) {
            interleaved[2 * i] = data[i]
            interleaved[2 * i + 1] = band[i]
        }
        maskW = width
        maskH = height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, width, height, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(interleaved))
    }

    /**
     * @param ringTexels radius (mask texels) of the ring the edge decision samples skin / surroundings on
     * @param soft width of the final 0→1 alpha ramp around the decision (0.1 crisp … 0.6 soft)
     */
    fun draw(viewW: Int, viewH: Int, stMatrix: FloatArray, rgb: Int, timeSec: Float, animated: Boolean, ringTexels: Float = 3f, soft: Float = 0.3f) {
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glUniform1i(uVideo, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glUniform1i(uMask, 1)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform3f(uColor, (rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f)
        GLES20.glUniform1i(uRot, rot)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1i(uAnimated, if (animated) 1 else 0)
        // one video pixel in texture coordinates (text protection samples neighbours TextGuard.R px away)
        GLES20.glUniform2f(uTexel, 1f / viewW, 1f / viewH)
        GLES20.glUniformMatrix4fv(uStF, 1, false, stMatrix, 0)
        // the ring radius in coded-frame units: the mask is in display orientation (rotated by 90/270 or not)
        val (mx, my) = if (rot % 180 != 0) maskH to maskW else maskW to maskH
        GLES20.glUniform2f(uRing, ringTexels / mx, ringTexels / my)
        GLES20.glUniform1f(uSoft, soft)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(2, intArrayOf(videoTex, maskTex), 0)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uSt;
            varying vec2 vVideo;
            varying vec2 vPos;
            void main() {
                vPos = aPos;
                vVideo = (uSt * vec4(aPos, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uVideo;
            uniform sampler2D uMask;
            uniform vec3 uColor;
            uniform int uRot;
            uniform float uTime;
            uniform int uAnimated;
            uniform vec2 uTexel;
            uniform mat4 uStF;
            uniform vec2 uRing;
            uniform float uSoft;
            varying vec2 vVideo;
            varying vec2 vPos;
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            float sat(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
            // on-screen text (bright + unsaturated next to very dark, or the dark outline itself) stays visible —
            // same test as core TextGuard
            float isText(vec4 video) {
                float l = luma(video.rgb);
                bool bright = l > 0.80 && sat(video.rgb) < 0.25;
                bool dark = l < 0.25;
                if (!bright && !dark) return 0.0;
                float hit = 0.0;
                for (int i = 0; i < 8; i++) {
                    vec2 d = i == 0 ? vec2(2.0, 0.0) : i == 1 ? vec2(-2.0, 0.0) : i == 2 ? vec2(0.0, 2.0) : i == 3 ? vec2(0.0, -2.0)
                        : i == 4 ? vec2(2.0, 2.0) : i == 5 ? vec2(-2.0, 2.0) : i == 6 ? vec2(2.0, -2.0) : vec2(-2.0, -2.0);
                    vec3 n = texture2D(uVideo, vVideo + d * uTexel).rgb;
                    float ln = luma(n);
                    if ((bright && ln < 0.25) || (dark && ln > 0.80 && sat(n) < 0.25)) hit = 1.0;
                }
                return hit;
            }
            // coded image coords (origin bottom-left, as vPos) -> display (upright) coords of the mask
            vec2 toDisplay(vec2 pos) {
                vec2 c = vec2(pos.x, 1.0 - pos.y);
                if (uRot == 90) return vec2(1.0 - c.y, c.x);
                if (uRot == 180) return vec2(1.0 - c.x, 1.0 - c.y);
                if (uRot == 270) return vec2(c.y, 1.0 - c.x);
                return c;
            }
            vec3 video(vec2 pos) { return texture2D(uVideo, (uStF * vec4(pos, 0.0, 1.0)).xy).rgb; }
            // brightness plus doubled colour: skin next to a grey shirt or dark hair differs mostly in colour
            vec3 ycc(vec3 c) {
                float y = luma(c);
                return vec3(y, 1.128 * (c.b - y), 1.426 * (c.r - y));
            }
            // Edge of the cover fitted to this frame (same algorithm as the desktop renderer): on two rings around
            // the pixel, the mean colour of the sure cover (this frame's skin) and of the sure surroundings; the
            // pixel (and four neighbours, against speckle) is decided by which it is closer to, with the coarse
            // mask as a prior.
            float fitted(vec2 pos, float a0) {
                vec3 s = vec3(0.0); float sn = 0.0;
                vec3 b = vec3(0.0); float bn = 0.0;
                for (int r = 1; r <= 2; r++) {
                    for (int k = 0; k < 16; k++) {
                        float t = 6.2831853 * (float(k) + 0.5 * float(r - 1)) / 16.0;
                        vec2 p = pos + vec2(cos(t), sin(t)) * uRing * float(r);
                        float m = texture2D(uMask, toDisplay(p)).r;
                        if (m > 0.9) { s += ycc(video(p)); sn += 1.0; }
                        else if (m < 0.1) { b += ycc(video(p)); bn += 1.0; }
                    }
                }
                if (sn < 3.0 || bn < 3.0) return a0;
                s /= sn;
                b /= bn;
                float acc = 0.0;
                for (int i = 0; i < 5; i++) {
                    vec2 o = i == 0 ? vec2(0.0) : i == 1 ? vec2(2.0, 0.0) : i == 2 ? vec2(-2.0, 0.0) : i == 3 ? vec2(0.0, 2.0) : vec2(0.0, -2.0);
                    vec3 c = ycc(video(pos + o * uTexel));
                    float ds = distance(c, s);
                    float db = distance(c, b);
                    acc += db / (ds + db + 0.004);
                }
                return 0.6 * acc / 5.0 + 0.4 * a0;
            }
            void main() {
                vec4 video = texture2D(uVideo, vVideo);
                vec2 d = toDisplay(vPos);
                vec4 m = texture2D(uMask, d);
                float v = m.r;
                // only near the edge (the band's grey zone) is the colour consulted
                if (m.a > 0.03 && m.a < 0.97) v = fitted(vPos, m.r);
                float a = clamp((v - 0.5) / uSoft + 0.5, 0.0, 1.0);
                if (a > 0.0) a *= 1.0 - isText(video);
                vec3 fill = uColor;
                if (uAnimated == 1) {
                    float wave = sin((d.x * 0.9 + d.y * 0.6) * 18.0 + uTime * 2.4);
                    fill = clamp(uColor * (1.0 + 0.12 * wave + 0.04 * sin(uTime * 3.1)) + step(0.85, wave) * 0.07, 0.0, 1.0);
                }
                gl_FragColor = vec4(mix(video.rgb, fill, a), 1.0);
            }
        """
    }
}
