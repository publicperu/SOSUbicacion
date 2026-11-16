package com.example.sosubicacion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var phoneInput: EditText
    private lateinit var status: TextView
    private lateinit var sosButton: Button
    private val prefs by lazy { getSharedPreferences("sos", MODE_PRIVATE) }

    private val perms = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        root.addView(TextView(this).apply {
            text = "Número de emergencia"
            textSize = 16f
        })

        phoneInput = EditText(this).apply {
            hint = "Ej: +51987654321"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(prefs.getString("phone", ""))
        }
        root.addView(phoneInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        sosButton = Button(this).apply {
            text = "SOS"
            textSize = 48f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setOnClickListener { onSosPressed() }
        }
        root.addView(sosButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (260 * dp).toInt()).apply {
            topMargin = (32 * dp).toInt()
        })

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, 0)
        }
        root.addView(status)

        setContentView(root)
    }

    private fun onSosPressed() {
        val phone = phoneInput.text.toString().trim()
        if (phone.length < 6) {
            status.text = "Ingresa un número válido."
            return
        }
        prefs.edit().putString("phone", phone).apply()

        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(perms, 1)
        } else {
            sendSos(phone)
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        val smsOk = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val locOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (smsOk && locOk) {
            sendSos(prefs.getString("phone", "") ?: "")
        } else {
            status.text = "Se necesitan los permisos de SMS y ubicación. Actívalos en Ajustes."
        }
    }

    private fun sendSos(phone: String) {
        status.text = "Obteniendo ubicación..."
        sosButton.isEnabled = false
        getLocation { loc ->
            sosButton.isEnabled = true
            val msg = if (loc != null) {
                "¡EMERGENCIA! Necesito ayuda. Mi ubicación: " +
                        "https://maps.google.com/?q=${loc.latitude},${loc.longitude}" +
                        " (precisión ~${loc.accuracy.toInt()} m)"
            } else {
                "¡EMERGENCIA! Necesito ayuda. No pude obtener mi ubicación exacta."
            }
            try {
                val sms = if (Build.VERSION.SDK_INT >= 31)
                    getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendMultipartTextMessage(phone, null, sms.divideMessage(msg), null, null)
                status.text = if (loc != null) "✔ Alerta enviada a $phone"
                else "⚠ Alerta enviada a $phone SIN ubicación (activa el GPS)"
            } catch (e: Exception) {
                status.text = "Error al enviar: ${e.message}"
            }
        }
    }

    @Suppress("MissingPermission")
    private fun getLocation(done: (Location?) -> Unit) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }

        fun lastKnown(): Location? = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (providers.isEmpty()) { done(lastKnown()); return }

        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            Handler(Looper.getMainLooper()).postDelayed({ signal.cancel() }, 15000)
            lm.getCurrentLocation(providers.first(), signal, mainExecutor) { loc ->
                done(loc ?: lastKnown())
            }
        } else {
            done(lastKnown())
        }
    }
}
