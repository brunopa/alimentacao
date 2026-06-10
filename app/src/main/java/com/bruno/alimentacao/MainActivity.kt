package com.bruno.alimentacao

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bruno.alimentacao.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultado tratado ao tentar ligar o serviço */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()

        binding.toggleButton.setOnClickListener {
            if (serviceRunning) stopShakeService() else tryStartShakeService()
        }

        binding.testPhotoButton.setOnClickListener {
            if (hasCameraPermission()) {
                startActivity(Intent(this, CameraActivity::class.java))
            } else {
                requestRuntimePermissions()
            }
        }

        setupSettings()
        setupUpload()
        updateUi()
    }

    private fun setupUpload() {
        binding.wsUrlInput.setText(Prefs.wsUrl(this))
        binding.apiTokenInput.setText(Prefs.apiToken(this))

        binding.saveUploadButton.setOnClickListener {
            Prefs.setUpload(
                this,
                binding.wsUrlInput.text.toString(),
                binding.apiTokenInput.text.toString()
            )
            Toast.makeText(this, "Configuração de envio salva", Toast.LENGTH_SHORT).show()
        }

        binding.testUploadButton.setOnClickListener {
            val url = binding.wsUrlInput.text.toString().trim()
            val token = binding.apiTokenInput.text.toString().trim()
            if (url.isEmpty()) {
                binding.uploadStatus.text = "Informe o endereço do webservice primeiro."
                return@setOnClickListener
            }
            // Salva o que está digitado antes de testar.
            Prefs.setUpload(this, url, token)
            binding.uploadStatus.text = "Enviando teste..."
            val bytes = makeTestJpeg()
            Uploader.uploadBytes(url, token, bytes, "ALIM_TESTE.jpg") { result ->
                binding.uploadStatus.text =
                    (if (result.ok) "OK: " else "Falhou: ") + result.message
            }
        }
    }

    /** Gera uma imagem JPEG pequena só para testar a conexão de envio. */
    private fun makeTestJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565)
        Canvas(bmp).drawColor(Color.rgb(46, 125, 50))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun setupSettings() {
        // Sensibilidade
        when (Prefs.sensIndex(this)) {
            0 -> binding.sensLow.isChecked = true
            2 -> binding.sensHigh.isChecked = true
            else -> binding.sensNormal.isChecked = true
        }
        binding.sensGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = when (checkedId) {
                binding.sensLow.id -> 0
                binding.sensHigh.id -> 2
                else -> 1
            }
            Prefs.setSensIndex(this, index)
        }

        // Tempo de foco
        val delay = Prefs.delayMs(this).toInt()
        binding.delaySeek.progress = delay
        updateDelayLabel(delay)
        binding.delaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateDelayLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Arredonda para 100 ms para um valor "redondo".
                val rounded = (Math.round((seekBar?.progress ?: 0) / 100.0) * 100).toInt()
                    .coerceIn(500, 3000)
                seekBar?.progress = rounded
                Prefs.setDelayMs(this@MainActivity, rounded)
                updateDelayLabel(rounded)
            }
        })
    }

    private fun updateDelayLabel(ms: Int) {
        val seconds = ms / 1000.0
        binding.delayLabel.text = "Tempo para focar antes da foto: %.1f s".format(seconds)
    }

    private fun tryStartShakeService() {
        if (!hasCameraPermission()) {
            requestRuntimePermissions()
            return
        }
        if (!canDrawOverlay()) {
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, ShakeService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        updateUi()
    }

    private fun stopShakeService() {
        stopService(Intent(this, ShakeService::class.java))
        serviceRunning = false
        updateUi()
    }

    private fun updateUi() {
        binding.statusText.text = getString(
            if (serviceRunning) R.string.status_on else R.string.status_off
        )
        binding.toggleButton.text = getString(
            if (serviceRunning) R.string.stop_service else R.string.start_service
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(android.Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}
