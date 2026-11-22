package com.zxsofa.emu

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Efectos de pantalla disponibles. */
enum class ScreenEffect(val label: String, val code: Float) {
    SHARP("Nítido", 0f),
    CRT("Monitor CRT", 1f),
    TV("Televisor de tubo", 2f),
    GREEN("Fósforo verde", 3f),
    AMBER("Fósforo ámbar", 4f),
}

/**
 * Pinta los fotogramas del emulador con OpenGL ES 2.0 y un shader que imita
 * monitores y televisores de tubo. Todo se llama desde el hilo del emulador.
 */
class GlScreen {
    private val timing get() = measure
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var boundSurface: Surface? = null

    private var program = 0
    private var plain = 0
    private var frameTex = 0
    private var frameBmp: Bitmap? = null
    private var texture = 0
    private var texW = 0
    private var texH = 0
    private var curFilter = 0
    private var aPos = 0
    private var uTex = 0
    private var uTexSize = 0
    private var uOutSize = 0
    private var uMode = 0
    private var uStrength = 0
    private var uTime = 0
    private var uSmooth = 0
    private val startTime = System.nanoTime()

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** Prepara EGL. Devuelve false si el aparato no tiene OpenGL ES 2 (se usará el pintado clásico). */
    fun init(): Boolean = try {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1))
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        true
    } catch (e: Throwable) {
        Log.w("ZXSofa", "OpenGL no disponible", e)
        release()
        false
    }

    /** Suelta la superficie actual (la ventana se va a destruir). */
    fun releaseSurface() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
        boundSurface = null
    }

    fun release() {
        releaseSurface()
        program = 0
        texture = 0
        plain = 0
        frameTex = 0
        frameBmp = null
        curFilter = 0
        texW = 0
        texH = 0
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun bind(surface: Surface): Boolean {
        if (surface === boundSurface && eglSurface != EGL14.EGL_NO_SURFACE) return true
        releaseSurface()
        if (!surface.isValid) return false
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            releaseSurface(); return false
        }
        boundSurface = surface
        // Que eglSwapBuffers no espere al refresco: el ritmo lo marca el audio
        EGL14.eglSwapInterval(display, 0)
        return true
    }

    private fun setup() {
        plain = link(VERTEX, PLAIN)
        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexSize = GLES20.glGetUniformLocation(program, "uTexSize")
        uOutSize = GLES20.glGetUniformLocation(program, "uOutSize")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uStrength = GLES20.glGetUniformLocation(program, "uStrength")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uSmooth = GLES20.glGetUniformLocation(program, "uSmooth")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        curFilter = 0
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Pinta [bmp] en [surface] de [outW]x[outH] píxeles. */
    fun draw(surface: Surface, outW: Int, outH: Int, bmp: Bitmap, effect: ScreenEffect, strength: Float, smooth: Boolean, frame: Bitmap? = null): Boolean {
        val t0 = System.nanoTime()
        if (effect != lastLogged) { lastLogged = effect; Log.i("ZXSofa", "GL: efecto ${effect.name}, marco=${frame != null}, salida ${outW}x$outH, imagen ${bmp.width}x${bmp.height}") }
        if (outW <= 0 || outH <= 0 || !bind(surface)) return false
        if (program == 0) setup()

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // Nítido sin suavizado: vecino más cercano, igual que el pintado clásico
        val filter = if (effect == ScreenEffect.SHARP && !smooth) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        if (filter != curFilter) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            curFilter = filter
        }
        if (bmp.width != texW || bmp.height != texH) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            texW = bmp.width; texH = bmp.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }

        // Rectángulo que conserva la proporción. En nítido, escala entera si apenas se pierde tamaño;
        // con efectos, la televisión se ve un poco más grande (la curvatura se come los bordes).
        var scale = minOf(outW.toFloat() / texW, outH.toFloat() / texH)
        val whole = kotlin.math.floor(scale)
        if (effect == ScreenEffect.SHARP && !smooth && whole >= 2f && whole / scale >= 0.85f) scale = whole
        val dw = (texW * scale).toInt()
        val dh = (texH * scale).toInt()

        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        var vx = (outW - dw) / 2
        var vy = (outH - dh) / 2
        var vw = dw
        var vh = dh
        if (effect == ScreenEffect.TV && frame != null) {
            // Marco de televisor: se encaja entero y la imagen va en el hueco del tubo
            val fs = minOf(outW.toFloat() / frame.width, outH.toFloat() / frame.height)
            val fw = (frame.width * fs).toInt()
            val fh = (frame.height * fs).toInt()
            val fx = (outW - fw) / 2
            val fy = (outH - fh) / 2
            drawFrame(frame, fx, fy, fw, fh)
            vw = (fw * HOLE_W).toInt(); vh = (fh * HOLE_H).toInt()
            vx = fx + (fw * HOLE_X).toInt()
            // GL cuenta desde abajo
            vy = fy + fh - (fh * HOLE_Y).toInt() - vh
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        }
        GLES20.glViewport(vx, vy, vw, vh)

        GLES20.glUseProgram(program)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uTexSize, texW.toFloat(), texH.toFloat())
        GLES20.glUniform2f(uOutSize, vw.toFloat(), vh.toFloat())
        GLES20.glUniform1f(uMode, effect.code)
        GLES20.glUniform1f(uStrength, strength.coerceIn(0f, 1f))
        GLES20.glUniform1f(uTime, ((System.nanoTime() - startTime) / 1e9).toFloat() % 1000f)
        GLES20.glUniform1f(uSmooth, if (smooth) 1f else 0f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, eglSurface)
        if (timing) {
            GLES20.glFinish()
            frameNs += System.nanoTime() - t0; frames++
            if (frames == 100) { Log.i("ZXSofa", "GL ${effect.name} ${dw}x$dh: ${frameNs / 100 / 1000} µs/fotograma"); frames = 0; frameNs = 0 }
        }
        return true
    }
    private var frames = 0
    private var lastLogged: ScreenEffect? = null
    private var frameNs = 0L

    private fun drawFrame(frame: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        if (frameTex == 0 || frameBmp !== frame) {
            if (frameTex == 0) {
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                frameTex = t[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
            frameBmp = frame
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTex)
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(plain)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(plain, "uTex"), 0)
        val a = GLES20.glGetAttribLocation(plain, "aPos")
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("Shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("Programa: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        /** Mide el tiempo de pintado (solo para pruebas). */
        @JvmStatic @Volatile var measure = false

        // Hueco del tubo dentro de tv_frame.jpg (1471x984, foto del usuario con el cristal oscurecido)
        private const val HOLE_X = 126f / 1471f
        private const val HOLE_Y = 124f / 984f
        private const val HOLE_W = 953f / 1471f
        private const val HOLE_H = 715f / 984f

        private const val PLAIN = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""

        private const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform vec2 uOutSize;
uniform float uMode;
uniform float uStrength;
uniform float uTime;
uniform float uSmooth;

// Ligero a propósito: 1–4 lecturas de textura por píxel y nada de exp/pow.

// Lectura en el centro vertical de la línea: el filtro lineal suaviza solo en horizontal, como el haz
vec3 line(vec2 uv) {
    float y = (floor(uv.y * uTexSize.y) + 0.5) / uTexSize.y;
    return texture2D(uTex, vec2(uv.x, y)).rgb;
}

vec2 curve(vec2 uv, float k) {
    vec2 c = uv * 2.0 - 1.0;
    c *= 1.0 + k * dot(c, c) * vec2(0.15, 0.25);
    return c * 0.5 + 0.5;
}

// Línea de barrido: más gruesa en colores brillantes (aproximación barata de una gaussiana)
float scan(vec2 uv, vec3 c, float depth) {
    if (uOutSize.y < uTexSize.y * 2.0) depth *= 0.4;   // pantalla pequeña: suave para evitar moiré
    float d = abs(fract(uv.y * uTexSize.y) - 0.5) * 2.0;
    float lum = dot(c, vec3(0.3, 0.59, 0.11));
    float b = clamp(1.0 - d * d * (1.6 - lum * 0.9), 0.0, 1.0);
    return mix(1.0, b, depth);
}

vec3 mask(float amount) {
    float x = mod(floor(gl_FragCoord.x), 3.0);
    vec3 m = x < 1.0 ? vec3(1.0, 0.7, 0.7) : (x < 2.0 ? vec3(0.7, 1.0, 0.7) : vec3(0.7, 0.7, 1.0));
    return mix(vec3(1.0), m, amount);
}

// Brillo: una sola lectura lineal desplazada media celda promedia 2x2 píxeles
vec3 glow(vec2 uv) { return texture2D(uTex, uv + 0.75 / uTexSize).rgb; }

float frame(vec2 uv) {
    vec2 v = uv * (1.0 - uv);
    float vig = clamp(v.x * v.y * 40.0, 0.0, 1.0);
    vig = sqrt(vig);
    vec2 d = max(abs(uv - 0.5) * 2.0 - 0.94, 0.0) / 0.06;
    return vig * (1.0 - smoothstep(0.85, 1.0, length(d)));
}

void main() {
    vec2 uv = vUv;
    float s = uStrength;
    vec3 col;

    if (uMode < 0.5) {
        col = texture2D(uTex, uv).rgb;
    } else if (uMode < 1.5) {
        col = line(uv);
        col *= scan(uv, col, 0.75 * s);
        col *= mask(0.45 * s);
        col += glow(uv) * 0.10 * s;
        col *= 1.0 + 0.45 * s;
    } else if (uMode < 2.5) {
        uv = curve(uv, 0.45);
        // Fuera del cristal no se pinta: se ve el fondo negro o el marco de la tele
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;
        float off = 0.9 / uTexSize.x;
        vec3 mid = line(uv);
        col = vec3(line(uv + vec2(off, 0.0)).r, mid.g, line(uv - vec2(off, 0.0)).b);
        col *= scan(uv, col, 0.55 * s);
        col *= mask(0.3 * s);
        col += glow(uv) * 0.15;
        float noise = fract(sin(dot(gl_FragCoord.xy + uTime * 61.0, vec2(12.9898, 78.233))) * 43758.5453);
        col += (noise - 0.5) * 0.035;
        col *= (0.985 + 0.015 * sin(uTime * 50.0)) * (1.0 + 0.35 * s) * frame(uv);
    } else {
        uv = curve(uv, 0.45);
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        float lum = dot(line(uv) * 0.85 + glow(uv) * 0.25, vec3(0.3, 0.59, 0.11));
        vec3 tint = uMode < 3.5 ? vec3(0.25, 1.0, 0.35) : vec3(1.0, 0.68, 0.12);
        col = tint * (lum * scan(uv, vec3(lum), 0.6 * s) * (1.0 + 0.4 * s) + 0.03) * frame(uv);
    }
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
