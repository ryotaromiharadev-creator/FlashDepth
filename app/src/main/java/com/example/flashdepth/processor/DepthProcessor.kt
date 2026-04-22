package com.example.flashdepth.processor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

object DepthProcessor {

    // --- 設定定数 ---
    private const val MAX_DIM = 160          // 計算負荷と手ブレ耐性のバランス
    private const val EPS = 1e-5f            // ゼロ除算回避
    private const val MAX_DISTANCE = 10.0f   // 遠方の飽和値
    private const val FLASH_THRESHOLD = 0.015f // フラッシュが届いていないと判断する閾値

    fun process(ambient: Bitmap, torch: Bitmap): Bitmap {
        // 1. スケーリング計算
        val scale = minOf(MAX_DIM.toFloat() / ambient.width, MAX_DIM.toFloat() / ambient.height, 1f)
        val w = (ambient.width * scale).toInt().coerceAtLeast(1)
        val h = (ambient.height * scale).toInt().coerceAtLeast(1)

        // 2. 高速化のためピクセル配列を取得
        val ambPx = IntArray(w * h).also {
            Bitmap.createScaledBitmap(ambient, w, h, true).getPixels(it, 0, w, 0, 0, w, h)
        }
        val torchPx = IntArray(w * h).also {
            Bitmap.createScaledBitmap(torch, w, h, true).getPixels(it, 0, w, 0, 0, w, h)
        }

        val rawDepth = FloatArray(w * h)
        val guide = FloatArray(w * h)

        for (i in ambPx.indices) {
            // 輝度(Luminance)を 0.0..1.0 で抽出
            val al = ((Color.red(ambPx[i]) * 0.299f + Color.green(ambPx[i]) * 0.587f + Color.blue(ambPx[i]) * 0.114f) / 255f)
            val tl = ((Color.red(torchPx[i]) * 0.299f + Color.green(torchPx[i]) * 0.587f + Color.blue(torchPx[i]) * 0.114f) / 255f)

            guide[i] = al // 環境光をGuided Filterの構造ガイドにする

            // --- 核心：比率による深度推定 (Ambient Normalization) ---
            val diff = tl - al

            if (diff < FLASH_THRESHOLD) {
                // フラッシュが届かない暗がりや、レンズの死角（影）は最大距離とする
                rawDepth[i] = MAX_DISTANCE
            } else {
                // 物理モデル: d = 1 / sqrt((I_torch / I_ambient) - 1)
                // これにより、物体の色(反射率)の影響を分母分子で打ち消し、
                // 純粋にフラッシュの届き具合(距離)だけを抽出する
                val ratio = tl / (al + EPS)
                val d = 1.0f / sqrt((ratio - 1.0f).coerceAtLeast(EPS))
                rawDepth[i] = d.coerceIn(0f, MAX_DISTANCE)
            }
        }

        // 3. Guided Filter によるエッジ保持平滑化
        // これにより、低解像度の「面の情報」が、高解像度の「物体の形」にフィットする
        val filteredDepth = applyGuidedFilter(rawDepth, guide, w, h, radius = 5, reg = 0.05f)

        // 4. 可視化 (Jetカラーマップへの変換)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (d in filteredDepth) {
            if (d < lo) lo = d
            if (d > hi) hi = d
        }
        val range = (hi - lo).coerceAtLeast(EPS)
        val outPx = IntArray(w * h) { i -> jetColor((filteredDepth[i] - lo) / range) }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPx, 0, w, 0, 0, w, h)
        }
    }

    private fun applyGuidedFilter(src: FloatArray, guide: FloatArray, w: Int, h: Int, radius: Int, reg: Float): FloatArray {
        val meanI = boxBlur(guide, w, h, radius)
        val meanP = boxBlur(src, w, h, radius)
        val meanIP = boxBlur(FloatArray(src.size) { i -> guide[i] * src[i] }, w, h, radius)
        val meanII = boxBlur(FloatArray(guide.size) { i -> guide[i] * guide[i] }, w, h, radius)

        val covIP = FloatArray(src.size) { i -> meanIP[i] - meanI[i] * meanP[i] }
        val varI = FloatArray(guide.size) { i -> meanII[i] - meanI[i] * meanI[i] }

        val a = FloatArray(src.size) { i -> covIP[i] / (varI[i] + reg) }
        val b = FloatArray(src.size) { i -> meanP[i] - a[i] * meanI[i] }

        val meanA = boxBlur(a, w, h, radius)
        val meanB = boxBlur(b, w, h, radius)

        return FloatArray(src.size) { i -> meanA[i] * guide[i] + meanB[i] }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f; var c = 0
                for (dy in -r..r) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -r..r) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        s += src[ny * w + nx]
                        c++
                    }
                }
                dst[y * w + x] = s / c
            }
        }
        return dst
    }

    private fun jetColor(t: Float): Int {
        val r: Float; val g: Float; val b: Float
        val v = t.coerceIn(0f, 1f)
        when {
            v < 0.25f -> { r = 0f; g = v * 4f; b = 1f }
            v < 0.5f  -> { r = 0f; g = 1f; b = 1f - (v - 0.25f) * 4f }
            v < 0.75f -> { r = (v - 0.5f) * 4f; g = 1f; b = 0f }
            else      -> { r = 1f; g = 1f - (v - 0.75f) * 4f; b = 0f }
        }
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }
}