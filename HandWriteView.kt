package com.orange.inputmethod

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class HandWriteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var strokeColor: Int = 0xFF000000.toInt()
    var strokeWidth: Float = 6f
    var recognizeDelayMs: Long = 800L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var recognizeRunnable: Runnable? = null

    var onRecognizeResult: ((String) -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        updatePaint()
    }

    fun updatePaint() {
        paint.color = strokeColor
        paint.strokeWidth = strokeWidth
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(x, y)
                lastX = x
                lastY = y
                cancelRecognizeTask()
            }
            MotionEvent.ACTION_MOVE -> {
                path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                lastX = x
                lastY = y
                invalidate()
                cancelRecognizeTask()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                postRecognizeTask()
            }
        }
        return true
    }

    private fun postRecognizeTask() {
        recognizeRunnable = Runnable {
            // 占位，后续接入MLKit墨水识别替换此处
            val mockResult = ""
            onRecognizeResult?.invoke(mockResult)
            clearBoard()
        }
        handler.postDelayed(recognizeRunnable!!, recognizeDelayMs)
    }

    private fun cancelRecognizeTask() {
        recognizeRunnable?.let { handler.removeCallbacks(it) }
    }

    fun clearBoard() {
        path.reset()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelRecognizeTask()
    }
}
