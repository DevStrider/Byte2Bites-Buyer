package com.byte2bites.app

/**
 * Simple data class representing a VoIP call signaling object.
 *
 * This is stored under: /VoipCalls/{callId}
 *
 * status can be:
 *  - "INITIATED"   : caller created the call
 *  - "RINGING"     : callee is being notified
 *  - "CONNECTED"   : both sides agreed to connect
 *  - "ENDED"       : call finished / hung up
 */
data class VoipCall(
    val callId: String = "",
    val callerUid: String = "",
    val calleeUid: String = "",
    val ipAddress: String = "",
    val port: Int = 0,
    val status: String = "INITIATED",
    val timestamp: Long = 0L
)
