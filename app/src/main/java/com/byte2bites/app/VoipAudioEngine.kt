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
 * - remoteIp/remotePort: where we SEND our microphone audio
 * - localPort          : where we LISTEN for incoming audio
 *
 * For a basic symmetric call, both sides can use the same port value.
 */
class VoipAudioEngine(
    private val remoteIp: String,
    private val remotePort: Int,
    private val localPort: Int
) {

    companion object {
        private const val SAMPLE_RATE = 8000          // Hz (low but OK for speech)
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val running = AtomicBoolean(false)

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
        if (running.getAndSet(true)) return

        // ---- Sender (mic -> UDP) ----
        recordThread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            if (minBuf <= 0) {
                running.set(false)
                return@Thread
            }

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    minBuf
                )
            } catch (se: SecurityException) {
                // No permission at runtime -> stop engine
                running.set(false)
                return@Thread
            } catch (e: IllegalArgumentException) {
                running.set(false)
                return@Thread
            }

            val socket = DatagramSocket()
            val targetAddr = InetAddress.getByName(remoteIp)
            val buf = ByteArray(minBuf)

            try {
                audioRecord.startRecording()

                while (running.get()) {
                    val read = audioRecord.read(buf, 0, buf.size)
                    if (read > 0) {
                        val packet = DatagramPacket(buf, read, targetAddr, remotePort)
                        socket.send(packet)
                    }
                }
            } catch (_: Exception) {
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

            val audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING,
                minBuf,
                AudioTrack.MODE_STREAM
            )

            val socket = DatagramSocket(localPort)
            val buf = ByteArray(minBuf)

            try {
                audioTrack.play()
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    audioTrack.write(packet.data, 0, packet.length)
                }
            } catch (_: Exception) {
            } finally {
                try { audioTrack.stop() } catch (_: Exception) {}
                audioTrack.release()
                socket.close()
            }
        }.apply { start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        try { recordThread?.interrupt() } catch (_: Exception) {}
        try { playThread?.interrupt() } catch (_: Exception) {}

        recordThread = null
        playThread = null
    }
}
