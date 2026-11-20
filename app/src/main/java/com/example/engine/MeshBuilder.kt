package com.example.engine

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GlMesh(vertices: FloatArray, indices: ShortArray) {
    private val vboId: Int
    private val iboId: Int
    val indexCount: Int = indices.size

    init {
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        vboId = buffers[0]
        iboId = buffers[1]

        val vBuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vBuf.position(0)

        val iBuf = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
        iBuf.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, iBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun draw() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)

        val stride = 8 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, intArrayOf(vboId, iboId), 0)
    }
}

data class LoftRing(
    val y: Float,
    val rx: Float,
    val rz: Float,
    val offsetZ: Float = 0f,
    val offsetX: Float = 0f,
    val squareness: Float = 0f // 0 = oval, 0.35 = rounded superellipse (chest/vest/boot)
)

object MeshBuilder {
    fun createBox(sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): GlMesh {
        val hx = sx * 0.5f
        val hy = sy * 0.5f
        val hz = sz * 0.5f

        val v = floatArrayOf(
            // Front (+Z)
            -hx, -hy,  hz,  0f, 0f, 1f,  0f, 0f,
             hx, -hy,  hz,  0f, 0f, 1f,  1f, 0f,
             hx,  hy,  hz,  0f, 0f, 1f,  1f, 1f,
            -hx,  hy,  hz,  0f, 0f, 1f,  0f, 1f,
            // Back (-Z)
             hx, -hy, -hz,  0f, 0f,-1f,  0f, 0f,
            -hx, -hy, -hz,  0f, 0f,-1f,  1f, 0f,
            -hx,  hy, -hz,  0f, 0f,-1f,  1f, 1f,
             hx,  hy, -hz,  0f, 0f,-1f,  0f, 1f,
            // Right (+X)
             hx, -hy,  hz,  1f, 0f, 0f,  0f, 0f,
             hx, -hy, -hz,  1f, 0f, 0f,  1f, 0f,
             hx,  hy, -hz,  1f, 0f, 0f,  1f, 1f,
             hx,  hy,  hz,  1f, 0f, 0f,  0f, 1f,
            // Left (-X)
            -hx, -hy, -hz, -1f, 0f, 0f,  0f, 0f,
            -hx, -hy,  hz, -1f, 0f, 0f,  1f, 0f,
            -hx,  hy,  hz, -1f, 0f, 0f,  1f, 1f,
            -hx,  hy, -hz, -1f, 0f, 0f,  0f, 1f,
            // Top (+Y)
            -hx,  hy,  hz,  0f, 1f, 0f,  0f, 0f,
             hx,  hy,  hz,  0f, 1f, 0f,  1f, 0f,
             hx,  hy, -hz,  0f, 1f, 0f,  1f, 1f,
            -hx,  hy, -hz,  0f, 1f, 0f,  0f, 1f,
            // Bottom (-Y)
            -hx, -hy, -hz,  0f,-1f, 0f,  0f, 0f,
             hx, -hy, -hz,  0f,-1f, 0f,  1f, 0f,
             hx, -hy,  hz,  0f,-1f, 0f,  1f, 1f,
            -hx, -hy,  hz,  0f,-1f, 0f,  0f, 1f
        )

        val idx = ShortArray(36)
        for (f in 0 until 6) {
            val baseV = (f * 4).toShort()
            val baseI = f * 6
            idx[baseI] = baseV
            idx[baseI + 1] = (baseV + 1).toShort()
            idx[baseI + 2] = (baseV + 2).toShort()
            idx[baseI + 3] = baseV
            idx[baseI + 4] = (baseV + 2).toShort()
            idx[baseI + 5] = (baseV + 3).toShort()
        }
        return GlMesh(v, idx)
    }

