package com.sernheise.app.gl

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

    const val RESOLVE_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_texCoord;
        uniform samplerExternalOES u_original;
        uniform mat4 u_texMatrix;
        void main() { 
            vec2 uv = (u_texMatrix * vec4(v_texCoord, 0.0, 1.0)).xy;
            gl_FragColor = texture2D(u_original, uv); 
        }
    """

    const val RESOLVE_2D = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        void main() { gl_FragColor = texture2D(u_original, v_texCoord); }
    """

    const val BLUR_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        uniform vec2 u_step;
        
        void main() {
            vec4 sum = vec4(0.0);
            sum += texture2D(u_texture, v_texCoord - 4.0 * u_step) * 0.05;
            sum += texture2D(u_texture, v_texCoord - 3.0 * u_step) * 0.09;
            sum += texture2D(u_texture, v_texCoord - 2.0 * u_step) * 0.12;
            sum += texture2D(u_texture, v_texCoord - 1.0 * u_step) * 0.15;
            sum += texture2D(u_texture, v_texCoord) * 0.18;
            sum += texture2D(u_texture, v_texCoord + 1.0 * u_step) * 0.15;
            sum += texture2D(u_texture, v_texCoord + 2.0 * u_step) * 0.12;
            sum += texture2D(u_texture, v_texCoord + 3.0 * u_step) * 0.09;
            sum += texture2D(u_texture, v_texCoord + 4.0 * u_step) * 0.05;
            gl_FragColor = sum;
        }
    """

    const val BILATERAL_FRAGMENT = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        uniform vec2 u_texel;
        
        const int RADIUS = 4;
        const float SIGMA_SPATIAL = 4.0;
        const float SIGMA_COLOR = 0.15;
        
        void main() {
            vec3 centerColor = texture2D(u_original, v_texCoord).rgb;
            vec3 sumColor = vec3(0.0);
            float sumWeight = 0.0;
            
            for(int x = -RADIUS; x <= RADIUS; x++) {
                for(int y = -RADIUS; y <= RADIUS; y++) {
                    vec2 offset = vec2(float(x), float(y));
                    vec3 color = texture2D(u_original, v_texCoord + offset * u_texel).rgb;
                    
                    float spatialDist2 = dot(offset, offset);
                    float colorDist2 = dot(color - centerColor, color - centerColor);
                    
                    float spatialWeight = exp(-spatialDist2 / (2.0 * SIGMA_SPATIAL * SIGMA_SPATIAL));
                    float colorWeight = exp(-colorDist2 / (2.0 * SIGMA_COLOR * SIGMA_COLOR));
                    
                    float weight = spatialWeight * colorWeight;
                    sumColor += color * weight;
                    sumWeight += weight;
                }
            }
            gl_FragColor = vec4(sumColor / sumWeight, 1.0);
        }
    """

    const val COMPOSITE_FRAGMENT = """
        precision highp float;
        varying vec2 v_texCoord;
        uniform sampler2D u_original;
        uniform sampler2D u_clarityBlur;
        uniform sampler2D u_structureBlur;
        uniform float u_clarityAmount; // -1 to 1
        uniform float u_structureAmount; // 0 to 1
        
        float lumOf(vec3 c) { return c.r * 0.3333 + c.g * 0.5 + c.b * 0.1667; }
        
        void main() {
            vec3 orig = texture2D(u_original, v_texCoord).rgb;
            vec3 cBlur = texture2D(u_clarityBlur, v_texCoord).rgb;
            vec3 sBlur = texture2D(u_structureBlur, v_texCoord).rgb;
            
            float srcLum = lumOf(orig);
            float blurLumClarity = lumOf(cBlur);
            float blurLumStructure = lumOf(sBlur);
            
            // 1. CLARITY (CapCut formula, mid-freq contrast)
            float structureS = 0.0;
            if (u_clarityAmount > 0.0) {
                structureS = 0.76 * u_clarityAmount * u_clarityAmount;
            } else {
                structureS = -0.4 * u_clarityAmount * u_clarityAmount;
            }
            float finalLumClarity = srcLum + structureS * (srcLum * 1.4 - blurLumClarity * 1.484 + 0.042) - 0.5;
            
            // 2. STRUCTURE (Bilateral high-pass)
            float structureDetail = srcLum - blurLumStructure;
            float finalLumStructure = finalLumClarity + structureDetail * u_structureAmount * 4.0;
            
            // 3. CAPCUT SATURATION PRESERVATION
            float meanVal = srcLum - 0.5;
            float denom = 0.275 - meanVal * meanVal;
            float denomSign = denom < 0.0 ? -1.0 : 1.0;
            denom = denomSign * max(abs(denom), 0.0001);
            
            float scale = min((0.275 - finalLumStructure * finalLumStructure) / denom * (1.0 + structureS / 5.0), 5.0);
            
            vec3 result = finalLumStructure + (orig - vec3(srcLum)) * scale + 0.5;
            gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
        }
    """
}
