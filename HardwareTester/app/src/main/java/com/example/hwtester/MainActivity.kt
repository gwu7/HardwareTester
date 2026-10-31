package com.example.hwtester

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.bluetooth.BluetoothManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

enum class Status { PASS, FAIL, SKIP }
class Result(val status: Status, val detail: String)

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val pendingPerms = HashMap<Int, Pair<() -> Unit, () -> Unit>>()
    private var nextReqCode = 100

    private var night = false
    private var cBg = 0
    private var cCard = 0
    private var cStroke = 0
    private var cText = 0
    private var cSub = 0
    private var cRed = 0xFFD71921.toInt()

    inner class Row(val name: String, val fn: ((Result) -> Unit) -> Unit) {
        lateinit var status: TextView
        lateinit var detail: TextView
        lateinit var btn: Button
        var result: Result? = null
    }

    private lateinit var rows: List<Row>
    private lateinit var summary: TextView
    private lateinit var dots: DotProgress
    private var runningAll = false

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) {
            cBg = 0xFF000000.toInt(); cCard = 0xFF0D0D0D.toInt(); cStroke = 0xFF2A2A2A.toInt()
            cText = 0xFFFFFFFF.toInt(); cSub = 0xFF8C8C8C.toInt()
        } else {
            cBg = 0xFFF2F2F2.toInt(); cCard = 0xFFFFFFFF.toInt(); cStroke = 0xFFD6D6D6.toInt()
            cText = 0xFF000000.toInt(); cSub = 0xFF707070.toInt()
        }
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (!night) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        rows = listOf(
            Row("Speaker") { done -> speakerTest(done) },
            Row("Microphone") { done -> micTest(done) },
            Row("Vibration motor") { done -> vibrationTest(done) },
            Row("Flashlight") { done -> flashTest(done) },
            Row("Cameras") { done -> cameraTest(done) },
            Row("Touchscreen") { done -> touchTest(done) },
            Row("Display / dead pixels") { done -> displayTest(done) },
            Row("Volume buttons") { done -> volumeTest(done) },
            Row("Accelerometer") { done -> sensorTest(Sensor.TYPE_ACCELEROMETER, "Accelerometer", done) },
            Row("Gyroscope") { done -> sensorTest(Sensor.TYPE_GYROSCOPE, "Gyroscope", done) },
            Row("Magnetometer") { done -> sensorTest(Sensor.TYPE_MAGNETIC_FIELD, "Magnetometer", done) },
            Row("Proximity sensor") { done -> sensorTest(Sensor.TYPE_PROXIMITY, "Proximity", done) },
            Row("Light sensor") { done -> sensorTest(Sensor.TYPE_LIGHT, "Light", done) },
            Row("Barometer") { done -> sensorTest(Sensor.TYPE_PRESSURE, "Barometer", done) },
            Row("Wi-Fi") { done -> wifiTest(done) },
            Row("Bluetooth") { done -> bluetoothTest(done) },
            Row("NFC") { done -> nfcTest(done) },
            Row("GPS") { done -> gpsTest(done) },
            Row("Fingerprint") { done -> biometricTest(done) },
            Row("Battery") { done -> batteryTest(done) },
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        // --- header: dot-matrix title, dot progress, summary, run button
        root.addView(mono("●  SYSTEM CHECK", 11f, cRed))
        val dot = dp(4).toFloat()
        val gap = dp(2).toFloat()
        root.addView(DotText(this, "HARDWARE", dot, gap, cText),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) })
        root.addView(DotText(this, "TESTER", dot, gap, cText),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })

        dots = DotProgress(this, rows.size, cText, cSub, cRed)
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18))
            .apply { topMargin = dp(26) })

        summary = mono("", 11f, cSub)
        root.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        root.addView(Button(this).apply {
            text = "RUN ALL TESTS"
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            textSize = 13f
            setTextColor(cBg)
            background = rounded(cText, 28, null)
            stateListAnimator = null
            setOnClickListener { runAll() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            .apply { topMargin = dp(18); bottomMargin = dp(24) })

        for ((i, row) in rows.withIndex()) {
            root.addView(buildRow(row, i + 1), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        })
        updateSummary()
    }

    private fun mono(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t
        textSize = size
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
        setTextColor(color)
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun buildRow(row: Row, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard, 26, cStroke)
            setPadding(dp(18), dp(16), dp(14), dp(16))
        }
        card.addView(mono("%02d".format(index), 12f, cSub), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(14) })

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = row.name
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cText)
        })
        row.status = mono("○  NOT TESTED", 10.5f, cSub)
        mid.addView(row.status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) })
        row.detail = TextView(this).apply {
            textSize = 12f
            setTextColor(cSub)
            visibility = View.GONE
        }
        mid.addView(row.detail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        card.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(8) })

        row.btn = Button(this).apply {
            text = "TEST"
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            textSize = 12f
            setTextColor(cText)
            background = rounded(Color.TRANSPARENT, 20, cText)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener { run(row) }
        }
        card.addView(row.btn)
        return card
    }

    private fun show(row: Row, r: Result) {
        row.result = r
        when (r.status) {
            Status.PASS -> { row.status.text = "●  WORKING"; row.status.setTextColor(cText) }
            Status.FAIL -> { row.status.text = "●  PROBLEM DETECTED"; row.status.setTextColor(cRed) }
            Status.SKIP -> { row.status.text = "○  SKIPPED"; row.status.setTextColor(cSub) }
        }
        row.detail.text = r.detail
        row.detail.visibility = if (r.detail.isEmpty()) View.GONE else View.VISIBLE
        row.btn.isEnabled = true
        row.btn.alpha = 1f
        updateSummary()
    }

    private fun updateSummary() {
        val res = rows.mapNotNull { it.result }
        val p = res.count { it.status == Status.PASS }
        val f = res.count { it.status == Status.FAIL }
        val s = res.count { it.status == Status.SKIP }
        summary.text = "%02d/%02d TESTED  ·  %02d OK  ·  %02d FAIL  ·  %02d SKIP"
            .format(res.size, rows.size, p, f, s)
        dots.states = IntArray(rows.size) { i ->
            when (rows[i].result?.status) {
                Status.PASS -> 1
                Status.FAIL -> 2
                Status.SKIP -> 3
                null -> 0
            }
        }
    }

    private fun run(row: Row, then: (() -> Unit)? = null) {
        row.status.text = "●  TESTING…"
        row.status.setTextColor(cRed)
        row.detail.text = ""
        row.detail.visibility = View.GONE
        row.btn.isEnabled = false
        row.btn.alpha = 0.4f
        var called = false
        val finish: (Result) -> Unit = { r ->
            if (!called) {
                called = true
                main.post { show(row, r); then?.invoke() }
            }
        }
        try {
            row.fn(finish)
        } catch (e: Exception) {
            finish(Result(Status.FAIL, "Error: ${e.message}"))
        }
    }

    private fun runAll() {
        if (runningAll) return
        runningAll = true
        fun next(i: Int) {
            if (i >= rows.size) { runningAll = false; return }
            run(rows[i]) { next(i + 1) }
        }
        next(0)
    }

    private fun dlgTheme() =
        if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ helpers

    private fun withPermission(perm: String, ok: () -> Unit, denied: () -> Unit) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) { ok(); return }
        val code = nextReqCode++
        pendingPerms[code] = ok to denied
        requestPermissions(arrayOf(perm), code)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        val cb = pendingPerms.remove(code) ?: return
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) cb.first() else cb.second()
    }

    private fun confirm(msg: String, done: (Result) -> Unit) {
        AlertDialog.Builder(this, dlgTheme())
            .setTitle("Please confirm")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Yes") { _, _ -> done(Result(Status.PASS, "Confirmed by you")) }
            .setNegativeButton("No") { _, _ -> done(Result(Status.FAIL, "You reported it is not working")) }
            .show()
    }

    private fun denied(done: (Result) -> Unit) =
        done(Result(Status.SKIP, "Permission denied – grant it in app settings"))

    // -------------------------------------------------------------- tests

    private fun speakerTest(done: (Result) -> Unit) {
        Toast.makeText(this, "Turn the media volume up", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val rate = 44100
                val n = rate * 2
                val buf = ShortArray(n) { (sin(2 * PI * 440 * it / rate) * Short.MAX_VALUE * 0.6).toInt().toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, n)
                track.play()
                Thread.sleep(2200)
                track.release()
                main.post { confirm("Did you hear a 2-second tone from the speaker?", done) }
            } catch (e: Exception) {
                main.post { done(Result(Status.FAIL, "Could not play audio: ${e.message}")) }
            }
        }.start()
    }

    private fun micTest(done: (Result) -> Unit) {
        withPermission(Manifest.permission.RECORD_AUDIO, {
            Toast.makeText(this, "Speak or clap for 3 seconds…", Toast.LENGTH_LONG).show()
            Thread {
                try {
                    val rate = 44100
                    val min = AudioRecord.getMinBufferSize(
                        rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate)
                    )
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        main.post { done(Result(Status.FAIL, "Microphone could not be initialised")) }
                        return@Thread
                    }
                    rec.startRecording()
                    val b = ShortArray(2048)
                    var peak = 0
                    val end = SystemClock.elapsedRealtime() + 3000
                    while (SystemClock.elapsedRealtime() < end) {
                        val r = rec.read(b, 0, b.size)
                        for (i in 0 until r) peak = max(peak, abs(b[i].toInt()))
                    }
                    rec.stop()
                    rec.release()
                    main.post {
                        if (peak > 1000) done(Result(Status.PASS, "Sound detected (peak level $peak)"))
                        else done(Result(Status.FAIL, "Almost no sound picked up (peak level $peak)"))
                    }
                } catch (e: Exception) {
                    main.post { done(Result(Status.FAIL, "Recording failed: ${e.message}")) }
                }
            }.start()
        }, { denied(done) })
    }

    private fun vibrationTest(done: (Result) -> Unit) {
        val v = getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) {
            done(Result(Status.SKIP, "No vibration motor reported"))
            return
        }
        v.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
        main.postDelayed({ confirm("Did the phone vibrate?", done) }, 1000)
    }

    private fun flashTest(done: (Result) -> Unit) {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            done(Result(Status.SKIP, "No flash unit reported"))
            return
        }
        cm.setTorchMode(id, true)
        main.postDelayed({
            try { cm.setTorchMode(id, false) } catch (_: Exception) {}
            confirm("Did the flashlight turn on?", done)
        }, 1500)
    }

    private fun cameraTest(done: (Result) -> Unit) {
        withPermission(Manifest.permission.CAMERA, {
            val cm = getSystemService(CameraManager::class.java)
            val ids = cm.cameraIdList
            if (ids.isEmpty()) {
                done(Result(Status.SKIP, "No cameras reported"))
            } else {
                var ok = 0
                fun next(i: Int) {
                    if (i >= ids.size) {
                        done(
                            Result(
                                if (ok == ids.size) Status.PASS else Status.FAIL,
                                "$ok of ${ids.size} cameras opened successfully"
                            )
                        )
                        return
                    }
                    try {
                        cm.openCamera(ids[i], object : CameraDevice.StateCallback() {
                            override fun onOpened(c: CameraDevice) { c.close(); ok++; next(i + 1) }
                            override fun onDisconnected(c: CameraDevice) { c.close(); next(i + 1) }
                            override fun onError(c: CameraDevice, e: Int) { c.close(); next(i + 1) }
                        }, main)
                    } catch (e: Exception) {
                        next(i + 1)
                    }
                }
                next(0)
            }
        }, { denied(done) })
    }

    private fun touchTest(done: (Result) -> Unit) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val cols = 4
        val rowsN = 6
        var remaining = cols * rowsN
        for (r in 0 until rowsN) {
            val line = LinearLayout(this)
            root.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until cols) {
                val cell = View(this)
                cell.setBackgroundColor(0xFF555555.toInt())
                var hit = false
                cell.setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_DOWN && !hit) {
                        hit = true
                        v.setBackgroundColor(0xFF2E7D32.toInt())
                        remaining--
                        if (remaining == 0) { d.dismiss(); done(Result(Status.PASS, "All screen areas responded")) }
                    }
                    true
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(2, 2, 2, 2)
                line.addView(cell, lp)
            }
        }
        root.addView(Button(this).apply {
            text = "Give up"
            setOnClickListener {
                d.dismiss()
                done(Result(Status.FAIL, "$remaining of ${cols * rowsN} screen areas did not respond"))
            }
        })
        d.setContentView(root)
        d.setCancelable(false)
        d.show()
        Toast.makeText(this, "Tap every square until all turn green", Toast.LENGTH_LONG).show()
    }

    private fun displayTest(done: (Result) -> Unit) {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK)
        var i = 0
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val v = View(this)
        v.setBackgroundColor(colors[0])
        v.setOnClickListener {
            i++
            if (i >= colors.size) {
                d.dismiss()
                confirm("Were all colors uniform, with no dead pixels, stuck pixels or lines?", done)
            } else v.setBackgroundColor(colors[i])
        }
        d.setContentView(v)
        d.setCancelable(false)
        d.show()
        Toast.makeText(this, "Tap the screen to cycle colors", Toast.LENGTH_LONG).show()
    }

    private fun volumeTest(done: (Result) -> Unit) {
        var up = false
        var down = false
        val tv = TextView(this).apply {
            text = "Press Volume Up and Volume Down"
            textSize = 18f
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val d = AlertDialog.Builder(this, dlgTheme())
            .setTitle("Volume buttons")
            .setView(tv)
            .setCancelable(false)
            .setNegativeButton("Give up") { _, _ -> done(Result(Status.FAIL, "Button presses not detected")) }
            .create()
        d.setOnKeyListener { _, code, ev ->
            val vol = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            if (vol && ev.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) up = true else down = true
                tv.text = "Volume Up: ${if (up) "✔" else "…"}\nVolume Down: ${if (down) "✔" else "…"}"
                if (up && down) { d.dismiss(); done(Result(Status.PASS, "Both volume buttons registered")) }
            }
            vol
        }
        d.show()
    }

    private fun sensorTest(type: Int, label: String, done: (Result) -> Unit) {
        val sm = getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(type)
        if (sensor == null) {
            done(Result(Status.SKIP, "$label sensor not present"))
            return
        }
        var finished = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (finished) return
                finished = true
                sm.unregisterListener(this)
                val vals = e.values.take(3).joinToString(", ") { "%.2f".format(it) }
                done(Result(Status.PASS, "${sensor.name}: $vals"))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        main.postDelayed({
            if (!finished) {
                finished = true
                sm.unregisterListener(l)
                done(Result(Status.FAIL, "${sensor.name} is present but sent no data"))
            }
        }, 3000)
    }

    private fun wifiTest(done: (Result) -> Unit) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            done(Result(Status.SKIP, "No Wi-Fi hardware reported"))
            return
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (wm.isWifiEnabled) done(Result(Status.PASS, "Wi-Fi radio is on"))
        else done(Result(Status.FAIL, "Wi-Fi is turned off – enable it and test again"))
    }

    private fun bluetoothTest(done: (Result) -> Unit) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        when {
            adapter == null -> done(Result(Status.SKIP, "No Bluetooth hardware reported"))
            adapter.isEnabled -> done(Result(Status.PASS, "Bluetooth radio is on"))
            else -> done(Result(Status.FAIL, "Bluetooth is turned off – enable it and test again"))
        }
    }

    private fun nfcTest(done: (Result) -> Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        when {
            adapter == null -> done(Result(Status.SKIP, "No NFC hardware reported"))
            adapter.isEnabled -> done(Result(Status.PASS, "NFC is on"))
            else -> done(Result(Status.FAIL, "NFC is turned off – enable it and test again"))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private fun gpsTest(done: (Result) -> Unit) {
        withPermission(Manifest.permission.ACCESS_FINE_LOCATION, {
            val lm = getSystemService(LocationManager::class.java)
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                done(Result(Status.FAIL, "Location/GPS is turned off – enable it and test again"))
            } else {
                Toast.makeText(this, "Go near a window or outside – waiting up to 30s", Toast.LENGTH_LONG).show()
                var finished = false
                val l = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (finished) return
                        finished = true
                        lm.removeUpdates(this)
                        done(Result(Status.PASS, "GPS fix acquired (accuracy ±${loc.accuracy.toInt()} m)"))
                    }
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, l, Looper.getMainLooper())
                main.postDelayed({
                    if (!finished) {
                        finished = true
                        lm.removeUpdates(l)
                        done(Result(Status.FAIL, "No GPS fix within 30s (try again outdoors)"))
                    }
                }, 30000)
            }
        }, { denied(done) })
    }

    @Suppress("DEPRECATION")
    private fun biometricTest(done: (Result) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            done(Result(Status.SKIP, "Needs Android 10 or newer"))
            return
        }
        val bm = getSystemService(BiometricManager::class.java)
        val code = if (Build.VERSION.SDK_INT >= 30)
            bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
        else bm.canAuthenticate()
        when (code) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                done(Result(Status.PASS, "Sensor present, enrolled and ready"))
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                done(Result(Status.PASS, "Sensor present (no fingerprint/face enrolled yet)"))
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                done(Result(Status.SKIP, "No biometric hardware reported"))
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                done(Result(Status.FAIL, "Biometric hardware is unavailable"))
            else -> done(Result(Status.SKIP, "Biometric status code $code"))
        }
    }

    private fun batteryTest(done: (Result) -> Unit) {
        val i: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) {
            done(Result(Status.SKIP, "Battery info unavailable"))
            return
        }
        val health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val info = "$level% • $temp °C"
        when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> done(Result(Status.PASS, "Health good ($info)"))
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> done(Result(Status.FAIL, "Overheating ($info)"))
            BatteryManager.BATTERY_HEALTH_DEAD -> done(Result(Status.FAIL, "Battery reported dead"))
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> done(Result(Status.FAIL, "Over-voltage ($info)"))
            BatteryManager.BATTERY_HEALTH_COLD -> done(Result(Status.FAIL, "Too cold ($info)"))
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> done(Result(Status.FAIL, "Unspecified failure ($info)"))
            else -> done(Result(Status.SKIP, "Health unknown ($info)"))
        }
    }
}


