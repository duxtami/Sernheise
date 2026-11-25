package com.sernheise.app.gl

/**
 * GLSL sources for [BilateralLchPipeline] -- a second, independent "Structure" technique
 * ported from the manual bilateral-diff + LCH-lock chain, added alongside (not replacing)
 * [StructureShaders]'s guided-filter pipeline so both are selectable.
 *
 * Two passes, matching the original two shaders 1:1:
 *  1. [BILATERAL_DIFF_FRAGMENT] -- a joint bilateral filter (range + spatial Gaussian
 *     weights) computed directly against the original, full-res image; the signed
 *     difference (original - bilateral blur) is the raw detail/structure signal, encoded
 *     into [0,1] the same way the source file did ((diff+1)/2).
 *  2. [LCH_COMPOSITE_FRAGMENT] -- decodes that diff, adds it back to the original scaled by
 *     `u_intensity`, then converts both the original and the adjusted color to LCH and
 *     locks the adjusted color's chroma (L) and hue (H) back to the *original* pixel's
 *     values before converting back to RGB -- so only lightness is ever affected by the
 *     detail signal, and hue/saturation cannot drift no matter how strong the effect.
 *
 * Ported-fix: the original `filter_structure_blc_fs.glsl` computed `lch`/`lch2` and then
 * never used them, outputting `col2` directly (dead code -- no actual LCH lock happened).
 * This port outputs `lch2rgb(lch2)` so the lock is real.
 *
 * Ported-fix: the original `filter_structure_blc_diff_fs.glsl` used a uniform `int radius`
 * as a GLSL `for` loop bound. GLSL ES 1.00 (what OpenGL ES 2.0 fragment shaders use)
 * requires loop bounds to be constant-expressions; a uniform-driven bound compiles on some
 * desktop-class drivers but is not guaranteed to compile on real Android GPU drivers. Here
 * the loop bound is the existing (previously-unused) `RADIUS` #define instead; the uniform
 * step (`u_texel` * `u_radiusPx`) still controls the actual sampling footprint in pixels,
 * so behavior is unchanged, only the loop-bound mechanism is made portable.
 */
object BilateralLchShaders {

    // Fixed loop-bound sample count (5 -> 11x11 taps), matching the original's #define
    // RADIUS(5) that was present but unused in the source file. This is a compile-time
    // constant on purpose -- see the class doc above for why a uniform loop bound is unsafe.
    private const val TAP_RADIUS = 5

    /**
     * Joint bilateral filter over the original image; outputs the signed (original - blur)
     * detail signal, bias-encoded to [0,1] as (diff+1)/2, same convention as the source shader.
     *
     * Uniforms:
     *  u_original   -- the resolved source image (full res).
     *  u_texel      -- 1/width, 1/height of u_original.
     *  u_radiusPx   -- spatial step between taps, in source pixels (was `bstep`).
     *  u_spaceSmooth2 -- spatial Gaussian falloff term (was `spacesmooth2`).
     */
    const val BILATERAL_DIFF_FRAGMENT = """
        precision highp float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        uniform vec2 u_texel;
        uniform float u_radiusPx;
        uniform float u_spaceSmooth2;

        const float INTENSITY_SMOOTH2 = 0.12 * 0.12;
        const vec4 LUM_WEIGHT = vec4(0.2126, 0.7152, 0.0722, 0.0);

        void main() {
            vec4 pixelColor = texture2D(u_original, v_texCoord);
            vec4 fragColor = vec4(0.0);
            float sum = 0.0;

            for (int i = -$TAP_RADIUS; i <= $TAP_RADIUS; i++) {
                for (int j = -$TAP_RADIUS; j <= $TAP_RADIUS; j++) {
                    vec2 uv = v_texCoord + vec2(float(i), float(j)) * u_radiusPx * u_texel;
                    vec4 color = texture2D(u_original, uv);
                    vec4 diff = color - pixelColor;
                    float coeff = exp(-(dot(diff * diff, LUM_WEIGHT) / INTENSITY_SMOOTH2
                        + float(i * i + j * j) / u_spaceSmooth2));
                    fragColor += color * coeff;
                    sum += coeff;
                }
            }

            fragColor /= sum;
            vec4 diff = pixelColor - fragColor;
            gl_FragColor = vec4(((diff + 1.0) / 2.0).rgb, 1.0);
        }
    """

    /**
     * Recombines the original with the (encoded) bilateral diff signal, then locks the
     * result's chroma/hue in LCH space back to the original's own chroma/hue so only
     * lightness moves.
     *
     * Uniforms:
     *  u_original    -- the resolved source image.
     *  u_diff        -- output of BILATERAL_DIFF_FRAGMENT, encoded to [0,1].
     *  u_intensity   -- effect strength (was `intensity`).
     */
    const val LCH_COMPOSITE_FRAGMENT = """
        precision highp float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        uniform sampler2D u_diff;
        uniform float u_intensity;

        const vec3 wref = vec3(1.0, 1.0, 1.0);

        float xyzF(float t) { return mix(pow(t, 1.0 / 3.0), 7.787037 * t + 0.139731, step(t, 0.00885645)); }
        float xyzR(float t) { return mix(t * t * t, 0.1284185 * (t - 0.139731), step(t, 0.20689655)); }

        vec3 rgb2lch(in vec3 c) {
            c *= mat3(0.4124, 0.3576, 0.1805,
                      0.2126, 0.7152, 0.0722,
                      0.0193, 0.1192, 0.9505);
            c.x = xyzF(c.x / wref.x);
            c.y = xyzF(c.y / wref.y);
            c.z = xyzF(c.z / wref.z);
            vec3 lab = vec3(max(0.0, 116.0 * c.y - 16.0), 500.0 * (c.x - c.y), 200.0 * (c.y - c.z));
            return vec3(lab.x, length(vec2(lab.y, lab.z)), atan(lab.z, lab.y));
        }

        vec3 lch2rgb(in vec3 c) {
            c = vec3(c.x, cos(c.z) * c.y, sin(c.z) * c.y);
            float lg = 1.0 / 116.0 * (c.x + 16.0);
            vec3 xyz = vec3(
                wref.x * xyzR(lg + 0.002 * c.y),
                wref.y * xyzR(lg),
                wref.z * xyzR(lg - 0.005 * c.z)
            );
            vec3 rgb = xyz * mat3(3.2406, -1.5372, -0.4986,
                                  -0.9689, 1.8758, 0.0415,
                                   0.0557, -0.2040, 1.0570);
            return rgb;
        }

        void main() {
            vec4 col = texture2D(u_original, v_texCoord);
            vec4 diff = texture2D(u_diff, v_texCoord);
            vec4 col2 = col + u_intensity * (diff * 2.0 - 1.0);

            vec3 lch = rgb2lch(col.rgb);
            vec3 lch2 = rgb2lch(col2.rgb);
            // Lock chroma + hue to the ORIGINAL pixel's values -- only lightness (lch2.x,
            // already derived from the detail-adjusted color2) is allowed to move. This is
            // the fix over the source shader, which computed this and then discarded it.
            lch2.y = lch.y;
            lch2.z = lch.z;

            vec3 result = lch2rgb(lch2);
            gl_FragColor = vec4(clamp(result, 0.0, 1.0), col.a);
        }
    """
}
