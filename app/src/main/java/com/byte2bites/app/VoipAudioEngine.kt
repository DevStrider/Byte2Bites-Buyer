package com.byte2bites.app

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Symmetric UDP audio engine:
 * - One DatagramSocket(localPort) used for both send & receive.
 * - Sends audio to remoteIp:remotePort (proxy server).
 * - Receives audio on localPort (via proxy).
 */
class VoipAudioEngine(
    private val remoteIp: String,   // Proxy server IP
    private val remotePort: Int,    // Proxy UDP port (e.g. 5000)
    private val localPort: Int      // Local UDP port (same as remotePort)
) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var running = false

    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var udpSocket: DatagramSocket? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true

        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufferSize = minOf(minRecord, minTrack).coerceAtLeast(2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_IN,
            AUDIO_FORMAT,
            bufferSize
        )

        audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE,
            CHANNEL_OUT,
            AUDIO_FORMAT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        // single UDP socket for send+receive
        udpSocket = DatagramSocket(localPort)
        val socket = udpSocket!!
        val remoteAddress = InetAddress.getByName(remoteIp)

        // ---- SEND ----
        recordThread = Thread {
            val buf = ByteArray(bufferSize)
            try {
                audioRecord?.startRecording()
                while (running) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (read > 0) {
                        val packet = DatagramPacket(buf, read, remoteAddress, remotePort)
                        try {
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                }
                audioRecord?.stop()
            } catch (_: Exception) {}
        }.also { it.start() }

        // ---- RECEIVE ----
        playThread = Thread {
            val buf = ByteArray(bufferSize)
            try {
                audioTrack?.play()
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        audioTrack?.write(packet.data, 0, packet.length)
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
                audioTrack?.stop()
            } catch (_: Exception) {}
        }.also { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false

        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null

        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        recordThread = null
        playThread = null
    }
}
