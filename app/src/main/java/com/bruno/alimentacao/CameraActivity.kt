package com.bruno.alimentacao

import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.bruno.alimentacao.databinding.ActivityCameraBinding
import java.text.SimpleDateFormat
import java.util.Locale

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Sem permissão de câmera. Abra o app e conceda.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
                // Atraso (configurável) para o foco automático estabilizar antes de capturar.
                binding.previewView.postDelayed({ takePhoto() }, Prefs.delayMs(this))
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao abrir câmera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (captured) return
        captured = true

        val capture = imageCapture ?: run { finish(); return }
        binding.cameraHint.text = "Capturando..."

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val fileName = "ALIM_$timestamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DiarioAlimentacao")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@CameraActivity, "Falha ao salvar: ${exc.message}", Toast.LENGTH_LONG).show()
                    finish()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@CameraActivity, "Foto salva: $fileName", Toast.LENGTH_SHORT).show()
                    val savedUri = output.savedUri
                    if (savedUri != null && Prefs.uploadConfigured(this@CameraActivity)) {
                        // Envio resiliente: WorkManager reenvia sozinho quando houver internet.
                        UploadWorker.enqueue(
                            applicationContext,
                            savedUri,
                            fileName,
                            System.currentTimeMillis()
                        )
                    }
                    finish()
                }
            }
        )
    }
}
