package com.byte2bites.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.byte2bites.app.databinding.ActivityVoipCallBinding
import com.google.firebase.auth.FirebaseAuth
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class VoipCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_IP = "EXTRA_REMOTE_IP"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_CALLEE_UID = "EXTRA_CALLEE_UID"

        private const val SIGNALING_PORT = 6000  // TCP signaling port
    }

    private lateinit var b: ActivityVoipCallBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var audioEngine: VoipAudioEngine? = null
    private val REQ_RECORD_AUDIO = 2001

    // ringing tone
    private var ringtone: Ringtone? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // TCP signaling
    private var signalingSocket: Socket? = null
    private var signalingThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoipCallBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }

        b.btnStartCall.setOnClickListener { startCallWithPermissionCheck() }
        b.btnEndCall.setOnClickListener { endCallWithConfirm() }

        val remoteIp = intent.getStringExtra(EXTRA_REMOTE_IP)
        val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, -1)
        val calleeUidExtra = intent.getStringExtra(EXTRA_CALLEE_UID)

        if (!remoteIp.isNullOrEmpty()) {
            b.etIpAddress.setText(remoteIp)
        }
        if (remotePort > 0) {
            b.etPort.setText(remotePort.toString())
        }
        if (!calleeUidExtra.isNullOrEmpty()) {
            b.etCalleeUid.setText(calleeUidExtra)
        }
    }

    // ===== permissions =====

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCallWithPermissionCheck() {
        if (hasMicPermission()) {
            startCall()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQ_RECORD_AUDIO
                )
            } else {
                Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startCall()
            } else {
                Toast.makeText(this, "Microphone permission denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ===== call + audio + signaling =====

    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {
            Toast.makeText(this, "You must be logged in to start a call.", Toast.LENGTH_LONG).show()
            return
        }

        val calleeUid = b.etCalleeUid.text.toString().trim()
        val ip = b.etIpAddress.text.toString().trim()    // server IP
        val portStr = b.etPort.text.toString().trim()

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "Please provide IP and port.", Toast.LENGTH_LONG).show()
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(this, "Please enter a valid port.", Toast.LENGTH_LONG).show()
            return
        }

        stopAudio()
        stopSignaling()

        val who = if (calleeUid.isNotEmpty()) calleeUid else "other side"
        b.tvCallStatus.text = "Calling $who..."

        startRingingTone()
        uiHandler.postDelayed({
            stopRingingTone()
            b.tvCallStatus.text = "Call in progress ($ip:$port)"

            // TCP signaling
            startSignaling(ip, port)

            // UDP audio through proxy
            startAudio(ip, port)
        }, 1500L)
    }

    private fun startAudio(remoteIp: String, port: Int) {
        if (!hasMicPermission()) {
            Toast.makeText(this, "Microphone permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        stopAudio()

        audioEngine = VoipAudioEngine(
            remoteIp = remoteIp,   // server IP
            remotePort = port,     // UDP proxy port (e.g. 5000)
            localPort = port       // same port locally
        ).also { engine ->
            try {
                engine.start()
                Toast.makeText(
                    this,
                    "Audio started (UDP $remoteIp:$port via proxy)",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (se: SecurityException) {
                Toast.makeText(
                    this,
                    "Mic permission error: ${se.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun stopAudio() {
        audioEngine?.stop()
        audioEngine = null
    }

    // ===== TCP signaling =====

    private fun startSignaling(serverIp: String, udpPort: Int) {
        stopSignaling()

        signalingThread = Thread {
            try {
                val socket = Socket(serverIp, SIGNALING_PORT)
                signalingSocket = socket

                val out = PrintWriter(socket.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))

                val uid = auth.currentUser?.uid ?: "unknown"

                out.println("HELLO role=buyer uid=$uid udpPort=$udpPort")
                out.println("CALL_INIT from=$uid")

                // fixed: no uninitialized var
                while (!socket.isClosed) {
                    val line = input.readLine() ?: break
                    runOnUiThread {
                        b.tvCallStatus.text = "Signaling: $line"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { signalingSocket?.close() } catch (_: Exception) {}
                signalingSocket = null
            }
        }.apply { start() }
    }

    private fun stopSignaling() {
        try { signalingSocket?.close() } catch (_: Exception) {}
        signalingSocket = null
        signalingThread = null
    }

    // ===== ringing tone =====

    private fun startRingingTone() {
        if (ringtone == null) {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
        }
        try { ringtone?.play() } catch (_: Exception) {}
    }

    private fun stopRingingTone() {
        try { ringtone?.stop() } catch (_: Exception) {}
    }

    // ===== end call =====

    private fun endCallWithConfirm() {
        AlertDialog.Builder(this)
            .setTitle("End call")
            .setMessage("Are you sure you want to end this call?")
            .setPositiveButton("End") { _, _ -> endCall() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun endCall() {
        stopRingingTone()
        stopAudio()
        stopSignaling()
        b.tvCallStatus.text = "Call ended."
        Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        stopRingingTone()
        stopAudio()
        stopSignaling()
    }
}
