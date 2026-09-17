package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isVietnameseSupported = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeVi = Locale("vi", "VN")
            val result = tts?.setLanguage(localeVi)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                isVietnameseSupported = false
                Log.e("TTS", "Vietnamese language not supported")
                onMessage("Thiết bị chưa hỗ trợ giọng đọc tiếng Việt")
            } else {
                isVietnameseSupported = true
                isInitialized = true
                setupProgressListener()
            }
        } else {
            isInitialized = false
            Log.e("TTS", "Initialization failed")
            onMessage("Không thể khởi tạo tính năng đọc to")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeakingStateChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                onSpeakingStateChanged(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeakingStateChanged(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onSpeakingStateChanged(false)
                Log.e("TTS", "Error speaking, code: $errorCode")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                onSpeakingStateChanged(false)
            }
        })
    }

    fun speak(text: String) {
        if (!isInitialized) {
            onMessage("Tính năng đọc to đang khởi tạo...")
            return
        }
        if (!isVietnameseSupported) {
            onMessage("Thiết bị chưa hỗ trợ giọng đọc tiếng Việt")
            return
        }
        
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FruitSpeechUtteranceId")
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
            onSpeakingStateChanged(false)
        }
    }

    fun shutdown() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }
}
