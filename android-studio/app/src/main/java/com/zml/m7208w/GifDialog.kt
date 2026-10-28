package com.zml.m7208w

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View

/// 原厂GIF引导弹窗: AlertDialog内嵌GifView逐帧循环播放res/raw动画, 点击动画或"知道了"关闭。
/// 纯展示组件, 不含任何业务逻辑(触发时机由调用方决定)。
object GifDialog {

    /** GIF显示宽度上限占屏比: 原厂动画按此等比缩放, 小屏缩小、不过分放大 */
    private const val MAX_WIDTH_RATIO = 0.8f

    /** 弹出GIF引导(循环播放, 点击关闭); Activity已退出或GIF解码失败时降级为纯文字弹窗 */
    fun show(activity: Activity, gifRes: Int, title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val gifView = GifView(activity, gifRes)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(gifView)
            .setPositiveButton("知道了", null)
            .create()
        gifView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 弹出GIF引导+双按钮(确认/取消); 用于需要用户操作后继续的引导(如双面翻纸后继续打背面) */
    fun showWithActions(
        activity: Activity, gifRes: Int, title: String, message: String,
        positiveText: String, onPositive: (android.content.DialogInterface, Int) -> Unit,
        negativeText: String? = null, onNegative: (android.content.DialogInterface, Int) -> Unit = { _, _ -> }
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val gifView = GifView(activity, gifRes)
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(gifView)
            .setPositiveButton(positiveText) { d, w -> onPositive(d, w) }
        if (negativeText != null) builder.setNegativeButton(negativeText) { d, w -> onNegative(d, w) }
        val dialog = builder.create()
        dialog.show()
    }

    /** GIF逐帧渲染视图: Movie按系统时钟取帧, 播到片尾自动回绕循环; 解码失败则为零尺寸隐藏 */
    @Suppress("DEPRECATION")   // Movie自API28标记弃用, 但各版本均可用且零三方依赖
    private class GifView(context: Context, gifRes: Int) : View(context) {

        private val movie: Movie? = runCatching {
            resources.openRawResource(gifRes).use { Movie.decodeStream(it) }
        }.getOrNull()
        private val startAtMs = SystemClock.uptimeMillis()
        private var drawScale = 1f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val m = movie
            if (m == null || m.width() <= 0 || m.height() <= 0) {
                setMeasuredDimension(0, 0); return
            }
            val maxWidthPx = (resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO).toInt()
            drawScale = minOf(1f, maxWidthPx.toFloat() / m.width())
            setMeasuredDimension((m.width() * drawScale).toInt(), (m.height() * drawScale).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val m = movie ?: return
            canvas.save()
            canvas.scale(drawScale, drawScale)
            m.setTime(playPositionMs(m.duration()))
            m.draw(canvas, 0f, 0f)
            canvas.restore()
            if (m.duration() > 0) postInvalidateOnAnimation()   // 有时长才驱动下一帧(循环)
        }

        /** 播放位置: 开机时钟对片长取模实现循环; 无时长(单帧GIF)固定第0帧 */
        private fun playPositionMs(durationMs: Int): Int =
            if (durationMs <= 0) 0
            else ((SystemClock.uptimeMillis() - startAtMs) % durationMs).toInt()
    }
}
