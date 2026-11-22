package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.online.AvatarPresets
import com.example.ui.theme.IceCyan
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.ThreatCrimson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val avatarBitmapCache = LruCache<String, ImageBitmap>(48)

private fun decodeCachedAvatarBitmap(avatarData: String): ImageBitmap? {
    if (!avatarData.startsWith("data:image/jpeg;base64,")) return null
    avatarBitmapCache.get(avatarData)?.let { return it }
    return runCatching {
        val b64 = avatarData.removePrefix("data:image/jpeg;base64,")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        bmp.asImageBitmap().also { avatarBitmapCache.put(avatarData, it) }
    }.getOrNull()
}

/**
 * Center-crops and compresses a user-selected gallery image (from zero-permission Android Photo Picker / SAF)
 * into a compact 144x144 JPEG data URI (< 48 KB) safe for Supabase profile storage.
 * Uses ImageDecoder on API 28+ (supports HEIC/WebP/PNG/JPEG + EXIF rotation) with a single-pass
 * ByteArray BitmapFactory fallback for older devices and single-shot ContentProviders.
 */
suspend fun encodeGalleryPhotoToAvatarData(context: Context, uri: Uri): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver

            val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val source = ImageDecoder.createSource(resolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                        val w = info.size.width.coerceAtLeast(1)
                        val h = info.size.height.coerceAtLeast(1)
                        val maxSide = max(w, h)
                        if (maxSide > 512) {
                            val scale = 512f / maxSide.toFloat()
                            decoder.setTargetSize(
                                (w * scale).roundToInt().coerceAtLeast(1),
                                (h * scale).roundToInt().coerceAtLeast(1)
                            )
                        }
                    }
                }.getOrNull()
            } else {
                null
            } ?: run {
                // Single-pass stream read so one-shot MIUI / HyperOS / cloud ContentProviders never fail on 2nd open
                val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Не удалось открыть выбранное изображение.")
                require(rawBytes.isNotEmpty()) { "Выбранный файл пуст." }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                val srcW = bounds.outWidth
                val srcH = bounds.outHeight
                require(srcW > 0 && srcH > 0) { "Файл не является корректным изображением." }

                var sample = 1
                while ((srcW / sample) > 512 && (srcH / sample) > 512) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                    ?: error("Не удалось декодировать выбранное фото.")
            }

            val side = min(decoded.width, decoded.height).coerceAtLeast(1)
            val cropX = ((decoded.width - side) / 2).coerceAtLeast(0)
            val cropY = ((decoded.height - side) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(decoded, cropX, cropY, side, side)
            if (cropped !== decoded) decoded.recycle()

            val scaled = Bitmap.createScaledBitmap(cropped, 144, 144, true)
            if (scaled !== cropped) cropped.recycle()

            val out = ByteArrayOutputStream()
            var quality = 78
            var encoded: String
            try {
                while (true) {
                    out.reset()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    encoded = "data:image/jpeg;base64,$b64"
                    if (encoded.length <= 48_000 || quality <= 38) break
                    quality -= 10
                }
            } finally {
                scaled.recycle()
            }
            encoded
        }
    }

private data class PresetVisual(
    val icon: ImageVector,
    val startColor: Color,
    val endColor: Color,
    val tint: Color
)

private fun visualForPreset(key: String): PresetVisual = when (key) {
    AvatarPresets.WOLF -> PresetVisual(
        icon = Icons.Default.Pets,
        startColor = Color(0xFF2B2112),
        endColor = Color(0xFF14110B),
        tint = TacticalAmber
    )
    AvatarPresets.SNIPER -> PresetVisual(
        icon = Icons.Default.GpsFixed,
        startColor = Color(0xFF0E2934),
        endColor = Color(0xFF09141E),
        tint = IceCyan
    )
    AvatarPresets.SHIELD -> PresetVisual(
        icon = Icons.Default.Security,
        startColor = Color(0xFF0D2B22),
        endColor = Color(0xFF081414),
        tint = RtxEmerald
    )
    AvatarPresets.SKULL -> PresetVisual(
        icon = Icons.Default.Warning,
        startColor = Color(0xFF331419),
        endColor = Color(0xFF160A0E),
        tint = ThreatCrimson
    )
    AvatarPresets.EAGLE -> PresetVisual(
        icon = Icons.Default.Bolt,
        startColor = Color(0xFF2D2510),
        endColor = Color(0xFF12100A),
        tint = Color(0xFFFFD56B)
    )
    AvatarPresets.PHOENIX -> PresetVisual(
        icon = Icons.Default.LocalFireDepartment,
        startColor = Color(0xFF361A10),
        endColor = Color(0xFF180C0A),
        tint = Color(0xFFFF7847)
    )
    AvatarPresets.CYBER -> PresetVisual(
        icon = Icons.Default.Memory,
        startColor = Color(0xFF12233B),
        endColor = Color(0xFF09111F),
        tint = Color(0xFF6CF3FF)
    )
    else -> PresetVisual( // GHOST default
        icon = Icons.Default.Visibility,
        startColor = Color(0xFF102A28),
        endColor = Color(0xFF081219),
        tint = RtxEmerald
    )
}

/**
 * Reusable circular player avatar supporting both gallery JPEG photos and built-in tactical emblems.
 */
@Composable
fun PlayerAvatar(
    avatarData: String,
    size: Dp,
    borderColor: Color = RtxEmerald,
    borderWidth: Dp = 1.5.dp,
    modifier: Modifier = Modifier
) {
    val photoBitmap = remember(avatarData) { decodeCachedAvatarBitmap(avatarData) }
    val presetVisual = remember(avatarData) { visualForPreset(avatarData) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(presetVisual.startColor, presetVisual.endColor)
                )
            )
            .border(borderWidth, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap,
                contentDescription = "Аватар игрока",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = presetVisual.icon,
                contentDescription = "Тактический аватар",
                tint = presetVisual.tint,
                modifier = Modifier.size(size * 0.56f)
            )
        }
    }
}
