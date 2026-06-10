package com.bruno.alimentacao

import android.content.Context

/**
 * Centraliza as preferências do app: sensibilidade do shake e tempo de foco antes da foto.
 */
object Prefs {
    const val NAME = "alimentacao_prefs"
    const val KEY_SENS_INDEX = "sens_index"   // 0 = Baixa, 1 = Normal, 2 = Alta
    const val KEY_DELAY_MS = "delay_ms"        // atraso antes de capturar
    const val KEY_WS_URL = "ws_url"            // endereço do webservice (upload)
    const val KEY_API_TOKEN = "api_token"      // token de autenticação do usuário

    const val DEFAULT_SENS_INDEX = 1
    const val DEFAULT_DELAY_MS = 1500

    // Limiar de força (g) para disparar. Quanto MENOR o limiar, mais sensível (abre mais fácil).
    private val THRESHOLDS = floatArrayOf(2.9f, 2.3f, 1.7f)

    fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun sensIndex(context: Context): Int =
        prefs(context).getInt(KEY_SENS_INDEX, DEFAULT_SENS_INDEX).coerceIn(0, 2)

    fun threshold(context: Context): Float = THRESHOLDS[sensIndex(context)]

    fun delayMs(context: Context): Long =
        prefs(context).getInt(KEY_DELAY_MS, DEFAULT_DELAY_MS).toLong()

    fun setSensIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_SENS_INDEX, index.coerceIn(0, 2)).apply()
    }

    fun setDelayMs(context: Context, ms: Int) {
        prefs(context).edit().putInt(KEY_DELAY_MS, ms).apply()
    }

    fun wsUrl(context: Context): String =
        prefs(context).getString(KEY_WS_URL, "")?.trim().orEmpty()

    fun apiToken(context: Context): String =
        prefs(context).getString(KEY_API_TOKEN, "")?.trim().orEmpty()

    fun setUpload(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(KEY_WS_URL, url.trim())
            .putString(KEY_API_TOKEN, token.trim())
            .apply()
    }

    fun uploadConfigured(context: Context): Boolean = wsUrl(context).isNotEmpty()

    fun sensLabel(index: Int): String = when (index) {
        0 -> "Baixa (balançar forte)"
        2 -> "Alta (abre fácil)"
        else -> "Normal"
    }
}
