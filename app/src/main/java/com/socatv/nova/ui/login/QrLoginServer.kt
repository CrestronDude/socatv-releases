package com.socatv.nova.ui.login

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Minimal single-connection HTTP server running on a background thread.
 * Serves a mobile login page; the phone submits credentials via POST.
 * Notifies via callback on the calling coroutine dispatcher.
 */
class QrLoginServer(
    private val onCredentials: (username: String, password: String) -> Unit
) {
    private val TAG = "QrLoginServer"
    var port: Int = 0
        private set
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            val ss = ServerSocket(0)  // OS picks a free port
            serverSocket = ss
            port = ss.localPort
            running = true
            Log.d(TAG, "QR login server on port $port")

            while (running) {
                try {
                    val client = ss.accept()
                    client.use { socket ->
                        val br = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val pw = PrintWriter(socket.getOutputStream(), true)

                        val firstLine = br.readLine() ?: return@use
                        val isPost = firstLine.startsWith("POST")

                        // Read headers
                        var contentLength = 0
                        var line = br.readLine()
                        while (!line.isNullOrEmpty()) {
                            if (line.lowercase().startsWith("content-length:")) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                            line = br.readLine()
                        }

                        if (isPost && contentLength > 0) {
                            // Read body
                            val bodyChars = CharArray(contentLength)
                            br.read(bodyChars, 0, contentLength)
                            val body = String(bodyChars)
                            val params = parseForm(body)
                            val user = params["username"] ?: ""
                            val pass = params["password"] ?: ""

                            // Respond with a nice "Processing" page
                            val html = successHtml()
                            sendHtml(pw, html, 200)

                            if (user.isNotBlank() && pass.isNotBlank()) {
                                running = false
                                onCredentials(user, pass)
                            }
                        } else {
                            // Serve the login form
                            sendHtml(pw, loginHtml(), 200)
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Client error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun parseForm(body: String): Map<String, String> {
        return body.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()
    }

    private fun sendHtml(pw: PrintWriter, html: String, code: Int) {
        val status = if (code == 200) "200 OK" else "400 Bad Request"
        pw.print("HTTP/1.1 $status\r\n")
        pw.print("Content-Type: text/html; charset=UTF-8\r\n")
        pw.print("Content-Length: ${html.toByteArray().size}\r\n")
        pw.print("Connection: close\r\n")
        pw.print("\r\n")
        pw.print(html)
        pw.flush()
    }

    private fun loginHtml() = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Soca TV Nova — Sign In</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    min-height: 100vh;
    background: linear-gradient(135deg, #080810 0%, #0a0a1a 50%, #080818 100%);
    display: flex; align-items: center; justify-content: center;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  }
  .card {
    background: rgba(20,20,34,0.95);
    border: 1px solid rgba(0,220,255,0.25);
    border-radius: 16px;
    padding: 40px 36px;
    width: 100%; max-width: 380px;
    box-shadow: 0 0 60px rgba(0,220,255,0.08);
  }
  h1 {
    color: #00DCFF;
    font-size: 22px;
    font-weight: 300;
    letter-spacing: 3px;
    text-align: center;
    margin-bottom: 6px;
  }
  p.sub {
    color: rgba(255,255,255,0.45);
    text-align: center;
    font-size: 13px;
    margin-bottom: 32px;
  }
  label {
    display: block;
    color: rgba(255,255,255,0.55);
    font-size: 11px;
    letter-spacing: 1.5px;
    text-transform: uppercase;
    margin-bottom: 6px;
  }
  input[type=text], input[type=password] {
    width: 100%;
    background: rgba(255,255,255,0.06);
    border: 1px solid rgba(255,255,255,0.12);
    border-radius: 8px;
    color: #fff;
    font-size: 15px;
    padding: 12px 14px;
    margin-bottom: 20px;
    outline: none;
    transition: border-color .2s;
  }
  input:focus { border-color: #00DCFF; }
  button {
    width: 100%;
    background: #00DCFF;
    border: none;
    border-radius: 8px;
    color: #080810;
    font-size: 16px;
    font-weight: 700;
    padding: 14px;
    cursor: pointer;
    letter-spacing: 1px;
    margin-top: 4px;
    transition: opacity .2s;
  }
  button:active { opacity: 0.8; }
  .hint {
    color: rgba(255,255,255,0.3);
    font-size: 11px;
    text-align: center;
    margin-top: 16px;
  }
</style>
</head>
<body>
  <div class="card">
    <h1>SOCA TV NOVA</h1>
    <p class="sub">Sign in from your phone</p>
    <form method="POST" action="/">
      <label for="u">Username</label>
      <input type="text" id="u" name="username" autocomplete="username" autofocus placeholder="Enter username">
      <label for="p">Password</label>
      <input type="password" id="p" name="password" autocomplete="current-password" placeholder="Enter password">
      <button type="submit">Connect to TV</button>
    </form>
    <p class="hint">Your TV will log in automatically</p>
  </div>
</body>
</html>
""".trimIndent()

    private fun successHtml() = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Soca TV Nova — Connected!</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    min-height: 100vh;
    background: linear-gradient(135deg, #080810 0%, #0a0a1a 100%);
    display: flex; align-items: center; justify-content: center;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  }
  .card {
    background: rgba(20,20,34,0.95);
    border: 1px solid rgba(0,220,110,0.35);
    border-radius: 16px;
    padding: 48px 36px;
    width: 100%; max-width: 340px;
    text-align: center;
  }
  .check { font-size: 56px; margin-bottom: 16px; }
  h1 { color: #00DC6E; font-size: 22px; font-weight: 600; margin-bottom: 8px; }
  p { color: rgba(255,255,255,0.5); font-size: 14px; }
</style>
</head>
<body>
  <div class="card">
    <div class="check">✓</div>
    <h1>Connected!</h1>
    <p>Your TV is now logging in. You can close this page.</p>
  </div>
</body>
</html>
""".trimIndent()
}