// ---------------------------------------------------------------- dot-matrix views

private val GLYPHS: Map<Char, List<Int>> = mapOf(
    'A' to "01110 10001 10001 11111 10001 10001 10001",
    'B' to "11110 10001 10001 11110 10001 10001 11110",
    'C' to "01110 10001 10000 10000 10000 10001 01110",
    'D' to "11110 10001 10001 10001 10001 10001 11110",
    'E' to "11111 10000 10000 11110 10000 10000 11111",
    'F' to "11111 10000 10000 11110 10000 10000 10000",
    'G' to "01110 10001 10000 10111 10001 10001 01111",
    'H' to "10001 10001 10001 11111 10001 10001 10001",
    'I' to "01110 00100 00100 00100 00100 00100 01110",
    'J' to "00111 00010 00010 00010 00010 10010 01100",
    'K' to "10001 10010 10100 11000 10100 10010 10001",
    'L' to "10000 10000 10000 10000 10000 10000 11111",
    'M' to "10001 11011 10101 10101 10001 10001 10001",
    'N' to "10001 11001 10101 10011 10001 10001 10001",
    'O' to "01110 10001 10001 10001 10001 10001 01110",
    'P' to "11110 10001 10001 11110 10000 10000 10000",
    'Q' to "01110 10001 10001 10001 10101 10010 01101",
    'R' to "11110 10001 10001 11110 10100 10010 10001",
    'S' to "01111 10000 10000 01110 00001 00001 11110",
    'T' to "11111 00100 00100 00100 00100 00100 00100",
    'U' to "10001 10001 10001 10001 10001 10001 01110",
    'V' to "10001 10001 10001 10001 10001 01010 00100",
    'W' to "10001 10001 10001 10101 10101 10101 01010",
    'X' to "10001 10001 01010 00100 01010 10001 10001",
    'Y' to "10001 10001 01010 00100 00100 00100 00100",
    'Z' to "11111 00001 00010 00100 01000 10000 11111",
    '0' to "01110 10001 10011 10101 11001 10001 01110",
    '1' to "00100 01100 00100 00100 00100 00100 01110",
    '2' to "01110 10001 00001 00010 00100 01000 11111",
    '3' to "11110 00001 00001 01110 00001 00001 11110",
    '4' to "00010 00110 01010 10010 11111 00010 00010",
    '5' to "11111 10000 11110 00001 00001 10001 01110",
    '6' to "00110 01000 10000 11110 10001 10001 01110",
    '7' to "11111 00001 00010 00100 01000 01000 01000",
    '8' to "01110 10001 10001 01110 10001 10001 01110",
    '9' to "01110 10001 10001 01111 00001 00010 01100",
    '/' to "00001 00001 00010 00100 01000 10000 10000",
    '-' to "00000 00000 00000 01110 00000 00000 00000",
    '.' to "00000 00000 00000 00000 00000 00000 00100",
    ' ' to "00000 00000 00000 00000 00000 00000 00000",
).mapValues { (_, v) -> v.split(" ").map { it.toInt(2) } }

