package com.usool.map.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws an equirectangular 360° photo as seen from its centre. Every screen pixel is turned into
 * a viewing direction and looked up in the photo, so the picture can be turned in any direction
 * without distortion. The same maths is in `PanoMath.uv`, which is unit tested.
 */
class PanoView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var fov = Math.toRadians(75.0).toFloat()

    private var pending: Bitmap? = null
    private var texture = 0
    private var hasTexture = false
    private var program = 0
    private var aspect = 1f
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    init {
        setEGLContextClientVersion(2)
        // The map underneath is also a surface; this keeps the panorama in front of it.
        setZOrderMediaOverlay(true)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Shows [bitmap]. It is uploaded on the GL thread at the next frame. */
    fun setImage(bitmap: Bitmap) {
        synchronized(this) { pending = bitmap }
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        hasTexture = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        val fresh = synchronized(this) { pending.also { pending = null } }
        if (fresh != null && !fresh.isRecycled) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, fresh, 0)
            hasTexture = true
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasTexture || program == 0) return
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uYaw"), yaw)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uPitch"), pitch)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTan"), Math.tan(fov / 2.0).toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), aspect)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        return if (ok[0] == 0) 0 else p
    }

    companion object {
        /** Largest picture side uploaded as a texture; within every phone GPU's limit. */
        const val MAX_TEXTURE = 4096

        const val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vPos;
            void main() { vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform float uYaw;
            uniform float uPitch;
            uniform float uTan;
            uniform float uAspect;
            varying vec2 vPos;
            void main() {
                vec3 d = normalize(vec3(vPos.x * uTan * uAspect, vPos.y * uTan, -1.0));
                float cp = cos(uPitch); float sp = sin(uPitch);
                float py = d.y * cp - d.z * sp; float pz = d.y * sp + d.z * cp;
                float cy = cos(uYaw); float sy = sin(uYaw);
                float rx = d.x * cy - pz * sy; float rz = d.x * sy + pz * cy;
                float lon = atan(rx, -rz);
                float lat = asin(clamp(py, -1.0, 1.0));
                gl_FragColor = texture2D(uTex, vec2(lon / 6.28318530718 + 0.5, 0.5 - lat / 3.14159265359));
            }
        """
    }
}
