package com.sernheise.app.gl

import android.opengl.GLES20
import android.util.Log

/** Compiles and links a vertex/fragment shader pair, exposing uniform/attribute locations. */
class ShaderProgram(vertexSrc: String, fragmentSrc: String) {

    val programHandle: Int

    val aPosition: Int
    val aTexCoord: Int
    val uMvpMatrix: Int
    val uTexMatrix: Int
    val uTexture: Int
    val uTexel: Int
    val uExposure: Int
    val uBrightness: Int
    val uContrast: Int
    val uSaturation: Int
    val uHighlights: Int
    val uShadows: Int
    val uTemperature: Int
    val uTint: Int
    val uSharpen: Int
    val uVibrance: Int

    init {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)

        programHandle = GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Could not link Sernheise shader program: $log")
            }
        }

        aPosition = GLES20.glGetAttribLocation(programHandle, "a_position")
        aTexCoord = GLES20.glGetAttribLocation(programHandle, "a_texCoord")
        uMvpMatrix = GLES20.glGetUniformLocation(programHandle, "u_mvpMatrix")
        uTexMatrix = GLES20.glGetUniformLocation(programHandle, "u_texMatrix")
        uTexture = GLES20.glGetUniformLocation(programHandle, "u_texture")
        uTexel = GLES20.glGetUniformLocation(programHandle, "u_texel")
        uExposure = GLES20.glGetUniformLocation(programHandle, "u_exposure")
        uBrightness = GLES20.glGetUniformLocation(programHandle, "u_brightness")
        uContrast = GLES20.glGetUniformLocation(programHandle, "u_contrast")
        uSaturation = GLES20.glGetUniformLocation(programHandle, "u_saturation")
        uHighlights = GLES20.glGetUniformLocation(programHandle, "u_highlights")
        uShadows = GLES20.glGetUniformLocation(programHandle, "u_shadows")
        uTemperature = GLES20.glGetUniformLocation(programHandle, "u_temperature")
        uTint = GLES20.glGetUniformLocation(programHandle, "u_tint")
        uSharpen = GLES20.glGetUniformLocation(programHandle, "u_sharpen")
        uVibrance = GLES20.glGetUniformLocation(programHandle, "u_vibrance")
    }

    fun use() = GLES20.glUseProgram(programHandle)

    fun applyAdjustments(adj: Adjustments, texelW: Float, texelH: Float) {
        GLES20.glUniform2f(uTexel, texelW, texelH)
        GLES20.glUniform1f(uExposure, adj.exposure / 100f * 1.4f)
        GLES20.glUniform1f(uBrightness, adj.brightness / 100f * 0.5f)
        GLES20.glUniform1f(uContrast, adj.contrast / 100f * 1.0f)
        GLES20.glUniform1f(uSaturation, adj.saturation / 100f * 1.2f)
        GLES20.glUniform1f(uHighlights, adj.highlights / 100f * 0.6f)
        GLES20.glUniform1f(uShadows, adj.shadows / 100f * 0.6f)
        GLES20.glUniform1f(uTemperature, adj.temperature / 100f * 0.35f)
        GLES20.glUniform1f(uTint, adj.tint / 100f * 0.35f)
        GLES20.glUniform1f(uSharpen, (adj.sharpen / 100f).coerceIn(0f, 1f))
        
        if (uVibrance >= 0) GLES20.glUniform1f(uVibrance, adj.vibrance / 100f * 1.5f)
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e("Sernheise", "Shader compile error: $log\n$src")
            throw RuntimeException("Could not compile shader (type=$type): $log")
        }
        return shader
    }
}
