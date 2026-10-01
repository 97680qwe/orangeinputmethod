package com.orange.inputmethod

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orange.inputmethod.clipboard.ClipAdapter
import com.orange.inputmethod.clipboard.ClipboardRepo
import com.orange.inputmethod.config.ConfigRepo
import com.orange.inputmethod.config.ImeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

//网格数据实体
data class GridItem(
    val iconText:String,
    val name:String,
    val tag:String
)

class ImeMainKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var imeService: OrangeIME? = null
    private var configRepo: ConfigRepo? = null
    private var clipRepo: ClipboardRepo? = null
    private var currentConfig: ImeConfig = ImeConfig()
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private var clipAdapter: ClipAdapter? = null
    private var tvClipStatus: TextView? = null
    private var rvClipList: RecyclerView? = null

    //手写
    private var handWritePanel: View? = null
    private var handWriteView: HandWriteView? = null

    //弹窗
    private var popKeyboardSel:PopupWindow?=null
    private var popSettingSel:PopupWindow?=null

    // Shift 状态: 0=小写, 1=临时大写(输一个字自动恢复), 2=锁定大写
    private var shiftState = 0
    // 记录进入符号面板前来自哪个面板
    private var previousPanelBeforeSymbol = "normal"

    // 字母键 ID 与对应小写字符
    private val letterKeyIds = listOf(
        R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
        R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
        R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
        R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
        R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v, R.id.key_b,
        R.id.key_n, R.id.key_m
    )
    private val letterKeyChars = listOf(
        "q","w","e","r","t","y","u","i","o","p",
        "a","s","d","f","g","h","j","k","l",
        "z","x","c","v","b","n","m"
    )

    override fun onFinishInflate() {
        super.onFinishInflate()
        adjustKeyboardHeight()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        adjustKeyboardHeight()
    }

    private fun adjustKeyboardHeight() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val screenHeight = resources.displayMetrics.heightPixels
        val lp = layoutParams ?: return
        val targetHeight = if (isLandscape) (screenHeight * 2 / 3) else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height != targetHeight) {
            lp.height = targetHeight
            layoutParams = lp
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun getValidInputConnection(): InputConnection? {
        val service = imeService ?: return null
        val ic = service.getCurrentInputConnection()
        ic?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE)
        return ic
    }

    private fun pressChar(rawText: String) {
        var out = rawText
        if (rawText.length == 1 && rawText[0].isLetter()) {
            when (shiftState) {
                1 -> {
                    out = rawText.uppercase()
                    shiftState = 0
                    updateShiftUi()
                }
                2 -> out = rawText.uppercase()
            }
        }
        val ic = getValidInputConnection() ?: return
        ic.commitText(out, 1)
    }

    private fun doDelete() {
        val ic = getValidInputConnection() ?: return
        ic.deleteSurroundingText(1, 0)
    }

    fun attachService(service: OrangeIME) {
        imeService = service
        configRepo = ConfigRepo(context)
        clipRepo = ClipboardRepo(context)

        val topBarContainer = findViewById<LinearLayout>(R.id.top_bar_container)
        val qwertyPanel = findViewById<View>(R.id.qwertyPanel)
        val numberPanel = findViewById<View>(R.id.numberPanel)
        val clipPanel = findViewById<View>(R.id.clipPanel)
        val symbolPanel = findViewById<View>(R.id.symbolPanel)
        handWritePanel = findViewById(R.id.handWritePanel)

        qwertyPanel?.let { initQwertyKeyEvent(it) }
        numberPanel?.let { initNumberKeyEvent(it) }
        clipPanel?.let { initClipPanel(it) }
        symbolPanel?.let { initSymbolKeyEvent(it) }
        handWritePanel?.let { initHandWritePanel(it) }

        topBarContainer?.findViewById<Button>(R.id.btn_tool_main)?.setOnClickListener { showSettingFullPopup(it) }
        topBarContainer?.findViewById<Button>(R.id.btn_switch_keyboard_pop)?.setOnClickListener { showKeyboardFullPopup(it) }
        topBarContainer?.findViewById<Button>(R.id.btn_top_clip)?.setOnClickListener { switchInnerPanel("clip") }

        findViewById<View>(R.id.qwertyPanel)?.visibility = View.VISIBLE
        findViewById<View>(R.id.numberPanel)?.visibility = View.GONE
        findViewById<View>(R.id.clipPanel)?.visibility = View.GONE
        findViewById<View>(R.id.symbolPanel)?.visibility = View.GONE
        findViewById<View>(R.id.handWritePanel)?.visibility = View.GONE

        shiftState = 0
        updateShiftUi()

        uiScope.launch {
            currentConfig = configRepo!!.getConfig()
            withContext(Dispatchers.Main) {
                applyThemeAll()
            }
        }
    }

    //键盘选择全屏弹窗
    private fun showKeyboardFullPopup(anchor:View){
        if(popKeyboardSel?.isShowing==true) return
        val popView = LayoutInflater.from(context).inflate(R.layout.pop_full_keyboard_sel,null)
        val gv = popView.findViewById<GridView>(R.id.gv_keyboard_list)
        val btnClose = popView.findViewById<Button>(R.id.btn_pop_close)
        val items = listOf(
            GridItem("⌨","26英文键盘","normal"),
            GridItem("🔢","数字键盘","number"),
            GridItem("✍","手写键盘","handwrite")
        )
        gv.adapter = object:BaseAdapter(){
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v:View = convertView ?: LayoutInflater.from(context).inflate(R.layout.grid_item_func,parent,false)
                val ivIcon = v.findViewById<TextView>(R.id.tv_icon)
                val tvName = v.findViewById<TextView>(R.id.tv_name)
                val d = items[position]
                ivIcon.text = d.iconText
                tvName.text = d.name
                v.setOnClickListener {
                    popKeyboardSel?.dismiss()
                    switchInnerPanel(d.tag)
                }
                return v
            }
        }
        btnClose.setOnClickListener { popKeyboardSel?.dismiss() }
        popKeyboardSel = PopupWindow(popView, ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,true)
        popKeyboardSel?.showAtLocation(anchor, Gravity.NO_GRAVITY,0,0)
    }

    //设置快捷弹窗（夜间模式）
    private fun showSettingFullPopup(anchor:View){
        if(popSettingSel?.isShowing==true) return
        val popView = LayoutInflater.from(context).inflate(R.layout.pop_full_setting_sel,null)
        val gv = popView.findViewById<GridView>(R.id.gv_setting_list)
        val btnClose = popView.findViewById<Button>(R.id.btn_setting_pop_close)
        val items = listOf(
            GridItem("🌙","夜间模式","night")
        )
        gv.adapter = object:BaseAdapter(){
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v:View = convertView ?: LayoutInflater.from(context).inflate(R.layout.grid_item_func,parent,false)
                val ivIcon = v.findViewById<TextView>(R.id.tv_icon)
                val tvName = v.findViewById<TextView>(R.id.tv_name)
                val d = items[position]
                ivIcon.text = d.iconText
                tvName.text = d.name
                v.setOnClickListener {
                    popSettingSel?.dismiss()
                    toggleNightMode()
                }
                return v
            }
        }
        btnClose.setOnClickListener { popSettingSel?.dismiss() }
        popSettingSel = PopupWindow(popView, ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,true)
        popSettingSel?.showAtLocation(anchor, Gravity.NO_GRAVITY,0,0)
    }

    private fun initHandWritePanel(root: View) {
        handWriteView = root.findViewById(R.id.handWriteView)
        val btnClear = root.findViewById<Button>(R.id.btn_handwrite_clear)
        val btnBack = root.findViewById<Button>(R.id.btn_handwrite_back)
        //修复BUG：每次打开手写面板读取最新config
        handWriteView?.strokeColor = currentConfig.handWriteColor
        handWriteView?.strokeWidth = currentConfig.handWriteStrokeWidth
        handWriteView?.recognizeDelayMs = currentConfig.handWriteRecognizeDelayMs
        handWriteView?.updatePaint()

        handWriteView?.onRecognizeResult = { text ->
            if (text.isNotEmpty()) {
                val ic = getValidInputConnection()
                ic?.commitText(text, 1)
            }
        }

        btnClear.setOnClickListener { handWriteView?.clearBoard() }
        btnBack.setOnClickListener { switchInnerPanel("normal") }
    }

    private fun isFuncKey(id: Int): Boolean {
        return id in setOf(
            R.id.key_backspace, R.id.key_backspace_num,
            R.id.key_switch_num, R.id.key_switch_abc, R.id.key_switch_symbol,
            R.id.key_switch_symbol_qwerty,
            R.id.key_clip_num,
            R.id.key_shift,
            R.id.btn_symbol_back,
            R.id.btn_clear_all_clip, R.id.btn_back_keyboard,
            R.id.btn_tool_main, R.id.btn_switch_keyboard_pop, R.id.btn_top_clip,
            R.id.btn_night_mode
        )
    }

    private fun isAccentKey(id: Int): Boolean {
        return id == R.id.key_enter || id == R.id.key_enter_num
    }

    private fun applyThemeAll() {
        val isNight = currentConfig.nightMode
        setBackgroundResource(if (isNight) R.drawable.bg_keyboard_night else R.drawable.bg_keyboard)
        val topBarContainer = findViewById<LinearLayout>(R.id.top_bar_container)
        topBarContainer?.setBackgroundColor(
            if (isNight) 0xFF2C2C2E.toInt() else 0xFFE3E5E8.toInt()
        )
        fun traverseViews(v: View) {
            if (v is Button) {
                when {
                    isAccentKey(v.id) -> {
                        v.setBackgroundResource(
                            if (isNight) R.drawable.key_accent_bg_night else R.drawable.key_accent_bg
                        )
                        v.setTextColor(0xFFFFFFFF.toInt())
                    }
                    isFuncKey(v.id) -> {
                        v.setBackgroundResource(
                            if (isNight) R.drawable.key_func_bg_night else R.drawable.key_func_bg
                        )
                        v.setTextColor(
                            if (isNight) 0xFFAEAEB2.toInt() else 0xFF505560.toInt()
                        )
                    }
                    else -> {
                        v.setBackgroundResource(
                            if (isNight) R.drawable.key_bg_night else R.drawable.key_bg
                        )
                        v.setTextColor(
                            if (isNight) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                        )
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverseViews(v.getChildAt(i))
                }
            }
        }
        findViewById<View>(R.id.qwertyPanel)?.let { traverseViews(it) }
        findViewById<View>(R.id.numberPanel)?.let { traverseViews(it) }
        findViewById<View>(R.id.symbolPanel)?.let { traverseViews(it) }
        topBarContainer?.let { traverseViews(it) }
        findViewById<View>(R.id.clipPanel)?.let { traverseViews(it) }
    }

    private fun toggleNightMode() {
        uiScope.launch {
            val newCfg = currentConfig.copy(nightMode = !currentConfig.nightMode)
            configRepo!!.saveConfig(newCfg)
            currentConfig = newCfg
            withContext(Dispatchers.Main) {
                applyThemeAll()
            }
        }
    }

    private fun updateShiftUi() {
        val qPanel = findViewById<View>(R.id.qwertyPanel) ?: return
        val shiftBtn = qPanel.findViewById<Button>(R.id.key_shift)
        when (shiftState) {
            0 -> shiftBtn.text = "↑"
            1 -> shiftBtn.text = "⇧"
            2 -> shiftBtn.text = "⇪"
        }
        for (i in letterKeyIds.indices) {
            val btn = qPanel.findViewById<Button>(letterKeyIds[i]) ?: continue
            btn.text = if (shiftState > 0) letterKeyChars[i].uppercase() else letterKeyChars[i]
        }
    }

    private fun initQwertyKeyEvent(root: View) {
        val shiftBtn = root.findViewById<Button>(R.id.key_shift)
        shiftBtn.setOnClickListener {
            shiftState = when (shiftState) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            updateShiftUi()
        }
        root.findViewById<Button>(R.id.key_q).setOnClickListener { pressChar("q") }
        root.findViewById<Button>(R.id.key_w).setOnClickListener { pressChar("w") }
        root.findViewById<Button>(R.id.key_e).setOnClickListener { pressChar("e") }
        root.findViewById<Button>(R.id.key_r).setOnClickListener { pressChar("r") }
        root.findViewById<Button>(R.id.key_t).setOnClickListener { pressChar("t") }
        root.findViewById<Button>(R.id.key_y).setOnClickListener { pressChar("y") }
        root.findViewById<Button>(R.id.key_u).setOnClickListener { pressChar("u") }
        root.findViewById<Button>(R.id.key_i).setOnClickListener { pressChar("i") }
        root.findViewById<Button>(R.id.key_o).setOnClickListener { pressChar("o") }
        root.findViewById<Button>(R.id.key_p).setOnClickListener { pressChar("p") }
        root.findViewById<Button>(R.id.key_a).setOnClickListener { pressChar("a") }
        root.findViewById<Button>(R.id.key_s).setOnClickListener { pressChar("s") }
        root.findViewById<Button>(R.id.key_d).setOnClickListener { pressChar("d") }
        root.findViewById<Button>(R.id.key_f).setOnClickListener { pressChar("f") }
        root.findViewById<Button>(R.id.key_g).setOnClickListener { pressChar("g") }
        root.findViewById<Button>(R.id.key_h).setOnClickListener { pressChar("h") }
        root.findViewById<Button>(R.id.key_j).setOnClickListener { pressChar("j") }
        root.findViewById<Button>(R.id.key_k).setOnClickListener { pressChar("k") }
        root.findViewById<Button>(R.id.key_l).setOnClickListener { pressChar("l") }
        root.findViewById<Button>(R.id.key_z).setOnClickListener { pressChar("z") }
        root.findViewById<Button>(R.id.key_x).setOnClickListener { pressChar("x") }
        root.findViewById<Button>(R.id.key_c).setOnClickListener { pressChar("c") }
        root.findViewById<Button>(R.id.key_v).setOnClickListener { pressChar("v") }
        root.findViewById<Button>(R.id.key_b).setOnClickListener { pressChar("b") }
        root.findViewById<Button>(R.id.key_n).setOnClickListener { pressChar("n") }
        root.findViewById<Button>(R.id.key_m).setOnClickListener { pressChar("m") }
        root.findViewById<Button>(R.id.key_space).setOnClickListener { pressChar(" ") }
        val backspaceQwerty = root.findViewById<Button>(R.id.key_backspace)
        backspaceQwerty.setOnClickListener { doDelete() }
        backspaceQwerty.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                stopRepeatDelete()
            }
            false
        }
        backspaceQwerty.setOnLongClickListener {
            startRepeatDelete()
            true
        }
        root.findViewById<Button>(R.id.key_enter).setOnClickListener { pressChar("\n") }
        root.findViewById<Button>(R.id.key_switch_symbol_qwerty)?.setOnClickListener {
            previousPanelBeforeSymbol = "normal"
            switchInnerPanel("symbol")
        }
        root.findViewById<Button>(R.id.key_switch_num).setOnClickListener { switchInnerPanel("number") }
    }

    private fun initNumberKeyEvent(root: View) {
        root.findViewById<Button>(R.id.key_1).setOnClickListener { pressChar("1") }
        root.findViewById<Button>(R.id.key_2).setOnClickListener { pressChar("2") }
        root.findViewById<Button>(R.id.key_3).setOnClickListener { pressChar("3") }
        root.findViewById<Button>(R.id.key_4).setOnClickListener { pressChar("4") }
        root.findViewById<Button>(R.id.key_5).setOnClickListener { pressChar("5") }
        root.findViewById<Button>(R.id.key_6).setOnClickListener { pressChar("6") }
        root.findViewById<Button>(R.id.key_7).setOnClickListener { pressChar("7") }
        root.findViewById<Button>(R.id.key_8).setOnClickListener { pressChar("8") }
        root.findViewById<Button>(R.id.key_9).setOnClickListener { pressChar("9") }
        root.findViewById<Button>(R.id.key_0).setOnClickListener { pressChar("0") }
        root.findViewById<Button>(R.id.key_comma).setOnClickListener { pressChar(",") }
        root.findViewById<Button>(R.id.key_dot).setOnClickListener { pressChar(".") }
        root.findViewById<Button>(R.id.num_sym_excl)?.setOnClickListener { pressChar("!") }
        root.findViewById<Button>(R.id.num_sym_at)?.setOnClickListener { pressChar("@") }
        root.findViewById<Button>(R.id.num_sym_hash)?.setOnClickListener { pressChar("#") }
        root.findViewById<Button>(R.id.num_sym_dollar)?.setOnClickListener { pressChar("$") }
        root.findViewById<Button>(R.id.num_sym_percent)?.setOnClickListener { pressChar("%") }
        root.findViewById<Button>(R.id.num_sym_amp)?.setOnClickListener { pressChar("&") }
        root.findViewById<Button>(R.id.num_sym_lparen)?.setOnClickListener { pressChar("(") }
        root.findViewById<Button>(R.id.num_sym_rparen)?.setOnClickListener { pressChar(")") }
        root.findViewById<Button>(R.id.num_sym_minus)?.setOnClickListener { pressChar("-") }
        root.findViewById<Button>(R.id.num_sym_plus)?.setOnClickListener { pressChar("+") }
        root.findViewById<Button>(R.id.key_space_num).setOnClickListener { pressChar(" ") }
        val backspaceNum = root.findViewById<Button>(R.id.key_backspace_num)
        backspaceNum.setOnClickListener { doDelete() }
        backspaceNum.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                stopRepeatDelete()
            }
            false
        }
        backspaceNum.setOnLongClickListener {
            startRepeatDelete()
            true
        }
        root.findViewById<Button>(R.id.key_enter_num).setOnClickListener { pressChar("\n") }
        root.findViewById<Button>(R.id.key_switch_abc).setOnClickListener { switchInnerPanel("normal") }
        root.findViewById<Button>(R.id.key_clip_num).setOnClickListener { switchInnerPanel("clip") }
        root.findViewById<Button>(R.id.key_switch_symbol)?.setOnClickListener {
            previousPanelBeforeSymbol = "number"
            switchInnerPanel("symbol")
        }
    }

    private fun initSymbolKeyEvent(root: View) {
        root.findViewById<Button>(R.id.sym_excl)?.setOnClickListener { pressChar("!") }
        root.findViewById<Button>(R.id.sym_at)?.setOnClickListener { pressChar("@") }
        root.findViewById<Button>(R.id.sym_hash)?.setOnClickListener { pressChar("#") }
        root.findViewById<Button>(R.id.sym_dollar)?.setOnClickListener { pressChar("$") }
        root.findViewById<Button>(R.id.sym_percent)?.setOnClickListener { pressChar("%") }
        root.findViewById<Button>(R.id.sym_caret)?.setOnClickListener { pressChar("^") }
        root.findViewById<Button>(R.id.sym_amp)?.setOnClickListener { pressChar("&") }
        root.findViewById<Button>(R.id.sym_star)?.setOnClickListener { pressChar("*") }
        root.findViewById<Button>(R.id.sym_lparen)?.setOnClickListener { pressChar("(") }
        root.findViewById<Button>(R.id.sym_rparen)?.setOnClickListener { pressChar(")") }
        root.findViewById<Button>(R.id.sym_minus)?.setOnClickListener { pressChar("-") }
        root.findViewById<Button>(R.id.sym_plus)?.setOnClickListener { pressChar("+") }
        root.findViewById<Button>(R.id.sym_lbracket)?.setOnClickListener { pressChar("[") }
        root.findViewById<Button>(R.id.sym_rbracket)?.setOnClickListener { pressChar("]") }
        root.findViewById<Button>(R.id.sym_lbrace)?.setOnClickListener { pressChar("{") }
        root.findViewById<Button>(R.id.sym_rbrace)?.setOnClickListener { pressChar("}") }
        root.findViewById<Button>(R.id.sym_slash)?.setOnClickListener { pressChar("/") }
        root.findViewById<Button>(R.id.sym_backslash)?.setOnClickListener { pressChar("\\") }
        root.findViewById<Button>(R.id.sym_less)?.setOnClickListener { pressChar("<") }
        root.findViewById<Button>(R.id.sym_greater)?.setOnClickListener { pressChar(">") }
        root.findViewById<Button>(R.id.sym_question)?.setOnClickListener { pressChar("?") }
        root.findViewById<Button>(R.id.sym_quote)?.setOnClickListener { pressChar("\"") }
        root.findViewById<Button>(R.id.sym_singlequote)?.setOnClickListener { pressChar("'") }
        root.findViewById<Button>(R.id.sym_semicolon)?.setOnClickListener { pressChar(";") }
        root.findViewById<Button>(R.id.sym_colon)?.setOnClickListener { pressChar(":") }
        root.findViewById<Button>(R.id.btn_symbol_back)?.setOnClickListener {
            switchInnerPanel(previousPanelBeforeSymbol)
        }
    }

    private fun startRepeatDelete() {
        stopRepeatDelete()
        deleteRunnable = object : Runnable {
            override fun run() {
                doDelete()
                handler.postDelayed(this, 80)
            }
        }
        handler.postDelayed(deleteRunnable!!, 300)
    }

    private fun stopRepeatDelete() {
        deleteRunnable?.let { handler.removeCallbacks(it) }
        deleteRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeatDelete()
        popKeyboardSel?.dismiss()
        popSettingSel?.dismiss()
    }

    private fun initClipPanel(root: View) {
        tvClipStatus = root.findViewById(R.id.tv_clip_status)
        rvClipList = root.findViewById(R.id.rv_clip_list)
        val btnClearAll = root.findViewById<Button>(R.id.btn_clear_all_clip)
        val btnBack = root.findViewById<Button>(R.id.btn_back_keyboard)
        clipAdapter = ClipAdapter(
            onItemClick = { item ->
                val conn = getValidInputConnection()
                conn?.commitText(item.text, 1)
                switchInnerPanel("normal")
            },
            onDeleteClick = { item ->
                uiScope.launch {
                    var list = clipRepo!!.getAllItems().toMutableList()
                    list.removeAll { it.timestamp == item.timestamp }
                    clipRepo!!.saveList(list)
                    refreshClipUi()
                }
            }
        )
        rvClipList?.adapter = clipAdapter
        rvClipList?.layoutManager = LinearLayoutManager(context)
        btnBack.setOnClickListener { switchInnerPanel("normal") }
        btnClearAll.setOnClickListener {
            uiScope.launch {
                clipRepo!!.clearAll()
                refreshClipUi()
            }
        }
        refreshClipUi()
    }

        /** 切换内部面板 normal/number/symbol/clip/handwrite */
    private fun switchInnerPanel(panelTag: String) {
        //全部隐藏
        findViewById<View>(R.id.qwertyPanel)?.visibility = View.GONE
        findViewById<View>(R.id.numberPanel)?.visibility = View.GONE
        findViewById<View>(R.id.clipPanel)?.visibility = View.GONE
        findViewById<View>(R.id.symbolPanel)?.visibility = View.GONE
        findViewById<View>(R.id.handWritePanel)?.visibility = View.GONE

        when(panelTag){
            "normal" -> findViewById<View>(R.id.qwertyPanel)?.visibility = View.VISIBLE
            "number" -> findViewById<View>(R.id.numberPanel)?.visibility = View.VISIBLE
            "clip" -> {
                findViewById<View>(R.id.clipPanel)?.visibility = View.VISIBLE
                refreshClipUi()
            }
            "symbol" -> findViewById<View>(R.id.symbolPanel)?.visibility = View.VISIBLE
            "handwrite" -> {
                findViewById<View>(R.id.handWritePanel)?.visibility = View.VISIBLE
                //每次打开手写重新读取配置
                handWriteView?.strokeColor = currentConfig.handWriteColor
                handWriteView?.strokeWidth = currentConfig.handWriteStrokeWidth
                handWriteView?.recognizeDelayMs = currentConfig.handWriteRecognizeDelayMs
                handWriteView?.updatePaint()
            }
        }
    }

    private fun refreshClipUi() {
        uiScope.launch {
            val cfg = configRepo!!.getConfig()
            val list = withContext(Dispatchers.IO) { clipRepo!!.getPage(0, 30) }
            val allCount = withContext(Dispatchers.IO) { clipRepo!!.getAllItems().size }
            tvClipStatus?.text = "剪贴板：当前 ${allCount} / 上限 ${cfg.clipMaxCount} | 单条最大${cfg.clipSingleMaxLength}字"
            clipAdapter?.submitList(list)
        }
    }

    fun release(){
        stopRepeatDelete()
        popKeyboardSel?.dismiss()
        popSettingSel?.dismiss()
        uiScope.cancel()
    }
}
