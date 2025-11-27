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

/**
 * Activity representing a point-to-point VoIP call screen.
 *
 * Features:
 * - User manually enters IP/port (or receives them via Intent extras).
 * - On "Start Call", plays a local ringing tone then starts two-way UDP audio using VoipAudioEngine.
 * - Enforces RECORD_AUDIO runtime permission.
 * - Provides a "Call status" text label for user feedback.
 *
 * NOTE:
 * This implementation focuses on the audio path and basic UX,
 * without creating any call logs in Firebase (signaling is local only here).
 */
class VoipCallActivity : AppCompatActivity() {

    companion object {
        // Intent extras keys used for passing default remote IP/port and callee id.
        const val EXTRA_REMOTE_IP = "EXTRA_REMOTE_IP"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_CALLEE_UID = "EXTRA_CALLEE_UID"
    }

    private lateinit var b: ActivityVoipCallBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Engine that handles the raw UDP audio sending/receiving.
    private var audioEngine: VoipAudioEngine? = null
    private val REQ_RECORD_AUDIO = 2001

    // Simple local “ringing” tone before call is connected.
    private var ringtone: Ringtone? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoipCallBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Back arrow finishes the activity.
        b.ivBack.setOnClickListener { finish() }

        // Manual start / end buttons.
        b.btnStartCall.setOnClickListener { startCallWithPermissionCheck() }
        b.btnEndCall.setOnClickListener { endCallWithConfirm() }

        // --- Autofill from Intent (OrdersActivity, etc.) ---
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
        // ---------------------------------------------------
    }

    // ===== PERMISSIONS =====

    /**
     * Checks whether the RECORD_AUDIO runtime permission is already granted.
     */
    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Ensures microphone permission is granted before starting the call.
     * - If granted: proceeds to startCall().
     * - If not: asks the user via requestPermissions.
     */
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

    /**
     * Callback from Android runtime permission system.
     * When the microphone permission is granted we continue with startCall().
     */
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

    // ===== CALL + AUDIO LOGIC (NO FIREBASE CALL LOGS) =====

    /**
     * Validates the call inputs (IP, port), ensures a logged-in user,
     * and then starts ringing + VoipAudioEngine after a short delay.
     */
    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {
            Toast.makeText(this, "You must be logged in to start a call.", Toast.LENGTH_LONG).show()
            return
        }

        // Callee UID is optional; used only for display purposes.
        val calleeUid = b.etCalleeUid.text.toString().trim()
        val ip = b.etIpAddress.text.toString().trim()
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

        // Stop any previous call/audio session before starting a new one.
        stopAudio()

        // Update status text with a friendly message.
        val who = if (calleeUid.isNotEmpty()) calleeUid else "other side"
        b.tvCallStatus.text = "Calling $who..."

        // Play a simple ringing tone for ~1.5s, then start audio.
        startRingingTone()
        uiHandler.postDelayed({
            stopRingingTone()
            b.tvCallStatus.text = "Call in progress ($ip:$port)"
            startAudio(ip, port)
        }, 1500L)
    }

    /**
     * Starts the underlying VoipAudioEngine with the provided IP and port.
     * We re-check permission here as a safety net.
     */
    private fun startAudio(remoteIp: String, port: Int) {
        if (!hasMicPermission()) {
            Toast.makeText(this, "Microphone permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        // Ensure no previous engine is running.
        stopAudio()

        // Symmetric audio: same port is used for sending and receiving.
        audioEngine = VoipAudioEngine(
            remoteIp = remoteIp,
            remotePort = port,
            localPort = port   // symmetric; both sides use the same port
        ).also { engine ->
            try {
                engine.start()   // this call requires RECORD_AUDIO
                Toast.makeText(
                    this,
                    "Audio started (UDP $remoteIp:$port)",
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

    /**
     * Stops the VoipAudioEngine, if any, and clears the reference.
     */
    private fun stopAudio() {
        audioEngine?.stop()
        audioEngine = null
    }

    // ===== RINGING TONE (LOCAL ONLY) =====

    /**
     * Plays the default system ringtone to simulate a ringing state.
     */
    private fun startRingingTone() {
        if (ringtone == null) {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
        }
        try {
            ringtone?.play()
        } catch (_: Exception) { }
    }

    /**
     * Stops the ringing sound if it is currently playing.
     */
    private fun stopRingingTone() {
        try {
            ringtone?.stop()
        } catch (_: Exception) { }
    }

    // ===== END CALL =====

    /**
     * Shows a confirmation dialog to avoid accidentally ending the call.
     */
    private fun endCallWithConfirm() {
        AlertDialog.Builder(this)
            .setTitle("End call")
            .setMessage("Are you sure you want to end this call?")
            .setPositiveButton("End") { _, _ ->
                endCall()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Ends the call:
     * - Stops ringing and audio engine.
     * - Updates the call status label.
     */
    private fun endCall() {
        stopRingingTone()
        stopAudio()
        b.tvCallStatus.text = "Call ended."
        Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure no delayed callbacks or audio continue after screen is closed.
        uiHandler.removeCallbacksAndMessages(null)
        stopRingingTone()
        stopAudio()
    }
}
