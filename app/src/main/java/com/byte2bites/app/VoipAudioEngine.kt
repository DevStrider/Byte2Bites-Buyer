package com.byte2bites.app

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Very simple bidirectional audio over UDP.
 *
 * This class encapsulates the low-level audio + networking logic:
 * - Uses AudioRecord to capture microphone audio.
 * - Sends raw PCM audio frames over UDP to the remote endpoint.
 * - Listens for incoming UDP packets and plays them via AudioTrack.
 *
 * Usage:
 * - Both peers must agree on IP and ports (remotePort/localPort).
 * - For a basic symmetric call, both sides can use the same port value.
 *
 * NOTE:
 * - This is intentionally minimal to match the course's VoIP requirement.
 * - No codecs, encryption, NAT traversal, jitter buffers, or packet loss handling.
 *
 * @param remoteIp   IP address of the other side (destination for outgoing packets).
 * @param remotePort UDP port of the other side.
 * @param localPort  Local UDP port to bind for receiving incoming audio.
 */
class VoipAudioEngine(
    private val remoteIp: String,
    private val remotePort: Int,
    private val localPort: Int
) {

    companion object {
        // Sample rate tuned for narrowband speech; helps keep bandwidth low.
        private const val SAMPLE_RATE = 8000          // Hz (low but OK for speech)
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    // Shared flag controlling both sender and receiver threads.
    private val running = AtomicBoolean(false)

    // Background threads for mic capture and speaker playback.
    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    /**
     * Start capturing mic audio and sending it via UDP,
     * and also start listening on [localPort] and playing to speaker.
     *
     * Caller MUST ensure android.permission.RECORD_AUDIO is granted.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        // If already running, ignore duplicate calls.
        if (running.getAndSet(true)) return

        // ---- Sender (mic -> UDP) ----
        recordThread = Thread {
            // Determine the minimum buffer size required for the given configuration.
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            if (minBuf <= 0) {
                running.set(false)
                return@Thread
            }

            // Create AudioRecord for microphone capture.
            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    minBuf
                )
            } catch (se: SecurityException) {
                // No permission at runtime -> stop engine gracefully.
                running.set(false)
                return@Thread
            } catch (e: IllegalArgumentException) {
                // Device might not support given configuration.
                running.set(false)
                return@Thread
            }

            // UDP socket to send packets to the remote endpoint.
            val socket = DatagramSocket()
            val targetAddr = InetAddress.getByName(remoteIp)
            val buf = ByteArray(minBuf)

            try {
                audioRecord.startRecording()

                // Capture loop: read PCM data and send via UDP as long as running == true.
                while (running.get()) {
                    val read = audioRecord.read(buf, 0, buf.size)
                    if (read > 0) {
                        val packet = DatagramPacket(buf, read, targetAddr, remotePort)
                        socket.send(packet)
                    }
                }
            } catch (_: Exception) {
                // Swallow exceptions; engine will shutdown in finally block.
            } finally {
                try { audioRecord.stop() } catch (_: Exception) {}
                audioRecord.release()
                socket.close()
            }
        }.apply { start() }

        // ---- Receiver (UDP -> speaker) ----
        playThread = Thread {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            if (minBuf <= 0) {
                running.set(false)
                return@Thread
            }

            // AudioTrack configured for voice call output.
            val audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING,
                minBuf,
                AudioTrack.MODE_STREAM
            )

            // UDP socket bound to localPort for receiving packets from remote side.
            val socket = DatagramSocket(localPort)
            val buf = ByteArray(minBuf)

            try {
                audioTrack.play()
                // Receive loop: blocks on socket.receive and writes data to audioTrack.
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    audioTrack.write(packet.data, 0, packet.length)
                }
            } catch (_: Exception) {
                // Network errors or stop() interrupt will drop here.
            } finally {
                try { audioTrack.stop() } catch (_: Exception) {}
                audioTrack.release()
                socket.close()
            }
        }.apply { start() }
    }

    /**
     * Stops sending and receiving audio and releases the underlying resources.
     * Safe to call multiple times.
     */
    fun stop() {
        if (!running.getAndSet(false)) return

        // Interrupt both threads; they will exit their loops quickly.
        try { recordThread?.interrupt() } catch (_: Exception) {}
        try { playThread?.interrupt() } catch (_: Exception) {}

        recordThread = null
        playThread = null
    }
}
