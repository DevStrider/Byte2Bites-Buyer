package com.byte2bites.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.byte2bites.app.databinding.ActivityVoipCallBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class VoipCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_IP = "EXTRA_REMOTE_IP"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_CALLEE_UID = "EXTRA_CALLEE_UID"
    }

    private lateinit var b: ActivityVoipCallBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private var currentCallId: String? = null
    private var callListener: ValueEventListener? = null
    private var callRef: DatabaseReference? = null

    private var audioEngine: VoipAudioEngine? = null
    private val REQ_RECORD_AUDIO = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoipCallBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Back
        b.ivBack.setOnClickListener { finish() }

        // Manual start
        b.btnStartCall.setOnClickListener { startCallWithPermissionCheck() }
        b.btnEndCall.setOnClickListener { endCallWithConfirm() }

        // If launched with extras (e.g. from OrdersActivity), auto-fill
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

        // If we have enough info, auto-start
        if (!remoteIp.isNullOrEmpty() && remotePort > 0) {
            startCallWithPermissionCheck()
        }
    }

    // ===== PERMISSIONS =====

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

    // ===== CALL + AUDIO LOGIC =====

    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {
            Toast.makeText(this, "You must be logged in to start a call.", Toast.LENGTH_LONG).show()
            return
        }

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

        // If a previous call is active, end it
        if (currentCallId != null) {
            endCall(false)
        }
        stopAudio()

        val callsRef = db.reference.child("VoipCalls")
        val callId = callsRef.push().key ?: System.currentTimeMillis().toString()
        val ts = System.currentTimeMillis()

        val voipCall = VoipCall(
            callId = callId,
            callerUid = callerUid,
            calleeUid = calleeUid,
            ipAddress = ip,
            port = port,
            status = "INITIATED",
            timestamp = ts
        )

        currentCallId = callId
        callRef = callsRef.child(callId)

        callRef!!.setValue(voipCall)
            .addOnSuccessListener {
                b.tvCallStatus.text = "Status: INITIATED (waiting for other side)"
                attachCallListener()

                // 🔊 Start audio stream (UDP)
                startAudio(ip, port)

                Toast.makeText(
                    this,
                    "Call created. Other side must also start their audio.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener { e ->
                currentCallId = null
                callRef = null
                Toast.makeText(this, "Failed to start call: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Start capturing mic audio and sending via UDP.
     * We **re-check** RECORD_AUDIO permission here to satisfy Lint and avoid crashes.
     */
    private fun startAudio(remoteIp: String, port: Int) {
        if (!hasMicPermission()) {
            Toast.makeText(this, "Microphone permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        stopAudio()

        audioEngine = VoipAudioEngine(
            remoteIp = remoteIp,
            remotePort = port,
            localPort = port   // symmetric; both sides use same port
        ).also { engine ->
            try {
                engine.start()   // this is the call that requires RECORD_AUDIO
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

    private fun stopAudio() {
        audioEngine?.stop()
        audioEngine = null
    }

    private fun attachCallListener() {
        val ref = callRef ?: return

        callListener?.let { ref.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val call = snapshot.getValue(VoipCall::class.java)
                if (call == null) {
                    b.tvCallStatus.text = "Status: Call deleted"
                    return
                }
                val statusText = when (call.status) {
                    "INITIATED" -> "INITIATED (waiting for other side)"
                    "RINGING" -> "RINGING (callee is being alerted)"
                    "CONNECTED" -> "CONNECTED (call in progress)"
                    "ENDED" -> "ENDED"
                    else -> call.status
                }
                b.tvCallStatus.text = "Status: $statusText"

                if (call.status == "ENDED") {
                    stopAudio()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@VoipCallActivity,
                    "Call listener cancelled: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        ref.addValueEventListener(listener)
        callListener = listener
    }

    private fun endCallWithConfirm() {
        if (currentCallId == null) {
            Toast.makeText(this, "No active call to end.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("End call")
            .setMessage("Are you sure you want to end this call?")
            .setPositiveButton("End") { _, _ ->
                endCall(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun endCall(showToast: Boolean) {
        val ref = callRef
        val id = currentCallId

        if (ref == null || id == null) {
            if (showToast) {
                Toast.makeText(this, "No active call.", Toast.LENGTH_SHORT).show()
            }
            stopAudio()
            return
        }

        ref.child("status").setValue("ENDED")
            .addOnCompleteListener {
                stopAudio()
                if (showToast) {
                    Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        callRef?.let { ref ->
            callListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
        stopAudio()
    }
}
