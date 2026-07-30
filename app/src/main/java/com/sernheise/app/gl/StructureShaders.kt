package com.sernheise.app.gl

/**
 * GLSL sources for [StructurePipeline]'s passes.
 *
 * ARCHITECTURE (rewritten from a Laplacian/blur-difference pyramid to a guided filter):
 *
 * A Laplacian-style pyramid -- subtract a blurred version of the image from itself to get
 * a "detail" signal -- fundamentally cannot avoid some edge leakage, no matter how the
 * blur is weighted (plain Gaussian, bilateral/range-weighted, whatever): any blur mixes
 * *some* information across a real edge, so the detail signal computed from it is never
 * exactly zero at edges, only smaller. Every gain-suppression heuristic layered on top of
 * that (edge protection, texture confidence, etc.) is patching a signal that's already
 * slightly contaminated. That contamination is what shows up as residual halos/black
 * fringing no matter how conservative the gain tuning gets.
 *
 * A guided filter (He et al., "Guided Image Filtering") is a different kind of primitive:
 * instead of a weighted average, it computes a per-pixel LOCAL LINEAR MODEL q = a*I + b,
 * where a and b are derived from the local mean/variance of a small window. Where local
 * variance is high (a real edge or real texture), a -> 1 and the filter's output tracks
 * the original value almost exactly -- it does not blur across the edge at all, by
 * construction. Where local variance is low (a flat area), a -> 0 and the output smooths
 * toward the local average. The practical upshot: detail = original - q is *naturally*
 * close to zero right at real edges, not because it was suppressed afterward, but because
 * q itself didn't move away from the original there. This is the standard reason guided
 * filtering is preferred over bilateral filtering for exactly this failure mode.
 *
 * Passes:
 *  1. [RESOLVE_FRAGMENT_OES] / [RESOLVE_FRAGMENT_2D] -- normalize the source (video OES
 *     frame or plain image texture) into one internal texture, exactly as before.
 *  2. [PACK_LUM_FRAGMENT] -- computes luminance once and packs (I, I^2) into one RG
 *     texture; both are needed to compute local variance without a second original-image
 *     pass per scale.
 *  3. [BOX_BLUR_RG_FRAGMENT] -- a plain separable box blur of an RG-packed texture, reused
 *     to compute the local mean of (I, I^2) at a given radius -- box (not Gaussian)
 *     because the guided filter's own math is what provides edge-awareness here, not the
 *     window shape.
 *  4. [GUIDED_AB_FRAGMENT] -- pointwise: from local mean(I) and mean(I^2), compute the
 *     local variance and the guided filter's per-pixel (a, b) linear coefficients.
 *  5. [GUIDED_Q_FRAGMENT] -- box-blurs (a, b) (the guided filter's own final smoothing
 *     step) and, on its last (vertical) pass, also samples the original luminance and
 *     computes q = mean_a * I + mean_b -- the edge-preserving smoothed reference at this
 *     radius. Outputs (q, mean_a) packed together, since mean_a doubles as a principled,
 *     mathematically-grounded edge/texture confidence map for the composite pass (no
 *     separate ad-hoc edge detector needed).
 *  6. [GLOW_EXTRACT_FRAGMENT] -- a genuinely different mechanism from everything above:
 *     detail enhancement (guided filter) is about texture/local contrast, but "glossy",
 *     "luminous", "silky shine" are a separate optical phenomenon -- light softly
 *     diffusing around highlights, the way a real lens/skin/hair actually behaves. This
 *     pass extracts only the bright, highlight-range luminance from the original (skin
 *     sheen, hair shine, catchlights in eyes), to be blurred at a wide radius and screen-
 *     blended back in the composite -- never touching texture or edge contrast at all.
 *  7. [COMPOSITE_FRAGMENT] -- runs the guided filter at two radii (a small one for fine
 *     detail, a larger one for broader local-contrast "richness"), derives detail as the
 *     differences (original - q_small) and (q_small - q_large), and blends them back in
 *     luminance only, additively, with shadow/highlight/chroma protection for near-black
 *     and near-clipping safety (unrelated to the edge-halo problem, still needed). A
 *     completely flat region has zero variance at every scale, so a -> 0, q -> the local
 *     mean, and detail -> 0 there too -- colors and flat regions are unaffected by
 *     construction, same guarantee as before. The wide-radius glow (see above) is screen-
 *     blended on top afterward -- a physically-motivated "adds light, never subtracts it"
 *     operation that can only ever brighten highlights softly, never sharpen or add
 *     contrast, which is what keeps it from reading as an edit.
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

    const val PACK_LUM_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        void main() {
            vec3 c = texture2D(u_texture, v_texCoord).rgb;
            float l = dot(c, vec3(0.299, 0.587, 0.114));
            gl_FragColor = vec4(l, l * l, 0.0, 1.0);
        }
    """

    /** Separable box blur (uniform weights) of an RG-packed texture; direction/radius are uniforms. */
    const val BOX_BLUR_RG_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        uniform vec2 u_texel;
        uniform vec2 u_direction;
        uniform float u_radius;

        vec2 sampleAt(float o) {
            vec2 uv = v_texCoord + u_direction * u_texel * u_radius * o;
            return texture2D(u_texture, uv).rg;
        }

        void main() {
            vec2 sum = sampleAt(0.0);
            sum += sampleAt(1.0) + sampleAt(-1.0);
            sum += sampleAt(2.0) + sampleAt(-2.0);
            sum += sampleAt(3.0) + sampleAt(-3.0);
            gl_FragColor = vec4(sum / 7.0, 0.0, 1.0);
        }
    """

    /** Pointwise guided-filter coefficients from local (mean_I, mean_I^2). */
    const val GUIDED_AB_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_meanCorr;
        uniform float u_epsilon;
        void main() {
            vec2 mc = texture2D(u_meanCorr, v_texCoord).rg;
            float meanI = mc.r;
            float corrI = mc.g;
            float varI = max(corrI - meanI * meanI, 0.0);
            float a = varI / (varI + u_epsilon);
            float b = meanI * (1.0 - a);
            gl_FragColor = vec4(a, b, 0.0, 1.0);
        }
    """

    /**
     * Box-blurs (a, b); on the final (vertical) pass also reconstructs q = mean_a * I + mean_b
     * against the true original luminance and outputs (q, mean_a) packed together.
     */
    const val GUIDED_Q_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_abTexture;
        uniform sampler2D u_original;
        uniform vec2 u_texel;
        uniform vec2 u_direction;
        uniform float u_radius;
        uniform float u_isFinalPass;

        vec2 sampleAt(float o) {
            vec2 uv = v_texCoord + u_direction * u_texel * u_radius * o;
            return texture2D(u_abTexture, uv).rg;
        }

        void main() {
            vec2 sum = sampleAt(0.0);
            sum += sampleAt(1.0) + sampleAt(-1.0);
            sum += sampleAt(2.0) + sampleAt(-2.0);
            sum += sampleAt(3.0) + sampleAt(-3.0);
            vec2 meanAB = sum / 7.0;

            if (u_isFinalPass > 0.5) {
                vec3 c = texture2D(u_original, v_texCoord).rgb;
                float lum = dot(c, vec3(0.299, 0.587, 0.114));
                float q = meanAB.x * lum + meanAB.y;
                gl_FragColor = vec4(q, meanAB.x, 0.0, 1.0);
            } else {
                gl_FragColor = vec4(meanAB, 0.0, 1.0);
            }
        }
    """

    /**
     * Isolates only the bright/highlight-range luminance from the original, to be blurred
     * at a wide radius and screen-blended back in the composite pass. Deliberately
     * luminance-only and confined to the upper tonal range: this is what makes it a
     * "glow around existing highlights" effect (skin sheen, hair shine, catchlights)
     * rather than anything that touches texture, edges, or midtone/shadow contrast.
     */
    const val GLOW_EXTRACT_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        void main() {
            vec3 c = texture2D(u_original, v_texCoord).rgb;
            float lum = dot(c, vec3(0.299, 0.587, 0.114));
            float brightMask = smoothstep(0.45, 0.90, lum);
            gl_FragColor = vec4(lum * brightMask, 0.0, 0.0, 1.0);
        }
    """

    const val COMPOSITE_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        uniform sampler2D u_qSmall;
        uniform sampler2D u_qLarge;
        uniform sampler2D u_glow;
        uniform float u_amount;

        float lumOf(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        // Cheap, approximate skin-tone heuristic (warm hue, R > G > B, moderate
        // saturation). Not a precise skin detector -- used only to soften the fine
        // detail layer on likely skin so pores/texture stay smooth. Deliberately capped
        // to moderate saturation so it doesn't fire on highly-saturated non-skin reds.
        float skinLikelihood(vec3 c) {
            float maxc = max(c.r, max(c.g, c.b));
            float minc = min(c.r, min(c.g, c.b));
            float delta = maxc - minc;
            float cond1 = step(0.30, c.r) * step(c.g, c.r) * step(c.b, c.g);
            float cond2 = smoothstep(0.03, 0.10, delta) * (1.0 - smoothstep(0.28, 0.50, delta));
            float cond3 = smoothstep(0.06, 0.12, c.r - c.b);
            return cond1 * cond2 * cond3;
        }

        // Soft-knee compression: full strength near zero, progressively compressed as the
        // delta grows, so enhancement fades out smoothly instead of clipping.
        float softApply(float base, float delta) {
            float headroom = max(delta > 0.0 ? (1.0 - base) : base, 0.10);
            float knee = smoothstep(0.0, 1.0, abs(delta) / headroom);
            return base + delta * (1.0 - knee * 0.7);
        }

        void main() {
            vec3 orig = texture2D(u_original, v_texCoord).rgb;
            float lumOrig = lumOf(orig);

            vec2 small = texture2D(u_qSmall, v_texCoord).rg; // (q_small, a_small)
            vec2 large = texture2D(u_qLarge, v_texCoord).rg; // (q_large, a_large)
            float qSmall = small.x;
            float aSmall = small.y; // guided filter's own edge/texture confidence -- principled, not a heuristic
            float qLarge = large.x;

            // Detail layers are edge-safe BY CONSTRUCTION: qSmall and qLarge individually
            // track real edges closely (that's the guided filter's defining property), so
            // these differences are naturally near-zero right at edges -- not suppressed
            // there after the fact, just genuinely small to begin with. That's what
            // eliminates halos structurally rather than by gain tuning.
            float detailFine = lumOrig - qSmall;
            float detailCoarse = qSmall - qLarge;

            float skin = skinLikelihood(orig);
            float fineReduction = mix(1.0, 0.45, skin);

            // Gain is close to uniform across the image on purpose: because detailFine is
            // already small at real edges/hard boundaries, there is little there to over-
            // amplify regardless of gain, so this pipeline does not need the heavy per-
            // pixel edge-suppression the previous (blur-difference) version required.
            float weighted = detailFine * fineReduction * 0.55 + detailCoarse * 0.6;
            float delta = weighted * u_amount;

            // Shadow protection: fades enhancement toward zero as luminance approaches
            // true black, regardless of which direction delta points -- preserves shadow
            // texture and black detail against ordinary sensor grain being amplified.
            float shadowRolloff = smoothstep(0.0, 0.14, lumOrig);
            delta *= shadowRolloff;

            // Highlight smoothness: gradually reduces enhancement well before any channel
            // reaches its clipping boundary, keeping bright regions creamy rather than gritty.
            float highlightSmooth = 1.0 - smoothstep(0.72, 0.95, lumOrig) * 0.55;
            delta *= highlightSmooth;

            // Chroma/highlight/shadow clip protection: fades the enhancement out further
            // as any channel approaches 0 or 1 in the direction delta would push it, since
            // pushing an already-near-clipped channel into clamp() is what shifts hue/
            // saturation and crushes detail.
            float maxChannel = max(orig.r, max(orig.g, orig.b));
            float minChannel = min(orig.r, min(orig.g, orig.b));
            float clipHeadroom = delta > 0.0 ? (1.0 - maxChannel) : minChannel;
            float chromaProtect = smoothstep(0.0, 0.22, clipHeadroom);
            delta *= chromaProtect;

            // Polish ("micro luminance"): a small, capped nudge of luminance toward the
            // small-radius guided-filter base (qSmall) itself -- an edge-preserving
            // reference, so this cannot bleed across real edges/texture the way blending
            // toward a naive blur could. Gated by (1 - aSmall): low guided-filter
            // confidence means a genuinely flat or only slightly rough area (the space
            // between skin pores, the flat part of a fabric weave), which is exactly what
            // this is meant to even out into something richer/smoother -- real pores,
            // strands, and weave texture register as higher-variance and are left alone,
            // which is what keeps this from reading as a plastic/beauty-filter smooth.
            float polishGate = 1.0 - aSmall;
            float polishAmount = polishGate * 0.26 * u_amount * shadowRolloff;
            float polishedBase = mix(lumOrig, qSmall, polishAmount);

            // Luminance-only, no global tone curve: a truly flat region has zero variance
            // at both scales, so aSmall -> 0, qSmall/qLarge -> the local mean (== lumOrig
            // in a flat area), detail -> 0, and this pixel passes through unchanged
            // regardless of Structure amount -- same guarantee as before, still holds here.
            float enhancedLum = softApply(polishedBase, delta);
            float lumDelta = enhancedLum - lumOrig;

            // Additive reconstruction: adding the same small delta to every channel is
            // bounded and stable at any luminance, unlike a multiplicative ratio (which
            // blows up as luminance approaches zero).
            vec3 result = orig + vec3(lumDelta);

            // Optical glow: a genuinely different mechanism from everything above -- not
            // texture, not local contrast, just a soft screen-blend of blurred highlight
            // luminance back onto the image. This is what a real lens/skin/hair actually
            // does with bright light (soft diffusion around specular highlights, sheen,
            // catchlights), and it is what reads as "glossy"/"luminous" rather than
            // "sharpened". Screen blend (a + b*(1-a)) can only ever add light, never
            // subtract or add contrast, and by construction can never push a channel past
            // 1.0 -- it softens/refines highlights rather than clipping them harder.
            float glowSample = texture2D(u_glow, v_texCoord).r;
            float glowContribution = glowSample * 0.35 * u_amount * shadowRolloff;
            result = result + glowContribution * (1.0 - result);

            gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
        }
    """
}
