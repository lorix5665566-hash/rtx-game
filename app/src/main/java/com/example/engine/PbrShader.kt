package com.example.engine

import android.opengl.GLES30
import android.util.Log

class PbrShader {
    val programId: Int
    val skyProgramId: Int

    val uMvp: Int
    val uModel: Int
    val uCamPos: Int
    val uTime: Int
    val uAlbedoTex: Int
    val uNormalTex: Int
    val uBaseColor: Int
    val uMetallic: Int
    val uRoughness: Int
    val uEmissive: Int
    val uReflectivity: Int
    val uUvScale: Int
    val uSunDir: Int
    val uSunColor: Int
    val uFogColor: Int
    val uFogDensity: Int
    val uLightPos: Int
    val uLightColor: Int
    val uLightRadius: Int
    val uQualityLevel: Int
    val uRtxReflections: Int
    val uDynamicLights: Int
    val uHdTextures: Int
    val uBloomAces: Int

    val skyUMvp: Int
    val skyUTime: Int
    val skyUBloomAces: Int
    val skyUTopColor: Int
    val skyUHorizonColor: Int
    val skyUSunDir: Int
    val skyUSunGlow: Int

    init {
        programId = compileProgram(PBR_VERTEX_SHADER, PBR_FRAGMENT_SHADER)
        skyProgramId = compileProgram(SKY_VERTEX_SHADER, SKY_FRAGMENT_SHADER)

        uMvp = GLES30.glGetUniformLocation(programId, "uMvp")
        uModel = GLES30.glGetUniformLocation(programId, "uModel")
        uCamPos = GLES30.glGetUniformLocation(programId, "uCamPos")
        uTime = GLES30.glGetUniformLocation(programId, "uTime")
        uAlbedoTex = GLES30.glGetUniformLocation(programId, "uAlbedoTex")
        uNormalTex = GLES30.glGetUniformLocation(programId, "uNormalTex")
        uBaseColor = GLES30.glGetUniformLocation(programId, "uBaseColor")
        uMetallic = GLES30.glGetUniformLocation(programId, "uMetallic")
        uRoughness = GLES30.glGetUniformLocation(programId, "uRoughness")
        uEmissive = GLES30.glGetUniformLocation(programId, "uEmissive")
        uReflectivity = GLES30.glGetUniformLocation(programId, "uReflectivity")
        uUvScale = GLES30.glGetUniformLocation(programId, "uUvScale")
        uSunDir = GLES30.glGetUniformLocation(programId, "uSunDir")
        uSunColor = GLES30.glGetUniformLocation(programId, "uSunColor")
        uFogColor = GLES30.glGetUniformLocation(programId, "uFogColor")
        uFogDensity = GLES30.glGetUniformLocation(programId, "uFogDensity")
        uLightPos = GLES30.glGetUniformLocation(programId, "uLightPos")
        uLightColor = GLES30.glGetUniformLocation(programId, "uLightColor")
        uLightRadius = GLES30.glGetUniformLocation(programId, "uLightRadius")
        uQualityLevel = GLES30.glGetUniformLocation(programId, "uQualityLevel")
        uRtxReflections = GLES30.glGetUniformLocation(programId, "uRtxReflections")
        uDynamicLights = GLES30.glGetUniformLocation(programId, "uDynamicLights")
        uHdTextures = GLES30.glGetUniformLocation(programId, "uHdTextures")
        uBloomAces = GLES30.glGetUniformLocation(programId, "uBloomAces")

        skyUMvp = GLES30.glGetUniformLocation(skyProgramId, "uMvp")
        skyUTime = GLES30.glGetUniformLocation(skyProgramId, "uTime")
        skyUBloomAces = GLES30.glGetUniformLocation(skyProgramId, "uBloomAces")
        skyUTopColor = GLES30.glGetUniformLocation(skyProgramId, "uTopColor")
        skyUHorizonColor = GLES30.glGetUniformLocation(skyProgramId, "uHorizonColor")
        skyUSunDir = GLES30.glGetUniformLocation(skyProgramId, "uSunDir")
        skyUSunGlow = GLES30.glGetUniformLocation(skyProgramId, "uSunGlow")
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
        GLES30.glDeleteProgram(skyProgramId)
    }

