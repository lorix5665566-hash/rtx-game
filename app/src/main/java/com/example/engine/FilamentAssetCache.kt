package com.example.engine

import com.example.model.ModelSlot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized native DirectByteBuffer & Asset Cache shared across Lobby and Gameplay scenes.
 * Eliminates redundant APK disk reads and heap spikes when instantiating multiple operatives,
 * third-person weapons, or transitioning between Lobby and Match — with strict revision-aware
 * invalidation and cleanup so zero stale/leaked buffers remain.
 */
object FilamentAssetCache {

    private data class CacheEntry(
        val buffer: ByteBuffer,
        val sizeBytes: Int,
        @Volatile var lastAccessNs: Long
    )

    // Keep up to 96 MB of hot GLB binary buffers in native memory (weapons, characters, hands, VFX)
    // Large multi-ten-MB map GLBs are loaded directly on demand so RAM stays lean on all phones.
    private const val MAX_SINGLE_ENTRY_BYTES = 12 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96 * 1024 * 1024

    private val entries = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var totalCachedBytes: Long = 0L

    fun getOrLoad(key: String, loader: () -> ByteBuffer?): ByteBuffer? {
        entries[key]?.let { existing ->
            existing.lastAccessNs = System.nanoTime()
            return duplicateAtZero(existing.buffer)
        }

        val loaded = loader() ?: return null
        val size = loaded.remaining()
        if (size <= 0) return null

        // Do not hoard giant map GLBs in the hot pool; return them directly
        if (size > MAX_SINGLE_ENTRY_BYTES) {
            return loaded
        }

        synchronized(this) {
            entries[key]?.let { raced ->
                raced.lastAccessNs = System.nanoTime()
                return duplicateAtZero(raced.buffer)
            }
            evictIfNeededLocked(size)
            val entry = CacheEntry(
                buffer = loaded.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),
                sizeBytes = size,
                lastAccessNs = System.nanoTime()
            )
            entries[key] = entry
            totalCachedBytes += size
            return duplicateAtZero(entry.buffer)
        }
    }

    fun invalidateSlot(slot: ModelSlot) {
        val prefix = "slot:${slot.name}:"
        synchronized(this) {
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key.startsWith(prefix)) {
                    totalCachedBytes = (totalCachedBytes - e.value.sizeBytes).coerceAtLeast(0L)
                    it.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(this) {
            entries.clear()
            totalCachedBytes = 0L
        }
    }

    private fun evictIfNeededLocked(incomingBytes: Int) {
        while (totalCachedBytes + incomingBytes > MAX_TOTAL_BYTES && entries.isNotEmpty()) {
            var oldestKey: String? = null
            var oldestTime = Long.MAX_VALUE
            for ((k, v) in entries) {
                if (v.lastAccessNs < oldestTime) {
                    oldestTime = v.lastAccessNs
                    oldestKey = k
                }
            }
            val keyToDrop = oldestKey ?: break
            val removed = entries.remove(keyToDrop)
            if (removed != null) {
                totalCachedBytes = (totalCachedBytes - removed.sizeBytes).coerceAtLeast(0L)
            }
        }
    }

    private fun duplicateAtZero(source: ByteBuffer): ByteBuffer {
        val dup = source.duplicate().order(ByteOrder.nativeOrder())
        dup.rewind()
        return dup
    }
}
