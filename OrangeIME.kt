package com.orange.inputmethod

import android.inputmethodservice.InputMethodService
import android.view.View
import com.orange.inputmethod.clipboard.ClipboardManagerHelper

class OrangeIME : InputMethodService() {
    private var keyboardView: ImeMainKeyboardView? = null
    private lateinit var clipboardHelper: ClipboardManagerHelper

    override fun onCreate() {
        super.onCreate()
        // 启动剪贴板监听，监控系统剪贴板复制事件
        clipboardHelper = ClipboardManagerHelper(this)
        clipboardHelper.startMonitor()
    }

    override fun onCreateInputView(): View? {
        // 关键点：避免重复实例化；attachService绑定service实例
        keyboardView = layoutInflater.inflate(R.layout.ime_main_keyboard, null) as ImeMainKeyboardView
        keyboardView?.attachService(this)
        return keyboardView
    }

    override fun onDestroy() {
        keyboardView?.release()
        keyboardView = null
        super.onDestroy()
    }

    // 增加onFinishInput：不要置空keyboardView！很多人写错这里把视图释放，导致再次呼不出键盘
    override fun onFinishInput() {
        super.onFinishInput()
        // ⚠️这里千万不要写 keyboardView = null
    }
}