    private fun compileProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    companion object {
        private const val TAG = "PbrShader"

        private val PBR_VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;

            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform vec2 uUvScale;

            out vec3 vWorldPos;
            out vec3 vWorldNormal;
            out vec2 vUv;

            void main() {
                vec4 worldPos = uModel * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vWorldNormal = normalize(mat3(uModel) * aNormal);
                vUv = aUv * uUvScale;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        private val PBR_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            in vec3 vWorldPos;
            in vec3 vWorldNormal;
            in vec2 vUv;

            uniform vec3 uCamPos;
            uniform float uTime;
            uniform sampler2D uAlbedoTex;
            uniform sampler2D uNormalTex;

            uniform vec3 uBaseColor;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform vec3 uEmissive;
            uniform float uReflectivity;

            uniform vec3 uSunDir;
            uniform vec3 uSunColor;
            uniform vec3 uFogColor;
            uniform float uFogDensity;
            uniform vec3 uLightPos[4];
            uniform vec3 uLightColor[4];
            uniform float uLightRadius[4];

            uniform int uQualityLevel;
            uniform int uRtxReflections;
            uniform int uDynamicLights;
            uniform int uHdTextures;
            uniform int uBloomAces;

            out vec4 fragColor;

            const float PI = 3.14159265359;

            float distributionGGX(vec3 N, vec3 H, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float NdotH = max(dot(N, H), 0.0);
                float NdotH2 = NdotH * NdotH;
                float denom = (NdotH2 * (a2 - 1.0) + 1.0);
                return a2 / max(PI * denom * denom, 0.0001);
            }

            float geometrySchlickGGX(float NdotV, float roughness) {
                float r = roughness + 1.0;
                float k = (r * r) / 8.0;
                return NdotV / max(NdotV * (1.0 - k) + k, 0.0001);
            }

            float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
                float NdotV = max(dot(N, V), 0.0);
                float NdotL = max(dot(N, L), 0.0);
                return geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(NdotL, roughness);
            }

            vec3 fresnelSchlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
            }

            vec3 perturbNormal(vec3 N, vec3 p, vec2 uv, vec3 mapN) {
                vec3 dp1 = dFdx(p);
                vec3 dp2 = dFdy(p);
                vec2 duv1 = dFdx(uv);
                vec2 duv2 = dFdy(uv);
                vec3 dp2perp = cross(dp2, N);
                vec3 dp1perp = cross(N, dp1);
                vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
                vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
                float invmax = inversesqrt(max(dot(T, T), dot(B, B)) + 1e-6);
                mat3 TBN = mat3(T * invmax, B * invmax, N);
                return normalize(TBN * mapN);
            }

            vec3 sampleEnvironmentRtx(vec3 R, float roughness) {
                float up = clamp(R.y, -1.0, 1.0);
                vec3 zenith = uFogColor * 0.75 + vec3(0.03, 0.06, 0.10);
                vec3 horizon = uFogColor * 1.9 + vec3(0.06, 0.09, 0.12);
                vec3 ground = uFogColor * 0.35;
                vec3 env = up >= 0.0
                    ? mix(horizon, zenith, pow(up, 0.55))
                    : mix(horizon * 0.45, ground, clamp(-up * 2.5, 0.0, 1.0));

                float az = atan(R.z, R.x);
                float bandA = pow(max(0.0, sin(az * 2.0 + 0.7)), 6.0) * exp(-abs(up - 0.12) * 8.0);
                float bandB = pow(max(0.0, cos(az * 2.0 - 0.5)), 6.0) * exp(-abs(up - 0.10) * 9.0);
                float clarity = mix(1.0, 0.22, roughness);
                env += uLightColor[0] * bandA * 0.35 * clarity;
                env += uLightColor[1] * bandB * 0.35 * clarity;

                float sunSpec = pow(max(dot(R, normalize(uSunDir)), 0.0), mix(140.0, 12.0, roughness));
                env += uSunColor * sunSpec * 1.35 * clarity;
                return env;
            }

