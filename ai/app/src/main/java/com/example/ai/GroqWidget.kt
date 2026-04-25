package com.example.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*

class GroqWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_KEY_CLICK = "com.example.ai.KEY_CLICK"
        private const val EXTRA_KEY = "extra_key"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        private val KOR_KEY_MAP = mapOf(
            "q" to "ㅂ", "w" to "ㅈ", "e" to "ㄷ", "r" to "ㄱ", "t" to "ㅅ", "y" to "ㅛ", "u" to "ㅕ", "i" to "ㅑ", "o" to "ㅐ", "p" to "ㅔ",
            "a" to "ㅁ", "s" to "ㄴ", "d" to "ㅇ", "f" to "ㄹ", "g" to "ㅎ", "h" to "ㅗ", "j" to "ㅓ", "k" to "ㅏ", "l" to "ㅣ",
            "z" to "ㅋ", "x" to "ㅌ", "c" to "ㅊ", "v" to "ㅍ", "b" to "ㅠ", "n" to "ㅜ", "m" to "ㅡ"
        )
        private val KOR_SHIFT_MAP = mapOf(
            "q" to "ㅃ", "w" to "ㅉ", "e" to "ㄸ", "r" to "ㄲ", "t" to "ㅆ", "o" to "ㅒ", "p" to "ㅖ"
        )

        private val SYMBOL_KEY_MAP = mapOf(
            "minus" to "-", "equal" to "=", "left_bracket" to "[", "right_bracket" to "]", "backslash" to "\\",
            "semicolon" to ";", "quote" to "'", "comma" to ",", "period" to ".", "slash" to "/"
        )

        private val SHIFT_MAP = mapOf(
            "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")",
            "minus" to "_", "equal" to "+", "left_bracket" to "{", "right_bracket" to "}", "backslash" to "|",
            "semicolon" to ":", "quote" to "\"", "comma" to "<", "period" to ">", "slash" to "?"
        )
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_KEY_CLICK) {
            val key = intent.getStringExtra(EXTRA_KEY) ?: return
            handleKeyClick(context, key)
        }
    }

    private fun handleKeyClick(context: Context, key: String) {
        val prefs = context.getSharedPreferences("groq_widget_prefs", Context.MODE_PRIVATE)
        var buffer = prefs.getString("buffer", "") ?: ""
        var isKorean = prefs.getBoolean("is_korean", false)
        var isShift = prefs.getBoolean("is_shift", false)
        var isCaps = prefs.getBoolean("is_caps", false)

        when (key) {
            "DEL" -> if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
            "SPACE" -> buffer += " "
            "TAB" -> buffer += "    "
            "ENTER" -> buffer += "\n"
            "LANG" -> {
                isKorean = !isKorean
                prefs.edit().putBoolean("is_korean", isKorean).apply()
            }
            "SHIFT" -> {
                isShift = !isShift
                prefs.edit().putBoolean("is_shift", isShift).apply()
            }
            "CAPS" -> {
                isCaps = !isCaps
                prefs.edit().putBoolean("is_caps", isCaps).apply()
            }
            "RESET" -> {
                prefs.edit().putString("buffer", "").putString("history", "").putString("context_history", "[]").apply()
                buffer = ""
            }
            "SEND" -> {
                if (buffer.trim().isNotEmpty()) {
                    val message = buffer.trim()
                    updateHistorySync(context, "\nyou: $message")
                    buffer = ""
                    prefs.edit().putString("buffer", "").apply()
                    sendToGroqStreaming(context, message)
                }
            }
            else -> {
                val shiftActive = isShift || isCaps
                val charStr = if (shiftActive) {
                    SHIFT_MAP[key] ?: if (isKorean) (KOR_SHIFT_MAP[key] ?: KOR_KEY_MAP[key] ?: key) 
                                      else key.uppercase(Locale.getDefault())
                } else {
                    SYMBOL_KEY_MAP[key] ?: if (isKorean) (KOR_KEY_MAP[key] ?: key) else key
                }

                if (isKorean && charStr.length == 1 && (charStr[0] in 'ㄱ'..'ㅎ' || charStr[0] in 'ㅏ'..'ㅣ' || charStr[0] in '가'..'힣')) {
                    buffer = HangulComposer.compose(buffer, charStr[0])
                } else {
                    buffer += charStr
                }

                if (isShift) {
                    isShift = false
                    prefs.edit().putBoolean("is_shift", false).apply()
                }
            }
        }

        prefs.edit().putString("buffer", buffer).apply()
        updateAllWidgets(context)
    }

    private fun sendToGroqStreaming(context: Context, message: String) {
        val mainPrefs = context.getSharedPreferences("groq_prefs", Context.MODE_PRIVATE)
        val apiKey = mainPrefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            updateHistorySync(context, "\nsystem: Please set API key in app.")
            return
        }

        val widgetPrefs = context.getSharedPreferences("groq_widget_prefs", Context.MODE_PRIVATE)
        val contextHistoryJson = widgetPrefs.getString("context_history", "[]") ?: "[]"
        val gson = Gson()
        val type = object : TypeToken<MutableList<Map<String, String>>>() {}.type
        val messages: MutableList<Map<String, String>> = gson.fromJson(contextHistoryJson, type)

        messages.add(mapOf("role" to "user", "content" to message))

        val requestBody = mapOf(
            "model" to "openai/gpt-oss-120b",
            "messages" to messages,
            "stream" to true
        )
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(gson.toJson(requestBody).toRequestBody(JSON))
            .build()

        updateHistorySync(context, "\nai: ")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateHistorySync(context, "error - ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    updateHistorySync(context, "api error ${response.code}")
                    return
                }

                val source = response.body?.source() ?: return
                var fullAiResponse = ""
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data == "[DONE]") break
                            
                            val jsonChunk = gson.fromJson(data, Map::class.java)
                            val choices = jsonChunk["choices"] as? List<*>
                            val firstChoice = choices?.get(0) as? Map<*, *>
                            val delta = firstChoice?.get("delta") as? Map<*, *>
                            val content = delta?.get("content") as? String
                            
                            if (!content.isNullOrEmpty()) {
                                fullAiResponse += content
                                updateHistorySync(context, content)
                            }
                        }
                    }
                    if (fullAiResponse.isNotEmpty()) {
                        messages.add(mapOf("role" to "assistant", "content" to fullAiResponse))
                        val limitedMessages = if (messages.size > 20) messages.takeLast(20) else messages
                        widgetPrefs.edit().putString("context_history", gson.toJson(limitedMessages)).apply()
                    }
                } catch (e: Exception) {}
            }
        })
    }

    @Synchronized
    private fun updateHistorySync(context: Context, text: String) {
        val prefs = context.getSharedPreferences("groq_widget_prefs", Context.MODE_PRIVATE)
        val currentHistory = prefs.getString("history", "") ?: ""
        val newHistory = currentHistory + text
        prefs.edit().putString("history", newHistory).commit()
        
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, GroqWidget::class.java))
        val views = RemoteViews(context.packageName, R.layout.groq_widget)
        views.setTextViewText(R.id.tvChatHistory, newHistory)
        for (id in ids) {
            appWidgetManager.partiallyUpdateAppWidget(id, views)
        }
    }

    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, GroqWidget::class.java))
        for (id in ids) updateAppWidget(context, appWidgetManager, id)
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("groq_widget_prefs", Context.MODE_PRIVATE)
        val buffer = prefs.getString("buffer", "") ?: ""
        val history = prefs.getString("history", "Let's start a new chat!") ?: ""
        val isKor = prefs.getBoolean("is_korean", false)
        val isShift = prefs.getBoolean("is_shift", false)
        val isCaps = prefs.getBoolean("is_caps", false)

        val views = RemoteViews(context.packageName, R.layout.groq_widget)
        views.setTextViewText(R.id.tvInputBuffer, if (buffer.isEmpty()) "type..." else buffer)
        views.setTextViewText(R.id.tvChatHistory, history)

        val keys = listOf(
            R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3", R.id.key_4 to "4", R.id.key_5 to "5",
            R.id.key_6 to "6", R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9", R.id.key_0 to "0",
            R.id.key_minus to "minus", R.id.key_equal to "equal", R.id.key_del to "DEL",
            R.id.key_tab to "TAB", R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r", R.id.key_t to "t",
            R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i", R.id.key_o to "o", R.id.key_p to "p",
            R.id.key_left_bracket to "left_bracket", R.id.key_right_bracket to "right_bracket", R.id.key_backslash to "backslash",
            R.id.key_caps to "CAPS", R.id.key_a to "a", R.id.key_s to "s", R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g",
            R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l",
            R.id.key_semicolon to "semicolon", R.id.key_quote to "quote", R.id.key_enter to "ENTER",
            R.id.key_shift to "SHIFT", R.id.key_z to "z", R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v",
            R.id.key_b to "b", R.id.key_n to "n", R.id.key_m to "m",
            R.id.key_comma to "comma", R.id.key_period to "period", R.id.key_slash to "slash", R.id.key_shift_r to "SHIFT",
            R.id.key_lang to "LANG", R.id.key_space to "SPACE", R.id.key_send to "SEND", R.id.key_reset to "RESET"
        )

        val shiftActive = isShift || isCaps

        for (pair in keys) {
            val id = pair.first
            val key = pair.second
            val intent = Intent(context, GroqWidget::class.java).apply {
                action = ACTION_KEY_CLICK
                putExtra(EXTRA_KEY, key)
            }
            views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            
            val display = if (shiftActive) {
                SHIFT_MAP[key] ?: if (isKor) (KOR_SHIFT_MAP[key] ?: KOR_KEY_MAP[key] ?: key) else key.uppercase(Locale.getDefault())
            } else {
                SYMBOL_KEY_MAP[key] ?: if (isKor) (KOR_KEY_MAP[key] ?: key) else key
            }
            
            val functionalKeys = setOf("SHIFT", "LANG", "SPACE", "SEND", "DEL", "RESET", "TAB", "CAPS", "ENTER")
            if (key !in functionalKeys) {
                views.setTextViewText(id, display)
            }
        }
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}

