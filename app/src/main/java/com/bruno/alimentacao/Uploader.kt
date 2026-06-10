package com.bruno.alimentacao

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Envia uma foto para o webservice configurado.
 *
 * CONTRATO (o backend Cloudflare deve seguir isto):
 *   POST  {wsUrl}
 *   Header: Authorization: Bearer {apiToken}   (omitido se token vazio)
 *   Content-Type: multipart/form-data
 *   Campos:
 *     - photo     : arquivo JPEG (filename = nome da foto)
 *     - taken_at  : timestamp em milissegundos (epoch)
 *     - filename  : nome do arquivo
 *     - device    : modelo do aparelho (ajuda a distinguir usuários/origens)
 *   Sucesso = código HTTP 2xx.
 */
object Uploader {

    data class Result(val ok: Boolean, val message: String)

    /**
     * Envio SÍNCRONO a partir de uma Uri (lê os bytes e envia). Usado pelo UploadWorker,
     * que decide reagendar em caso de falha. Se não houver destino configurado, considera "ok"
     * (não há o que enviar).
     */
    fun uploadFromUriSync(context: Context, photoUri: Uri, takenAtMillis: Long, fileName: String): Result {
        val appContext = context.applicationContext
        val url = Prefs.wsUrl(appContext)
        val token = Prefs.apiToken(appContext)
        if (url.isEmpty()) return Result(true, "Sem destino configurado")
        return try {
            val bytes = appContext.contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
                ?: return Result(false, "Não consegui ler a foto")
            doUpload(url, token, bytes, fileName, takenAtMillis)
        } catch (e: Exception) {
            Result(false, "Erro: ${e.message}")
        }
    }

    /** Envia bytes diretamente (usado pelo botão "Testar envio"). */
    fun uploadBytes(
        url: String,
        token: String,
        bytes: ByteArray,
        fileName: String,
        onResult: (Result) -> Unit
    ) {
        thread(name = "upload-test") {
            val result = try {
                doUpload(url, token, bytes, fileName, System.currentTimeMillis())
            } catch (e: Exception) {
                Result(false, "Erro: ${e.message}")
            }
            postBack(onResult, result)
        }
    }

    private fun doUpload(
        url: String,
        token: String,
        bytes: ByteArray,
        fileName: String,
        takenAtMillis: Long
    ): Result {
        if (url.isEmpty()) return Result(false, "Endereço do webservice vazio")

        val boundary = "----AlimentacaoBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }

        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary$lineEnd")
                out.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
                out.writeBytes(value + lineEnd)
            }
            field("taken_at", takenAtMillis.toString())
            field("filename", fileName)
            field("device", Build.MODEL ?: "android")

            out.writeBytes("--$boundary$lineEnd")
            out.writeBytes(
                "Content-Disposition: form-data; name=\"photo\"; filename=\"$fileName\"$lineEnd"
            )
            out.writeBytes("Content-Type: image/jpeg$lineEnd$lineEnd")
            out.write(bytes)
            out.writeBytes(lineEnd)
            out.writeBytes("--$boundary--$lineEnd")
            out.flush()
        }

        val code = conn.responseCode
        val body = try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
        conn.disconnect()

        return if (code in 200..299) {
            Result(true, "Enviado (HTTP $code)")
        } else {
            Result(false, "Servidor recusou (HTTP $code) ${body.take(120)}")
        }
    }

    private fun postBack(onResult: ((Result) -> Unit)?, result: Result) {
        if (onResult == null) return
        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
    }
}
