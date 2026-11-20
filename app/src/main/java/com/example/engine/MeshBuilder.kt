package com.example.engine

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
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

object MeshBuilder {
    fun createBox(sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): GlMesh {
        val hx = sx * 0.5f
        val hy = sy * 0.5f
        val hz = sz * 0.5f

        // 24 vertices (4 per face) with crisp normals and UVs
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

    /**
     * Smooth sculpted ellipsoid for realistic 1:1 human head, cranium, shoulders,
     * kneecaps, ballistic helmet dome, and anatomical joints.
     */
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

    /**
     * Smooth organically tapered limb/torso capsule-like cylinder along Y from 0 to +height
     * (or centered from -height/2 to +height/2) with anatomical bulge for muscles.
     */
    fun createTaperedLimb(
        rBottomX: Float,
        rBottomZ: Float,
        rTopX: Float,
        rTopZ: Float,
        height: Float,
        muscleBulge: Float = 1.06f,
        segments: Int = 16,
        rings: Int = 8,
        centered: Boolean = true
    ): GlMesh {
        val verts = ArrayList<Float>((rings + 1) * (segments + 1) * 8)
        val indices = ArrayList<Short>(rings * segments * 6)
        val yOffset = if (centered) -height * 0.5f else 0f

        for (r in 0..rings) {
            val t = r.toFloat() / rings
            val y = yOffset + t * height
            // Smooth muscle taper & rounded end pinch
            val capPinch = when {
                r == 0 || r == rings -> 0.35f
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
}
