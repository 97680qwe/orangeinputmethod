package com.orange.inputmethod

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orange.inputmethod.config.ConfigRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImeSettingsActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepo
    private lateinit var etColor: EditText
    private lateinit var etWidth: EditText
    private lateinit var etDelay: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configRepo = ConfigRepo(this)

        etColor = findViewById(R.id.et_hand_color)
        etWidth = findViewById(R.id.et_hand_width)
        etDelay = findViewById(R.id.et_hand_delay)
        btnSave = findViewById(R.id.btn_save_setting)

        CoroutineScope(Dispatchers.IO).launch {
            val cfg = configRepo.getConfig()
            withContext(Dispatchers.Main) {
                etColor.setText("${cfg.handWriteColor}")
                etWidth.setText("${cfg.handWriteStrokeWidth}")
                etDelay.setText("${cfg.handWriteRecognizeDelayMs}")
            }
        }

        btnSave.setOnClickListener { saveConfig() }
    }

    private fun saveConfig() {
        CoroutineScope(Dispatchers.IO).launch {
            val oldCfg = configRepo.getConfig()
            val colorStr = etColor.text.toString()
            val widthStr = etWidth.text.toString()
            val delayStr = etDelay.text.toString()

            val newColor = try {
                colorStr.toInt()
            } catch (_: Exception) {
                oldCfg.handWriteColor
            }
            val newWidth = try {
                widthStr.toFloat()
            } catch (_: Exception) {
                oldCfg.handWriteStrokeWidth
            }
            val newDelay = try {
                delayStr.toLong()
            } catch (_: Exception) {
                oldCfg.handWriteRecognizeDelayMs
            }

            val newCfg = oldCfg.copy(
                handWriteColor = newColor,
                handWriteStrokeWidth = newWidth,
                handWriteRecognizeDelayMs = newDelay
            )
            configRepo.saveConfig(newCfg)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ImeSettingsActivity, "设置已保存，切换到手写面板立即生效", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
