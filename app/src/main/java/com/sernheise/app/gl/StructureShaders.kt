package com.sernheise.app.gl

/**
 * GLSL sources for [StructurePipeline]: The "Structure" effect.
 *
 * 1. [DIFF_FRAGMENT]: 11x11 bilateral blur, outputs detail = original - blur, packed as (d+1)/2.
 * 2. [COMPOSITE_FRAGMENT]: out = original + intensity * detail.
 */
object StructureShaders {

    const val PASSTHROUGH_VERTEX = """
        attribute vec4 a_position;
        attribute vec2 a_texCoord;
        varying vec2 v_texCoord;
        void main() {
            gl_Position = a_position;
            v_texCoord = a_texCoord;
        }
    """

    const val RESOLVE_FRAGMENT_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_texCoord;
        uniform samplerExternalOES u_texture;
        uniform mat4 u_texMatrix;
        void main() {
            vec2 uv = (u_texMatrix * vec4(v_texCoord, 0.0, 1.0)).xy;
            gl_FragColor = texture2D(u_texture, uv);
        }
    """

    const val RESOLVE_FRAGMENT_2D = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        void main() {
            gl_FragColor = texture2D(u_texture, v_texCoord);
        }
    """

    /** u_step = bstep / size, where bstep = max(1, max(W, H) / 500) (integer division). */
    const val DIFF_FRAGMENT = """
        precision highp float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        uniform vec2 u_step;

        #define RADIUS 5
        #define INTENSITYSMOOTH2 (0.12 * 0.12)
        #define SPACESMOOTH2 (25.0 * (2.0 / 1.41) * (2.0 / 1.41))
        #define LUMWEIGHT vec4(0.2126, 0.7152, 0.0722, 0.0)

        void main() {
            vec4 center = texture2D(u_texture, v_texCoord);
            vec4 acc = vec4(0.0);
            float sum = 0.0;
            for (int i = -RADIUS; i <= RADIUS; i++) {
                for (int j = -RADIUS; j <= RADIUS; j++) {
                    vec4 c = texture2D(u_texture, v_texCoord + vec2(float(i), float(j)) * u_step);
                    vec4 d = c - center;
                    float w = exp(-(dot(d * d, LUMWEIGHT) / INTENSITYSMOOTH2 + float(i * i + j * j) / SPACESMOOTH2));
                    acc += c * w;
                    sum += w;
                }
            }
            gl_FragColor = ((center - acc / sum) + 1.0) / 2.0;
        }
    """

    const val COMPOSITE_FRAGMENT = """
        precision highp float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        uniform sampler2D u_diff;
        uniform float u_intensity;
        void main() {
            vec4 col = texture2D(u_original, v_texCoord);
            vec4 diff = texture2D(u_diff, v_texCoord);
            gl_FragColor = clamp(col + u_intensity * (diff * 2.0 - 1.0), 0.0, 1.0);
        }
    """
}