    /**
     * Chamfered / beveled machined metal or polymer block so specular highlights wrap cleanly
     * around weapon receivers, slides, magazines, plate carriers, and armor pieces.
     */
    fun createBeveledBox(sx: Float, sy: Float, sz: Float, bevelRatio: Float = 0.16f): GlMesh {
        val hx = sx * 0.5f
        val hy = sy * 0.5f
        val hz = sz * 0.5f
        val b = minOf(hx, hy, hz) * bevelRatio.coerceIn(0.05f, 0.35f)
        val rings = listOf(
            LoftRing(-hy, (hx - b * 1.4f).coerceAtLeast(0.001f), (hz - b * 1.4f).coerceAtLeast(0.001f), squareness = 0.52f),
            LoftRing(-hy + b * 0.5f, (hx - b * 0.35f).coerceAtLeast(0.001f), (hz - b * 0.35f).coerceAtLeast(0.001f), squareness = 0.62f),
            LoftRing(-hy + b, hx, hz, squareness = 0.68f),
            LoftRing( hy - b, hx, hz, squareness = 0.68f),
            LoftRing( hy - b * 0.5f, (hx - b * 0.35f).coerceAtLeast(0.001f), (hz - b * 0.35f).coerceAtLeast(0.001f), squareness = 0.62f),
            LoftRing( hy, (hx - b * 1.4f).coerceAtLeast(0.001f), (hz - b * 1.4f).coerceAtLeast(0.001f), squareness = 0.52f)
        )
        return createProfiledLoft(rings, segments = 16, closeCaps = true)
    }

    /**
     * High-detail anatomical lofted mesh supporting superellipse cross-sections
     * (creates realistic sculpted chests, vests, helmets, combat boots, rifles, and limbs).
     */
    fun createProfiledLoft(
        rings: List<LoftRing>,
        segments: Int = 20,
        closeCaps: Boolean = true
    ): GlMesh {
        val allRings = if (closeCaps) {
            val first = rings.first()
            val last = rings.last()
            listOf(first.copy(rx = 0.0005f, rz = 0.0005f)) + rings + listOf(last.copy(rx = 0.0005f, rz = 0.0005f))
        } else {
            rings
        }
        val ringCount = allRings.size
        val verts = ArrayList<Float>(ringCount * (segments + 1) * 8)
        val indices = ArrayList<Short>((ringCount - 1) * segments * 6)

        val minY = allRings.first().y
        val spanY = (allRings.last().y - minY).takeIf { kotlin.math.abs(it) > 0.0001f } ?: 1f

        for (r in 0 until ringCount) {
            val cur = allRings[r]
            val prev = allRings[(r - 1).coerceAtLeast(0)]
            val next = allRings[(r + 1).coerceAtMost(ringCount - 1)]
            val v = ((cur.y - minY) / spanY).coerceIn(0f, 1f)
            val sq = cur.squareness.coerceIn(0f, 0.85f)

            for (s in 0..segments) {
                val phi = s * 2.0 * PI / segments
                val cosP = cos(phi).toFloat()
                val sinP = sin(phi).toFloat()

                // Superellipse blend (0 = pure ellipse, higher = sculpted chamfered box)
                val sx = blendSuper(cosP, sq)
                val sz = blendSuper(sinP, sq)

                val px = cur.offsetX + cur.rx * sx
                val pz = cur.offsetZ + cur.rz * sz

                // Tangent along ring and along profile for smooth studio normals
                val dY = next.y - prev.y
                val dR = ((next.rx - prev.rx) * sx * cosP + (next.rz - prev.rz) * sz * sinP)
                var nx = cosP / cur.rx.coerceAtLeast(0.002f)
                var nz = sinP / cur.rz.coerceAtLeast(0.002f)
                val horizLen = sqrt(nx * nx + nz * nz).coerceAtLeast(0.0001f)
                nx /= horizLen; nz /= horizLen

                var ny = when {
                    closeCaps && r == 0 -> -1.0f
                    closeCaps && r == ringCount - 1 -> 1.0f
                    else -> if (kotlin.math.abs(dY) > 0.0001f) -(dR / dY) * 0.75f else 0f
                }
                if (closeCaps && (r == 0 || r == ringCount - 1)) {
                    nx = 0f; nz = 0f
                }
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
                nx /= len; ny /= len; nz /= len

                verts.add(px); verts.add(cur.y); verts.add(pz)
                verts.add(nx); verts.add(ny); verts.add(nz)
                verts.add(s.toFloat() / segments); verts.add(v)
            }
        }

        val stride = segments + 1
        for (r in 0 until ringCount - 1) {
            for (s in 0 until segments) {
                val a = (r * stride + s).toShort()
                val b = ((r + 1) * stride + s).toShort()
                val c = ((r + 1) * stride + s + 1).toShort()
                val d = (r * stride + s + 1).toShort()
                indices.add(a); indices.add(b); indices.add(c)
                indices.add(a); indices.add(c); indices.add(d)
            }
        }
        return GlMesh(verts.toFloatArray(), indices.toShortArray())
    }

