package com.byte2bites.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityVoipCallBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class VoipCallActivity : AppCompatActivity() {

    private lateinit var b: ActivityVoipCallBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private var currentCallId: String? = null
    private var callListener: ValueEventListener? = null
    private var callRef: DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoipCallBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Back button
        b.ivBack.setOnClickListener { finish() }

        b.btnStartCall.setOnClickListener { startCall() }
        b.btnEndCall.setOnClickListener { endCallWithConfirm() }
    }

    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {
            Toast.makeText(this, "You must be logged in to start a call.", Toast.LENGTH_LONG).show()
            return
        }

        val calleeUid = b.etCalleeUid.text.toString().trim()
        val ip = b.etIpAddress.text.toString().trim()
        val portStr = b.etPort.text.toString().trim()

        if (calleeUid.isEmpty() || ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "Please fill all fields.", Toast.LENGTH_LONG).show()
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(this, "Please enter a valid port.", Toast.LENGTH_LONG).show()
            return
        }

        // If a previous call is active, end it first
        if (currentCallId != null) {
            Toast.makeText(this, "Ending previous call and starting a new one.", Toast.LENGTH_SHORT).show()
            endCall(false)
        }

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
                Toast.makeText(this, "Call created. Share the call ID or wait for callee to respond.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                currentCallId = null
                callRef = null
                Toast.makeText(this, "Failed to start call: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Attaches a listener to the current call reference.
     * Any side (buyer/seller) can update /VoipCalls/{callId}/status to:
     *  - "RINGING"
     *  - "CONNECTED"
     *  - "ENDED"
     */
    private fun attachCallListener() {
        val ref = callRef ?: return

        // Remove previous listener if any
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
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@VoipCallActivity, "Call listener cancelled: ${error.message}", Toast.LENGTH_LONG).show()
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

    /**
     * Set status to ENDED and optionally show a toast.
     * doesNot delete the node so the call history stays in DB.
     */
    private fun endCall(showToast: Boolean) {
        val ref = callRef
        val id = currentCallId

        if (ref == null || id == null) {
            if (showToast) {
                Toast.makeText(this, "No active call.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        ref.child("status").setValue("ENDED")
            .addOnCompleteListener {
                if (showToast) {
                    Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up listener
        callRef?.let { ref ->
            callListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
    }
}
