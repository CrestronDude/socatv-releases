package com.socatv.nova.ui.login

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.ServerAutoSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

class QrLoginActivity : AppCompatActivity() {

    private lateinit var ivQr: ImageView
    private lateinit var tvUrl: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSpinner: TextView

    private var server: QrLoginServer? = null
    private var serverJob: Job? = null
    private val repo by lazy { IptvRepository(NovaApp.instance.database) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_login)

        ivQr = findViewById(R.id.ivQrCode)
        tvUrl = findViewById(R.id.tvQrUrl)
        tvStatus = findViewById(R.id.tvQrStatus)
        tvSpinner = findViewById(R.id.tvQrSpinner)

        findViewById<android.view.View>(R.id.btnQrBack).setOnClickListener { finish() }

        startQrServer()
    }

    private fun startQrServer() {
        tvStatus.text = "Starting…"
        tvSpinner.visibility = View.VISIBLE

        server = QrLoginServer { username, password ->
            handleCredentials(username, password)
        }

        serverJob = lifecycleScope.launch {
            // start() is a suspend function that blocks until done
            server?.start()
        }

        // After the server is bound, get its port and render the QR
        lifecycleScope.launch {
            // Wait briefly for the server to bind
            kotlinx.coroutines.delay(300)
            val port = server?.port ?: 0
            if (port == 0) {
                tvStatus.text = "Could not start server"
                tvSpinner.visibility = View.GONE
                return@launch
            }
            val ip = getWifiIp()
            val url = "http://$ip:$port"
            tvUrl.text = url
            tvStatus.text = "Scan with your phone"
            tvSpinner.visibility = View.GONE

            val qrBitmap = generateQr(url)
            ivQr.setImageBitmap(qrBitmap)
        }
    }

    private fun handleCredentials(username: String, password: String) {
        lifecycleScope.launch {
            runOnUiThread {
                tvStatus.text = "Signing in…"
                tvSpinner.visibility = View.VISIBLE
                ivQr.alpha = 0.3f
            }

            val result = ServerAutoSelector.findWorkingServer(repo, username, password)
            runOnUiThread {
                tvSpinner.visibility = View.GONE
                if (result != null) {
                    Prefs.serverUrl  = result.serverUrl
                    Prefs.username   = username
                    Prefs.password   = password
                    Prefs.isLoggedIn = true
                    result.response.userInfo?.let { info ->
                        Prefs.accountStatus = info.status ?: "Active"
                        Prefs.expDate       = info.expDate ?: ""
                        Prefs.putString("max_connections", info.maxConnections ?: "—")
                        Prefs.putString("active_cons",     info.activeCons ?: "—")
                        Prefs.putString("created_at",      info.createdAt ?: "—")
                        Prefs.putString("is_trial",        info.isTrial ?: "0")
                    }
                    tvStatus.text = "Logged in!"
                    startActivity(Intent(this@QrLoginActivity, PanelPickerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                } else {
                    tvStatus.text = "Login failed — try manually"
                    ivQr.alpha = 1f
                }
            }
        }
    }

    private suspend fun generateQr(content: String): Bitmap = withContext(Dispatchers.Default) {
        val size = 512
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.parseColor("#00DCFF") else Color.parseColor("#080810"))
            }
        }
        bmp
    }

    private fun getWifiIp(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val rawIp = wm.connectionInfo.ipAddress
            if (rawIp == 0) {
                // Fallback: enumerate network interfaces
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
                "0.0.0.0"
            } else {
                InetAddress.getByAddress(
                    byteArrayOf(
                        (rawIp and 0xFF).toByte(),
                        (rawIp shr 8 and 0xFF).toByte(),
                        (rawIp shr 16 and 0xFF).toByte(),
                        (rawIp shr 24 and 0xFF).toByte()
                    )
                ).hostAddress ?: "0.0.0.0"
            }
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        serverJob?.cancel()
    }
}
