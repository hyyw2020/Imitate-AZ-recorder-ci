package com.monkeycode.screenrecorder

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class FloatingView(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var floatingView: View? = null
    private var isPaused = false
    private var btnPause: Button? = null
    private var indicator: ImageView? = null
    private var tvDuration: TextView? = null

    private var onStopListener: (() -> Unit)? = null
    private var onPauseListener: (() -> Unit)? = null
    private var onResumeListener: (() -> Unit)? = null

    fun setOnStopListener(listener: () -> Unit) { onStopListener = listener }
    fun setOnPauseListener(listener: () -> Unit) { onPauseListener = listener }
    fun setOnResumeListener(listener: () -> Unit) { onResumeListener = listener }

    fun show() {
        if (floatingView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        val view = LayoutInflater.from(context).inflate(R.layout.floating_view, null)
        btnPause = view.findViewById(R.id.btnPause)
        indicator = view.findViewById(R.id.ivIndicator)
        tvDuration = view.findViewById(R.id.tvDuration)
        val btnStop = view.findViewById<Button>(R.id.btnStop)

        btnPause?.setOnClickListener {
            isPaused = !isPaused
            btnPause?.text = if (isPaused) context.getString(R.string.btn_resume) else context.getString(R.string.btn_pause)
            indicator?.visibility = if (isPaused) View.INVISIBLE else View.VISIBLE
            if (isPaused) onPauseListener?.invoke() else onResumeListener?.invoke()
        }

        btnStop.setOnClickListener {
            onStopListener?.invoke()
        }

        view.setOnTouchListener(FloatingTouchListener(params, windowManager))

        windowManager.addView(view, params)
        floatingView = view
    }

    fun hide() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            floatingView = null
        }
    }

    fun updateDuration(text: String) {
        tvDuration?.text = text
    }

    fun updatePauseState(paused: Boolean) {
        isPaused = paused
        btnPause?.text = if (paused) context.getString(R.string.btn_resume) else context.getString(R.string.btn_pause)
        indicator?.visibility = if (paused) View.INVISIBLE else View.VISIBLE
    }

    private inner class FloatingTouchListener(
        private val params: WindowManager.LayoutParams,
        private val windowManager: WindowManager
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private val clickThreshold = 10

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > clickThreshold || Math.abs(dy) > clickThreshold) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (_: Exception) {}
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx <= clickThreshold && dy <= clickThreshold) {
                        return false
                    }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        @Volatile
        private var instance: FloatingView? = null

        fun start(context: Context) {
            val fv = FloatingView(context)
            instance = fv
            fv.show()
        }

        fun hide(context: Context) {
            instance?.hide()
            instance = null
        }
    }
}
