package com.sernheise.app.gl

import android.opengl.GLES20

/**
 * A minimal, generic compiled shader program used by [StructurePipeline]'s internal passes
 * (resolve / blur / composite). Kept separate from [ShaderProgram], which is deliberately
 * left untouched -- it still drives the main on-screen/export tonal shader exactly as
 * before, with its own fixed uniform set.
 */
class GlProgram(vertexSrc: String, fragmentSrc: String) {

    val handle: Int

    init {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        handle = GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Could not link program: $log")
            }
        }
    }

    fun use() = GLES20.glUseProgram(handle)
    fun attrib(name: String): Int = GLES20.glGetAttribLocation(handle, name)
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(handle, name)

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader (type=$type): $log\n$src")
        }
        return shader
    }
}