/** Text drawn as a 5x7 dot matrix, like the Nothing "Ndot" look. */
class DotText(
    ctx: Context,
    text: String,
    private val dot: Float,
    private val gap: Float,
    color: Int,
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    var text: String = text
        set(v) { field = v; requestLayout(); invalidate() }
    private val step get() = dot + gap

    override fun onMeasure(w: Int, h: Int) {
        val n = text.length
        val wPx = if (n == 0) 0f else 6f * n * step - step
        setMeasuredDimension(wPx.toInt() + 1, (7 * step).toInt() + 1)
    }

    override fun onDraw(c: Canvas) {
        var x0 = 0f
        for (ch in text.uppercase()) {
            val g = GLYPHS[ch]
            if (g != null) {
                for (r in 0 until 7) for (col in 0 until 5) {
                    if (((g[r] shr (4 - col)) and 1) == 1) {
                        c.drawCircle(x0 + col * step + dot / 2, r * step + dot / 2, dot / 2, paint)
                    }
                }
            }
            x0 += 6 * step
        }
    }
}

/** One dot per test: solid = ok, red = fail, ring = skipped, dim = not tested yet. */
class DotProgress(
    ctx: Context,
    private val n: Int,
    private val on: Int,
    private val off: Int,
    private val red: Int,
) : View(ctx) {
    var states: IntArray = IntArray(n)
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val step = width.toFloat() / n
        val r = step * 0.28f
        ring.strokeWidth = max(1f, r * 0.35f)
        val cy = height / 2f
        for (i in 0 until n) {
            val cx = step * i + step / 2
            when (states.getOrElse(i) { 0 }) {
                1 -> { fill.color = on; c.drawCircle(cx, cy, r, fill) }
                2 -> { fill.color = red; c.drawCircle(cx, cy, r, fill) }
                3 -> { ring.color = off; c.drawCircle(cx, cy, r * 0.85f, ring) }
                else -> { fill.color = (0x55 shl 24) or (off and 0x00FFFFFF); c.drawCircle(cx, cy, r, fill) }
            }
        }
    }
}
