package com.sernheise.app.gl

/**
 * Single-pass fragment shader implementing every Sernheise tonal adjustment plus Sharpen.
 *
 * This shader only ever samples a plain sampler2D. Video no longer needs its own
 * samplerExternalOES variant here: [StructurePipeline] always resolves a video frame to a
 * plain 2D texture first (a cheap copy pass when Structure is off, or the actual
 * multi-scale detail pipeline's output when it's on), so by the time this shader runs,
 * photo and video are indistinguishable.
 *
 * Structure itself is NOT implemented here anymore -- seeStructurePipeline/StructureShaders
 * for the real multi-scale, edge-aware detail-enhancement pipeline. This shader keeps
 * Sharpen (a small-radius luminance unsharp mask -- a genuinely different, simpler tool)
 * plus every tonal adjustment (exposure, brightness, contrast, saturation, highlights,
 * shadows, white balance).
 */
object SernShaders {

    const val VERTEX = """
        attribute vec4 a_position;
        attribute vec2 a_texCoord;
        varying vec2 v_texCoord;
        uniform mat4 u_mvpMatrix;
        uniform mat4 u_texMatrix;
        void main() {
            gl_Position = u_mvpMatrix * a_position;
            v_texCoord = (u_texMatrix * vec4(a_texCoord, 0.0, 1.0)).xy;
        }
    """

    const val FRAGMENT_2D = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        uniform vec2 u_texel;

        uniform float u_exposure;
        uniform float u_brightness;
        uniform float u_contrast;
        uniform float u_saturation;
        uniform float u_highlights;
        uniform float u_shadows;
        uniform float u_temperature;
        uniform float u_tint;
        uniform float u_sharpen;

        vec3 sampleAt(vec2 uv) {
            return texture2D(u_texture, uv).rgb;
        }

        float lumOf(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        void main() {
            vec3 color = sampleAt(v_texCoord);

            // ---- Exposure (stops) ----
            color *= pow(2.0, u_exposure);

            // ---- Brightness ----
            color += u_brightness;

            // ---- White balance: temperature (blue<->orange) & tint (green<->magenta) ----
            color.r += u_temperature * 0.35;
            color.b -= u_temperature * 0.35;
            color.g += u_tint * 0.30;
            color.r -= u_tint * 0.15;
            color.b -= u_tint * 0.15;

            // ---- Luminance-based highlights / shadows ----
            float toneLum = lumOf(color);
            float highlightMask = smoothstep(0.45, 1.0, toneLum);
            float shadowMask = 1.0 - smoothstep(0.0, 0.55, toneLum);
            color += vec3(u_highlights) * highlightMask * 0.6;
            color += vec3(u_shadows) * shadowMask * 0.6;

            // ---- Contrast (pivot at mid-gray) ----
            float contrastFactor = 1.0 + u_contrast;
            color = (color - 0.5) * contrastFactor + 0.5;

            // ---- Saturation ----
            float gray = lumOf(color);
            color = mix(vec3(gray), color, 1.0 + u_saturation);

            // ---- Sharpen: small-radius luminance unsharp mask ----
            if (u_sharpen > 0.001) {
                float centerLum = lumOf(color);
                vec3 fN = sampleAt(v_texCoord + vec2(0.0, -u_texel.y));
                vec3 fS = sampleAt(v_texCoord + vec2(0.0,  u_texel.y));
                vec3 fE = sampleAt(v_texCoord + vec2( u_texel.x, 0.0));
                vec3 fW = sampleAt(v_texCoord + vec2(-u_texel.x, 0.0));
                float fineBlurLum = (lumOf(fN) + lumOf(fS) + lumOf(fE) + lumOf(fW)) / 4.0;
                float fineDetail = centerLum - fineBlurLum;
                float sharpenedLum = centerLum + fineDetail * u_sharpen * 1.6;
                float lumRatio = sharpenedLum / max(centerLum, 0.0001);
                color *= mix(1.0, lumRatio, step(0.0001, centerLum));
            }

            gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """
}
