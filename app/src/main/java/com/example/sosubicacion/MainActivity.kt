package com.example.sosubicacion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

class MainActivity : Activity() {

    // Números de emergencia con código de país (+51 = Perú).
    // Para agregar o cambiar números, edita esta lista (uno entre comillas, separados por coma).
    private val allNumbers = listOf(
        "+51989957003",
        "+51904321233"   // <-- REEMPLAZA por el segundo número (ej. "+51987654321")
    )
    // Solo se usan los que tengan formato de número válido
    private val emergencyNumbers = allNumbers.filter { it.matches(Regex("^\\+?\\d{6,15}$")) }
    private val alertText =
        "🚨 Auxilio. Me encuentro en esta ubicación y necesito ayuda urgente. " +
                "Por favor, contacten conmigo de inmediato."

    private val REQ_GPS = 2001
    private lateinit var status: TextView
    private lateinit var sosButton: ImageButton

    private var gpsCallback: ((Boolean) -> Unit)? = null
    private var askedOnStart = false
    private var pendingSend = false

    private val perms = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        fun pill(tv: TextView) {
            tv.setTextColor(Color.parseColor("#222222"))
            tv.gravity = Gravity.CENTER
            tv.setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            tv.background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9FFFFFF"))
                cornerRadius = 20 * dp
            }
        }

        val frame = FrameLayout(this)

        // Fondo
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.fondo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        val title = TextView(this).apply {
            text = "Alerta SOS por SMS a Hortifrut Emergencia"
            textSize = 16f
        }
        pill(title)
        content.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // Botón con el ícono "buscar"
        sosButton = ImageButton(this).apply {
            setImageResource(R.drawable.buscar)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            setPadding(0, 0, 0, 0)
            contentDescription = "Enviar alerta de emergencia con mi ubicación"
            setOnClickListener {
                it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                onSosPressed()
            }
        }
        content.addView(sosButton, LinearLayout.LayoutParams((280 * dp).toInt(), (280 * dp).toInt()).apply {
            topMargin = (24 * dp).toInt()
            bottomMargin = (24 * dp).toInt()
        })

        status = TextView(this).apply {
            text = "Toca el botón para enviar tu ubicación"
            textSize = 16f
        }
        pill(status)
        content.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        // Al abrir la app: pedir permisos y activar GPS
        if (!askedOnStart) {
            askedOnStart = true
            if (hasAllPerms()) ensureGps { } else requestPermissions(perms, 1)
        }
    }

    private fun hasAllPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun onSosPressed() {
        if (!hasAllPerms()) {
            pendingSend = true
            requestPermissions(perms, 1)
        } else {
            startSosFlow()
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        val smsOk = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val locOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val wantedSend = pendingSend
        pendingSend = false
        if (!smsOk || !locOk) {
            status.text = "Se necesitan los permisos de SMS y ubicación. Actívalos en Ajustes."
            return
        }
        if (wantedSend) startSosFlow() else ensureGps { }
    }

    /** Activa el GPS: muestra el diálogo del sistema "Activar ubicación" (un solo toque en Aceptar). */
    private fun ensureGps(cb: (Boolean) -> Unit) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { cb(true); return }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        val settingsReq = LocationSettingsRequest.Builder()
            .addLocationRequest(req)
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(this).checkLocationSettings(settingsReq)
            .addOnSuccessListener { cb(true) }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        gpsCallback = cb
                        e.startResolutionForResult(this, REQ_GPS)
                    } catch (ex: Exception) {
                        gpsCallback = null
                        cb(false)
                    }
                } else {
                    cb(false)
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GPS) {
            val cb = gpsCallback
            gpsCallback = null
            cb?.invoke(resultCode == RESULT_OK)
        }
    }

    private fun setSending(sending: Boolean) {
        sosButton.isEnabled = !sending
        sosButton.alpha = if (sending) 0.5f else 1f
    }

    private fun startSosFlow() {
        setSending(true)
        status.text = "Verificando GPS..."
        ensureGps { gpsOn ->
            // Aunque el GPS no se active, se envía la alerta igual (emergencia)
            sendSos(gpsOn)
        }
    }

    private fun sendSos(gpsOn: Boolean) {
        status.text = "Obteniendo ubicación..."
        getLocation { loc ->
            setSending(false)
            val msg = if (loc != null) {
                alertText + " 📍 https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            } else {
                alertText + " (No pude obtener mi ubicación exacta)"
            }
            val sms = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            var ok = 0
            var lastError = ""
            for (number in emergencyNumbers) {
                try {
                    sms.sendMultipartTextMessage(number, null, sms.divideMessage(msg), null, null)
                    ok++
                } catch (e: Exception) {
                    lastError = e.message ?: ""
                }
            }
            status.text = when {
                ok == 0 -> "Error al enviar: $lastError"
                loc != null -> "✔ Alerta enviada a Hortifrut Emergencia"
                !gpsOn -> "⚠ Enviada a $ok número(s) SIN ubicación (GPS desactivado)"
                else -> "⚠ Enviada a $ok número(s) SIN ubicación (no se pudo obtener)"
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
            Handler(Looper.getMainLooper()).postDelayed({ signal.cancel() }, 20000)
            lm.getCurrentLocation(providers.first(), signal, mainExecutor) { loc ->
                done(loc ?: lastKnown())
            }
        } else {
            done(lastKnown())
        }
    }
}
