package com.example.flashdepth.processor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

object DepthProcessor {

    private const val MAX_DIM = 320  // ストリーミング処理向けに解像度を抑える

    fun process(ambient: Bitmap, torch: Bitmap): Bitmap {
        val scale = minOf(MAX_DIM.toFloat() / ambient.width, MAX_DIM.toFloat() / ambient.height, 1f)
        val w = (ambient.width * scale).toInt().coerceAtLeast(1)
        val h = (ambient.height * scale).toInt().coerceAtLeast(1)

        val ambPx = IntArray(w * h).also {
            Bitmap.createScaledBitmap(ambient, w, h, true).getPixels(it, 0, w, 0, 0, w, h)
        }
        val torchPx = IntArray(w * h).also {
            Bitmap.createScaledBitmap(torch, w, h, true).getPixels(it, 0, w, 0, 0, w, h)
        }

        val rawDepth = FloatArray(w * h)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE

        for (i in ambPx.indices) {
            val aR = Color.red(ambPx[i]) / 255f
            val aG = Color.green(ambPx[i]) / 255f
            val aB = Color.blue(ambPx[i]) / 255f

            val tR = Color.red(torchPx[i]) / 255f
            val tG = Color.green(torchPx[i]) / 255f
            val tB = Color.blue(torchPx[i]) / 255f

            // チャンネルごとのトーチ寄与量
            val dR = maxOf(tR - aR, 0f)
            val dG = maxOf(tG - aG, 0f)
            val dB = maxOf(tB - aB, 0f)
            val flashLum = 0.299f * dR + 0.587f * dG + 0.114f * dB

            // アルベド推定: 環境光下の最大チャンネル値を表面反射率の代理指標として使用
            // 白い面は全チャンネルで高反射、暗色面は反射率が低いため補正が必要
            val albedo = maxOf(aR, aG, aB, 0.05f)

            // 逆二乗則に反射率補正を適用: I_torch ∝ albedo / depth²
            // → depth ∝ sqrt(albedo / flashLum)
            val corrected = maxOf(flashLum / albedo, 1e-4f)
            val d = 1f / sqrt(corrected)

            rawDepth[i] = d
            if (d < lo) lo = d
            if (d > hi) hi = d
        }

        val smoothed = boxBlur(rawDepth, w, h, radius = 2)
        val range = (hi - lo).coerceAtLeast(1e-6f)
        val outPx = IntArray(w * h) { i -> jetColor((smoothed[i] - lo) / range) }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPx, 0, w, 0, 0, w, h)
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        sum += src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                        count++
                    }
                }
                dst[y * w + x] = sum / count
            }
        }
        return dst
    }

    // Jetカラーマップ: t=0(近い)→青, t=1(遠い)→赤
    private fun jetColor(t: Float): Int {
        val r: Float; val g: Float; val b: Float
        when {
            t < 0.25f -> { r = 0f;                    g = t * 4f;                b = 1f }
            t < 0.5f  -> { r = 0f;                    g = 1f;                    b = 1f - (t - 0.25f) * 4f }
            t < 0.75f -> { r = (t - 0.5f) * 4f;       g = 1f;                    b = 0f }
            else      -> { r = 1f;                    g = 1f - (t - 0.75f) * 4f; b = 0f }
        }
        return Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    }
}