            vec3 evalPbrLight(vec3 N, vec3 V, vec3 L, vec3 radiance, vec3 albedo, float metallic, float roughness, vec3 F0) {
                float NdotL = max(dot(N, L), 0.0);
                if (NdotL <= 0.0) return vec3(0.0);
                vec3 H = normalize(V + L);
                if (uQualityLevel == 0) {
                    float spec = pow(max(dot(N, H), 0.0), mix(48.0, 6.0, roughness)) * (1.0 - roughness * 0.6);
                    return (albedo * 0.85 + vec3(spec * 0.35)) * radiance * NdotL;
                }
                float NDF = distributionGGX(N, H, roughness);
                float G = geometrySmith(N, V, L, roughness);
                vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
                vec3 numerator = NDF * G * F;
                float denom = 4.0 * max(dot(N, V), 0.0) * NdotL + 0.001;
                vec3 specular = numerator / denom;
                vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
                return (kD * albedo / PI + specular) * radiance * NdotL;
            }

            vec3 acesToneMap(vec3 x) {
                const float a = 2.51;
                const float b = 0.03;
                const float c = 2.43;
                const float d = 0.59;
                const float e = 0.14;
                return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
            }

            void main() {
                vec4 texSample = texture(uAlbedoTex, vUv);
                vec3 albedo = (uHdTextures == 1)
                    ? (texSample.rgb * uBaseColor)
                    : (mix(vec3(0.75), texSample.rgb, 0.45) * uBaseColor);

                float puddleMask = clamp((texSample.a - 0.35) * 1.8, 0.0, 1.0) * uReflectivity;
                float roughness = clamp(uRoughness, 0.06, 0.98);
                if (uRtxReflections == 1 && puddleMask > 0.05) {
                    roughness = mix(roughness, 0.07, puddleMask);
                    albedo *= mix(1.0, 0.70, puddleMask);
                }

                vec3 N = normalize(vWorldNormal);
                if (uHdTextures == 1 && uQualityLevel >= 1) {
                    vec3 mapN = texture(uNormalTex, vUv).xyz * 2.0 - 1.0;
                    if (uRtxReflections == 1 && puddleMask > 0.25 && uQualityLevel >= 2) {
                        float rip = sin(vWorldPos.x * 16.0 + uTime * 5.0) * cos(vWorldPos.z * 16.0 - uTime * 4.2) * 0.032;
                        mapN = normalize(mix(mapN, vec3(rip, rip, 1.0), puddleMask * 0.80));
                    }
                    N = perturbNormal(N, vWorldPos, vUv, mapN);
                }

                vec3 V = normalize(uCamPos - vWorldPos);
                vec3 F0 = mix(vec3(0.04), albedo, uMetallic);
                if (uRtxReflections == 1 && puddleMask > 0.1) {
                    F0 = mix(F0, vec3(0.065), puddleMask);
                }

                float contactAo = mix(0.64, 1.0, clamp((vWorldPos.y + 0.05) * 0.9, 0.0, 1.0));
                vec3 skyAmb = mix(uFogColor * 0.8 + vec3(0.04), uSunColor * 0.22 + vec3(0.09, 0.12, 0.16), N.y * 0.5 + 0.5);
                vec3 color = albedo * skyAmb * (1.0 - uMetallic * 0.50) * contactAo;

                vec3 sunL = normalize(uSunDir);
                float shadowTerm = 1.0;
                if (uDynamicLights == 1 && vWorldPos.y < 0.08) {
                    float pattern = sin(vWorldPos.x * 0.35 + vWorldPos.z * 0.2) * 0.5 + 0.5;
                    shadowTerm = mix(0.75, 1.0, pattern);
                }
                color += evalPbrLight(N, V, sunL, uSunColor * shadowTerm, albedo, uMetallic, roughness, F0);

                if (uDynamicLights == 1) {
                    int lightCount = (uQualityLevel >= 2) ? 4 : 2;
                    for (int i = 0; i < 4; i++) {
                        if (i >= lightCount) break;
                        vec3 toL = uLightPos[i] - vWorldPos;
                        float dist = length(toL);
                        float rad = max(uLightRadius[i], 0.1);
                        if (dist < rad) {
                            vec3 L = toL / max(dist, 0.001);
                            float att = clamp(1.0 - (dist / rad), 0.0, 1.0);
                            att = att * att * 2.3;
                            color += evalPbrLight(N, V, L, uLightColor[i] * att, albedo, uMetallic, roughness, F0);
                        }
                    }
                }

                if (uRtxReflections == 1) {
                    vec3 R = reflect(-V, N);
                    vec3 envRefl = sampleEnvironmentRtx(R, roughness);
                    vec3 Fenv = fresnelSchlick(max(dot(N, V), 0.0), F0);
                    float reflWeight = max(uMetallic * 0.82, puddleMask * 1.15);
                    if (uQualityLevel >= 3) {
                        color += envRefl * Fenv * (0.48 + reflWeight * 1.30);
                    } else {
                        color += envRefl * Fenv * (0.25 + reflWeight * 0.72);
                    }
                } else {
                    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
                    color += vec3(0.08, 0.11, 0.15) * rim * (1.0 - roughness);
                }

                color += uEmissive;

                float distToCam = length(uCamPos - vWorldPos);
                float fogFactor = 1.0 - exp(-distToCam * distToCam * uFogDensity);
                color = mix(color, uFogColor, clamp(fogFactor, 0.0, 0.86));

                if (uBloomAces == 1) {
                    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                    if (luma > 0.88) {
                        color += (color - vec3(0.88)) * 0.30;
                    }
                    color = acesToneMap(color * 1.12);
                } else {
                    color = color / (color + vec3(1.0));
                }

                color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()

        private val SKY_VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            layout(location = 0) in vec3 aPos;
            uniform mat4 uMvp;
            out vec3 vDir;

            void main() {
                vDir = normalize(aPos);
                vec4 pos = uMvp * vec4(aPos, 1.0);
                gl_Position = pos.xyww;
            }
        """.trimIndent()

        private val SKY_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            in vec3 vDir;
            uniform float uTime;
            uniform int uBloomAces;
            uniform vec3 uTopColor;
            uniform vec3 uHorizonColor;
            uniform vec3 uSunDir;
            uniform vec3 uSunGlow;
            out vec4 fragColor;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash(i + vec2(0.0, 0.0)), hash(i + vec2(1.0, 0.0)), u.x),
                    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
                    u.y
                );
            }

            float fbm(vec2 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += a * noise(p);
                    p *= 2.02;
                    a *= 0.5;
                }
                return v;
            }

            void main() {
                vec3 d = normalize(vDir);
                float h = clamp(d.y, 0.0, 1.0);
                vec3 sky = mix(uHorizonColor, uTopColor, pow(h, 0.55));

                if (d.y > 0.01) {
                    vec2 cloudUv = d.xz / (d.y + 0.25) * 1.4 + vec2(uTime * 0.010, uTime * 0.005);
                    float c = fbm(cloudUv);
                    float cloudMask = smoothstep(0.42, 0.82, c) * smoothstep(0.01, 0.25, d.y);
                    vec3 cloudCol = mix(uTopColor * 1.2, uHorizonColor * 1.65, c);
                    sky = mix(sky, cloudCol, cloudMask * 0.68);
                }

                vec3 sunDirN = normalize(uSunDir);
                float sunDot = max(dot(d, sunDirN), 0.0);
                sky += uSunGlow * pow(sunDot, 12.0) * 0.55;
                sky += (uSunGlow + vec3(0.4)) * smoothstep(0.994, 0.999, sunDot) * 1.5;

                if (uBloomAces == 1) {
                    sky = sky / (sky + vec3(0.85));
                }
                sky = pow(clamp(sky, 0.0, 1.0), vec3(1.0 / 2.2));
                fragColor = vec4(sky, 1.0);
            }
        """.trimIndent()
    }
}