object HangulComposer {
    private val CHOSUNG = listOf('ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')
    private val JUNGSUNG = listOf('ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ')
    private val JONGSUNG = listOf('\u0000', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')

    fun compose(current: String, next: Char): String {
        if (current.isEmpty()) return next.toString()
        val last = current.last()
        if (last.code < 0xAC00 || last.code > 0xD7A3) {
            if (last in CHOSUNG && next in JUNGSUNG) {
                val choIndex = CHOSUNG.indexOf(last)
                val jungIndex = JUNGSUNG.indexOf(next)
                return current.dropLast(1) + (0xAC00 + (choIndex * 21 * 28) + (jungIndex * 28)).toChar()
            }
            return current + next
        }

        val base = last.code - 0xAC00
        val cho = base / (21 * 28)
        val jung = (base % (21 * 28)) / 28
        val jong = base % 28

        if (jong == 0 && next in JUNGSUNG) {
            val newJung = combineVowels(JUNGSUNG[jung], next)
            if (newJung != null) {
                val newJungIndex = JUNGSUNG.indexOf(newJung)
                return current.dropLast(1) + (0xAC00 + (cho * 21 * 28) + (newJungIndex * 28)).toChar()
            }
        } else if (jong == 0 && next in CHOSUNG) {
            val jongIndex = JONGSUNG.indexOf(next)
            if (jongIndex != -1) {
                return current.dropLast(1) + (0xAC00 + (cho * 21 * 28) + (jung * 28) + jongIndex).toChar()
            }
        } else if (jong != 0 && next in JUNGSUNG) {
            val currentJong = JONGSUNG[jong]
            val splitResult = splitJong(currentJong)
            if (splitResult.second != -1) {
                val nextJungIndex = JUNGSUNG.indexOf(next)
                val firstChar = (0xAC00 + (cho * 21 * 28) + (jung * 28) + splitResult.first).toChar()
                val secondChar = (0xAC00 + (splitResult.second * 21 * 28) + (nextJungIndex * 28)).toChar()
                return current.dropLast(1) + firstChar + secondChar
            } else {
                val nextChoIdx = CHOSUNG.indexOf(currentJong)
                val nextJungIdx = JUNGSUNG.indexOf(next)
                val firstChar = (0xAC00 + (cho * 21 * 28) + (jung * 28)).toChar()
                val secondChar = (0xAC00 + (nextChoIdx * 21 * 28) + (nextJungIdx * 28)).toChar()
                return current.dropLast(1) + firstChar + secondChar
            }
        } else if (jong != 0 && next in CHOSUNG) {
            val newJong = combineJongs(JONGSUNG[jong], next)
            if (newJong != null) {
                val newJongIndex = JONGSUNG.indexOf(newJong)
                return current.dropLast(1) + (0xAC00 + (cho * 21 * 28) + (jung * 28) + newJongIndex).toChar()
            }
        }

        return current + next
    }

    private fun combineVowels(v1: Char, v2: Char): Char? = when {
        v1 == 'ㅗ' && v2 == 'ㅏ' -> 'ㅘ'
        v1 == 'ㅗ' && v2 == 'ㅐ' -> 'ㅙ'
        v1 == 'ㅗ' && v2 == 'ㅣ' -> 'ㅚ'
        v1 == 'ㅜ' && v2 == 'ㅓ' -> 'ㅝ'
        v1 == 'ㅜ' && v2 == 'ㅔ' -> 'ㅞ'
        v1 == 'ㅜ' && v2 == 'ㅣ' -> 'ㅟ'
        v1 == 'ㅡ' && v2 == 'ㅣ' -> 'ㅢ'
        else -> null
    }

    private fun combineJongs(j1: Char, j2: Char): Char? = when {
        j1 == 'ㄱ' && j2 == 'ㅅ' -> 'ㄳ'
        j1 == 'ㄴ' && j2 == 'ㅈ' -> 'ㄵ'
        j1 == 'ㄴ' && j2 == 'ㅎ' -> 'ㄶ'
        j1 == 'ㄹ' && j2 == 'ㄱ' -> 'ㄺ'
        j1 == 'ㄹ' && j2 == 'ㅁ' -> 'ㄻ'
        j1 == 'ㄹ' && j2 == 'ㅂ' -> 'ㄼ'
        j1 == 'ㄹ' && j2 == 'ㅅ' -> 'ㄽ'
        j1 == 'ㄹ' && j2 == 'ㅌ' -> 'ㄾ'
        j1 == 'ㄹ' && j2 == 'ㅍ' -> 'ㄿ'
        j1 == 'ㄹ' && j2 == 'ㅎ' -> 'ㅀ'
        j1 == 'ㅂ' && j2 == 'ㅅ' -> 'ㅄ'
        else -> null
    }

    private fun splitJong(j: Char): Pair<Int, Int> = when(j) {
        'ㄳ' -> JONGSUNG.indexOf('ㄱ') to CHOSUNG.indexOf('ㅅ')
        'ㄵ' -> JONGSUNG.indexOf('ㄴ') to CHOSUNG.indexOf('ㅈ')
        'ㄶ' -> JONGSUNG.indexOf('ㄴ') to CHOSUNG.indexOf('ㅎ')
        'ㄺ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㄱ')
        'ㄻ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㅁ')
        'ㄼ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㅂ')
        'ㄽ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㅅ')
        'ㄾ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㅌ')
        'ㄿ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㅍ')
        'ㅀ' -> JONGSUNG.indexOf('ㄹ') to CHOSUNG.indexOf('ㅎ')
        'ㅄ' -> JONGSUNG.indexOf('ㅂ') to CHOSUNG.indexOf('ㅅ')
        else -> -1 to -1
    }
}
