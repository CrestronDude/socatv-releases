package com.socatv.nova.utils

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Govee LAN UDP API client.
 * Govee devices listen on port 4003 and respond on 4002.
 * Protocol: send JSON commands as UTF-8 UDP packets.
 */
object GoveeClient {

    private const val TAG = "GoveeClient"
    private const val GOVEE_PORT = 4003
    private const val TIMEOUT_MS = 2000

    suspend fun setColor(ip: String, color: Int) {
        if (ip.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                sendCommand(ip, buildColorCommand(r, g, b))
            } catch (e: Exception) {
                Log.e(TAG, "setColor failed for ip=$ip", e)
            }
        }
    }

    suspend fun setBrightness(ip: String, brightness: Int) {
        if (ip.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val pct = brightness.coerceIn(0, 100)
                sendCommand(ip, buildBrightnessCommand(pct))
            } catch (e: Exception) {
                Log.e(TAG, "setBrightness failed", e)
            }
        }
    }

    suspend fun turnOff(ip: String) {
        if (ip.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                sendCommand(ip, buildPowerCommand(false))
            } catch (e: Exception) {
                Log.e(TAG, "turnOff failed", e)
            }
        }
    }

    suspend fun turnOn(ip: String) {
        if (ip.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                sendCommand(ip, buildPowerCommand(true))
            } catch (e: Exception) {
                Log.e(TAG, "turnOn failed", e)
            }
        }
    }

    private suspend fun sendCommand(ip: String, json: String) {
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = TIMEOUT_MS
                val address = InetAddress.getByName(ip)
                val bytes = json.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, address, GOVEE_PORT)
                socket.send(packet)
                Log.d(TAG, "Sent to $ip: $json")
            } finally {
                socket?.close()
            }
        }
    }

    private fun buildColorCommand(r: Int, g: Int, b: Int): String {
        return JSONObject().apply {
            put("msg", JSONObject().apply {
                put("cmd", "colorwc")
                put("data", JSONObject().apply {
                    put("color", JSONObject().apply {
                        put("r", r)
                        put("g", g)
                        put("b", b)
                    })
                    put("colorTemInKelvin", 0)
                })
            })
        }.toString()
    }

    private fun buildBrightnessCommand(pct: Int): String {
        return JSONObject().apply {
            put("msg", JSONObject().apply {
                put("cmd", "brightness")
                put("data", JSONObject().apply {
                    put("value", pct)
                })
            })
        }.toString()
    }

    private fun buildPowerCommand(on: Boolean): String {
        return JSONObject().apply {
            put("msg", JSONObject().apply {
                put("cmd", "turn")
                put("data", JSONObject().apply {
                    put("value", if (on) 1 else 0)
                })
            })
        }.toString()
    }
}