    private fun blendSuper(trig: Float, squareness: Float): Float {
        if (squareness <= 0.001f) return trig
        val sign = if (trig >= 0f) 1f else -1f
        val mag = kotlin.math.abs(trig)
        val exp = 1f - squareness * 0.65f
        return sign * mag.toDouble().pow(exp.toDouble()).toFloat()
    }

    fun createFloorQuad(width: Float, depth: Float): GlMesh {
        val hw = width * 0.5f
        val hd = depth * 0.5f
        val v = floatArrayOf(
            -hw, 0f,  hd,  0f, 1f, 0f,  0f, 0f,
             hw, 0f,  hd,  0f, 1f, 0f,  1f, 0f,
             hw, 0f, -hd,  0f, 1f, 0f,  1f, 1f,
            -hw, 0f, -hd,  0f, 1f, 0f,  0f, 1f
        )
        val idx = shortArrayOf(0, 1, 2, 0, 2, 3)
        return GlMesh(v, idx)
    }

    fun createEllipsoid(
        rx: Float,
        ry: Float,
        rz: Float,
        latSegs: Int = 14,
        lonSegs: Int = 18
    ): GlMesh {
        val verts = ArrayList<Float>((latSegs + 1) * (lonSegs + 1) * 8)
        val indices = ArrayList<Short>(latSegs * lonSegs * 6)

        for (lat in 0..latSegs) {
            val theta = lat * PI / latSegs
            val sinT = sin(theta).toFloat()
            val cosT = cos(theta).toFloat()
            val v = lat.toFloat() / latSegs

            for (lon in 0..lonSegs) {
                val phi = lon * 2.0 * PI / lonSegs
                val sinP = sin(phi).toFloat()
                val cosP = cos(phi).toFloat()
                val u = lon.toFloat() / lonSegs

                val px = rx * sinT * cosP
                val py = ry * cosT
                val pz = rz * sinT * sinP

                var nx = (sinT * cosP) / maxOf(rx, 0.001f)
                var ny = cosT / maxOf(ry, 0.001f)
                var nz = (sinT * sinP) / maxOf(rz, 0.001f)
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
                nx /= len; ny /= len; nz /= len

                verts.add(px); verts.add(py); verts.add(pz)
                verts.add(nx); verts.add(ny); verts.add(nz)
                verts.add(u); verts.add(v)
            }
        }

        val stride = lonSegs + 1
        for (lat in 0 until latSegs) {
            for (lon in 0 until lonSegs) {
                val a = (lat * stride + lon).toShort()
                val b = ((lat + 1) * stride + lon).toShort()
                val c = ((lat + 1) * stride + lon + 1).toShort()
                val d = (lat * stride + lon + 1).toShort()
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(a); indices.add(d); indices.add(c)
            }
        }

        return GlMesh(verts.toFloatArray(), indices.toShortArray())
    }

