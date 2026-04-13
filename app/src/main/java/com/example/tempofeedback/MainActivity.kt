package com.example.tempofeedback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tempofeedback.databinding.ActivityMainBinding
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

  companion object {
    private const val ACTION_USB_PERMISSION = "com.example.tempofeedback.USB_PERMISSION"
    private const val BAUD_RATE = 115200
  }

  private lateinit var binding: ActivityMainBinding
  private lateinit var usbManager: UsbManager
  private var textToSpeech: TextToSpeech? = null

  private var serialPort: UsbSerialPort? = null
  private var ioManager: SerialInputOutputManager? = null

  private var lastSpokenStatus: String = ""
  private var lastSpeechTimeMs: Long = 0L
  private val logLines = ArrayDeque<String>()
  private var serialBuffer = ""

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        ACTION_USB_PERMISSION -> {
          val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
          if (!granted) {
            showToast(getString(R.string.usb_permission_denied))
            return
          }
          val device: UsbDevice? = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE)
          if (device != null) {
            connectToDevice(device)
          }
        }
        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
          val device: UsbDevice? = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE)
          if (device != null) {
            requestPermission(device)
          }
        }
        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
          disconnectSerial()
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
    textToSpeech = TextToSpeech(this, this)

    binding.connectButton.setOnClickListener {
      if (serialPort == null) {
        requestFirstAvailableDevice()
      } else {
        disconnectSerial()
      }
    }

    binding.resetButton.setOnClickListener { resetUi() }
    binding.clearLogButton.setOnClickListener { clearLog() }
  }

  override fun onStart() {
    super.onStart()
    val filter = IntentFilter().apply {
      addAction(ACTION_USB_PERMISSION)
      addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
      addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
  }

  override fun onStop() {
    super.onStop()
    unregisterReceiver(usbReceiver)
  }

  override fun onDestroy() {
    super.onDestroy()
    disconnectSerial()
    textToSpeech?.stop()
    textToSpeech?.shutdown()
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      textToSpeech?.language = Locale.JAPAN
    }
  }

  private fun requestFirstAvailableDevice() {
    val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    val driver = drivers.firstOrNull()
    if (driver == null) {
      showToast(getString(R.string.usb_not_found))
      return
    }
    requestPermission(driver.device)
  }

  private fun requestPermission(device: UsbDevice) {
    if (usbManager.hasPermission(device)) {
      connectToDevice(device)
      return
    }

    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_IMMUTABLE
    } else {
      0
    }

    val permissionIntent = PendingIntent.getBroadcast(
      this,
      0,
      Intent(ACTION_USB_PERMISSION),
      flags,
    )
    usbManager.requestPermission(device, permissionIntent)
  }

  private fun connectToDevice(device: UsbDevice) {
    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
    if (driver == null) {
      showToast(getString(R.string.usb_not_found))
      return
    }

    val connection = usbManager.openDevice(device)
    if (connection == null) {
      showToast(getString(R.string.usb_permission_denied))
      return
    }

    runCatching {
      val port = driver.ports.first()
      port.open(connection)
      port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
      serialPort = port
      startReading()
      binding.connectButton.text = getString(R.string.disconnect_button)
      binding.inputSourceText.text = getString(R.string.source_usb_sensor)
      renderNeutralStatus(getString(R.string.status_listening))
    }.onFailure { error ->
      connection.close()
      showToast(error.message ?: getString(R.string.serial_read_error))
    }
  }

  private fun startReading() {
    ioManager?.stop()

    val activePort = serialPort ?: return
    ioManager = SerialInputOutputManager(
      activePort,
      object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
          val chunk = data.toString(Charsets.UTF_8)
          runOnUiThread {
            processSerialChunk(chunk)
          }
        }

        override fun onRunError(e: Exception) {
          runOnUiThread {
            showToast(e.message ?: getString(R.string.serial_read_error))
            disconnectSerial()
          }
        }
      }
    ).also { it.start() }
  }

  private fun processSerialChunk(chunk: String) {
    serialBuffer += chunk
    val lines = serialBuffer.split('\n')
    serialBuffer = lines.lastOrNull().orEmpty()
    lines.dropLast(1).forEach { line ->
      val bpm = parseBpm(line.trim()) ?: return@forEach
      evaluateTempo(bpm)
    }
  }

  private fun parseBpm(line: String): Float? {
    if (line.isBlank()) return null
    val parts = line.split(",")
    if (parts.size < 3) return null
    val bpm = parts[2].toFloatOrNull() ?: return null
    return bpm.takeIf { it > 0f }
  }

  private fun evaluateTempo(bpm: Float) {
    val target = binding.targetBpmInput.text?.toString()?.toIntOrNull()?.coerceIn(20, 300) ?: 120
    val tolerance = binding.toleranceInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 30) ?: 3
    val delta = bpm - target

    binding.currentBpmText.text = String.format(Locale.US, "%.1f", bpm)
    binding.deltaText.text = String.format(Locale.US, "%+.1f", delta)

    val judgement = when {
      delta > tolerance -> TempoJudgement.FAST
      delta < -tolerance -> TempoJudgement.SLOW
      else -> TempoJudgement.OK
    }

    when (judgement) {
      TempoJudgement.FAST -> {
        renderFastStatus(getString(R.string.status_fast))
        speakOnce(getString(R.string.status_fast))
      }
      TempoJudgement.SLOW -> {
        renderSlowStatus(getString(R.string.status_slow))
        speakOnce(getString(R.string.status_slow))
      }
      TempoJudgement.OK -> {
        renderOkStatus(getString(R.string.status_good))
      }
    }

    appendLog(
      String.format(
        Locale.US,
        "%tT  bpm=%.1f  delta=%+.1f  %s",
        System.currentTimeMillis(),
        bpm,
        delta,
        when (judgement) {
          TempoJudgement.FAST -> getString(R.string.status_fast)
          TempoJudgement.SLOW -> getString(R.string.status_slow)
          TempoJudgement.OK -> getString(R.string.status_good)
        },
      ),
    )
  }

  private fun speakOnce(text: String) {
    if (!binding.speechSwitch.isChecked) {
      return
    }

    val now = System.currentTimeMillis()
    if (text == lastSpokenStatus && now - lastSpeechTimeMs < 900L) {
      return
    }

    lastSpokenStatus = text
    lastSpeechTimeMs = now
    textToSpeech?.stop()
    textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tempo_status")
  }

  private fun appendLog(line: String) {
    if (logLines.size >= 40) {
      logLines.removeLast()
    }
    logLines.addFirst(line)
    binding.logText.text = logLines.joinToString("\n")
  }

  private fun clearLog() {
    logLines.clear()
    binding.logText.text = getString(R.string.log_empty)
  }

  private fun resetUi() {
    binding.currentBpmText.text = "--"
    binding.deltaText.text = "--"
    lastSpokenStatus = ""
    lastSpeechTimeMs = 0L
    renderNeutralStatus(if (serialPort == null) getString(R.string.status_waiting) else getString(R.string.status_listening))
  }

  private fun disconnectSerial() {
    ioManager?.stop()
    ioManager = null
    runCatching { serialPort?.close() }
    serialPort = null
    serialBuffer = ""
    binding.connectButton.text = getString(R.string.connect_button)
    binding.inputSourceText.text = getString(R.string.source_not_connected)
    renderNeutralStatus(getString(R.string.status_waiting))
  }

  private fun renderNeutralStatus(text: String) {
    binding.statusText.text = text
    binding.statusText.background = ContextCompat.getDrawable(this, R.drawable.status_background_neutral)
    binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.textPrimary))
  }

  private fun renderFastStatus(text: String) {
    binding.statusText.text = text
    binding.statusText.background = ContextCompat.getDrawable(this, R.drawable.status_background_fast)
    binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.statusFastText))
  }

  private fun renderSlowStatus(text: String) {
    binding.statusText.text = text
    binding.statusText.background = ContextCompat.getDrawable(this, R.drawable.status_background_slow)
    binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.statusSlowText))
  }

  private fun renderOkStatus(text: String) {
    binding.statusText.text = text
    binding.statusText.background = ContextCompat.getDrawable(this, R.drawable.status_background_ok)
    binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.statusOkText))
  }

  private fun showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }
}

private enum class TempoJudgement {
  FAST,
  SLOW,
  OK,
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.parcelableExtraCompat(key: String): T? {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(key, T::class.java)
  } else {
    getParcelableExtra(key) as? T
  }
}