    fun createTaperedLimb(
        rBottomX: Float,
        rBottomZ: Float,
        rTopX: Float,
        rTopZ: Float,
        height: Float,
        muscleBulge: Float = 1.06f,
        segments: Int = 18,
        rings: Int = 8,
        centered: Boolean = true
    ): GlMesh {
        val verts = ArrayList<Float>((rings + 1) * (segments + 1) * 8)
        val indices = ArrayList<Short>(rings * segments * 6)
        val yOffset = if (centered) -height * 0.5f else 0f

        for (r in 0..rings) {
            val t = r.toFloat() / rings
            val y = yOffset + t * height
            val capPinch = when {
                r == 0 || r == rings -> 0.25f
                r == 1 || r == rings - 1 -> 0.92f
                else -> 1f + (muscleBulge - 1f) * sin(t * PI).toFloat()
            }
            val rx = (rBottomX + (rTopX - rBottomX) * t) * capPinch
            val rz = (rBottomZ + (rTopZ - rBottomZ) * t) * capPinch

            for (s in 0..segments) {
                val phi = s * 2.0 * PI / segments
                val cosP = cos(phi).toFloat()
                val sinP = sin(phi).toFloat()
                val px = rx * cosP
                val pz = rz * sinP

                var nx = cosP / maxOf(rx, 0.001f)
                var nz = sinP / maxOf(rz, 0.001f)
                var ny = when (r) {
                    0 -> -1.2f
                    rings -> 1.2f
                    else -> (rBottomX - rTopX) / maxOf(height, 0.01f)
                }
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
                nx /= len; ny /= len; nz /= len

                verts.add(px); verts.add(y); verts.add(pz)
                verts.add(nx); verts.add(ny); verts.add(nz)
                verts.add(s.toFloat() / segments); verts.add(t)
            }
        }

        val stride = segments + 1
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val a = (r * stride + s).toShort()
                val b = ((r + 1) * stride + s).toShort()
                val c = ((r + 1) * stride + s + 1).toShort()
                val d = (r * stride + s + 1).toShort()
                indices.add(a); indices.add(b); indices.add(c)
                indices.add(a); indices.add(c); indices.add(d)
            }
        }

        return GlMesh(verts.toFloatArray(), indices.toShortArray())
    }

    /**
     * Multi-plane volumetric flame mesh (3 intersecting longitudinal flame tongues along -Z
     * plus a front radial starburst card) for additive-blended realistic split-second gunfire flame.
     */
    fun createVolumetricMuzzleFlame(length: Float = 0.24f, radius: Float = 0.095f): GlMesh {
        val verts = ArrayList<Float>(16 * 8)
        val indices = ArrayList<Short>(24)
        var baseV = 0

        fun addQuad(
            x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
            x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
            x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
            x3: Float, y3: Float, z3: Float, u3: Float, v3: Float
        ) {
            verts.add(x0); verts.add(y0); verts.add(z0); verts.add(0f); verts.add(0f); verts.add(1f); verts.add(u0); verts.add(v0)
            verts.add(x1); verts.add(y1); verts.add(z1); verts.add(0f); verts.add(0f); verts.add(1f); verts.add(u1); verts.add(v1)
            verts.add(x2); verts.add(y2); verts.add(z2); verts.add(0f); verts.add(0f); verts.add(1f); verts.add(u2); verts.add(v2)
            verts.add(x3); verts.add(y3); verts.add(z3); verts.add(0f); verts.add(0f); verts.add(1f); verts.add(u3); verts.add(v3)
            val a = baseV.toShort()
            val b = (baseV + 1).toShort()
            val c = (baseV + 2).toShort()
            val d = (baseV + 3).toShort()
            indices.add(a); indices.add(b); indices.add(c)
            indices.add(a); indices.add(c); indices.add(d)
            baseV += 4
        }

        // 3 crossed longitudinal fire tongues extending along -Z from barrel muzzle (z = 0 to -length)
        val angles = floatArrayOf(0f, 60f, 120f)
        for (deg in angles) {
            val rad = Math.toRadians(deg.toDouble())
            val cx = cos(rad).toFloat() * radius
            val cy = sin(rad).toFloat() * radius
            addQuad(
                -cx, -cy,  0.015f, 0f, 0f,
                 cx,  cy,  0.015f, 1f, 0f,
                 cx,  cy, -length, 1f, 1f,
                -cx, -cy, -length, 0f, 1f
            )
        }

        // Front radial plasma star quad right in front of muzzle crown
        val rFront = radius * 1.15f
        addQuad(
            -rFront, -rFront, -0.025f, 0f, 0f,
             rFront, -rFront, -0.025f, 1f, 0f,
             rFront,  rFront, -0.025f, 1f, 1f,
            -rFront,  rFront, -0.025f, 0f, 1f
        )

        return GlMesh(verts.toFloatArray(), indices.toShortArray())
    }
}
