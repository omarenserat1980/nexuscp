package com.ztec.cplay.airplay

import android.util.Log
import com.ztec.cplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(session: AirPlaySession, type: Int, codec: VideoCodec) {}
    fun onVideoConfig(session: AirPlaySession, type: Int, codecData: ByteArray) {}
    fun onVideoFrame(session: AirPlaySession, type: Int, naluBytes: ByteArray) {}
    fun setVideoRecoveryHandler(session: AirPlaySession, type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(session: AirPlaySession, type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(session: AirPlaySession, type: Int, active: Boolean) {}
    /** Called before per-stream teardown so sinks can reject late callbacks permanently. */
    fun onSessionClosed(session: AirPlaySession) {}
    fun onAudioStarted(session: AirPlaySession, type: Int, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(session: AirPlaySession, type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(session: AirPlaySession, type: Int) {}
    fun onMicrophoneStarted(session: AirPlaySession, type: Int, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(session: AirPlaySession, type: Int) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
    )

    internal data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val sessionLocks = ConcurrentHashMap<AirPlaySession, Any>()
    private val closedSessions = Collections.newSetFromMap(
        Collections.synchronizedMap(WeakHashMap<AirPlaySession, Boolean>()),
    )
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    private inline fun <T> withOpenSession(
        session: AirPlaySession,
        closedValue: T,
        block: () -> T,
    ): T {
        val lock = sessionLocks.computeIfAbsent(session) { Any() }
        synchronized(lock) {
            if (session.isClosed || closedSessions.contains(session)) return closedValue
            return block()
        }
    }

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        return withOpenSession(session, null) {
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key, session::logDebug)
        sink.setVideoDiagnosticHandler(session, type) {
            if (it == "first frame rendered") session.videoFrameRendered()
            session.logDebug("Video: $it")
        }
        sink.setVideoRecoveryHandler(session, type) {
            if (streams[streamKey] === screen) {
                val sent = session.sendCommand(mapOf("type" to "forceKeyFrame"))
                session.logDebug("Video recovery: requested keyframe sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = withOpenSession(session, Unit) {
                    sink.onVideoCodec(session, type, codec)
                }
                override fun onConfig(codecData: ByteArray) = withOpenSession(session, Unit) {
                    sink.onVideoConfig(session, type, codecData)
                }
                override fun onFrame(naluBytes: ByteArray) = withOpenSession(session, Unit) {
                    sink.onVideoFrame(session, type, naluBytes)
                }
                override fun onClosed(cause: Throwable?) = withOpenSession(session, Unit) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (streams.remove(streamKey, screen)) {
                        sink.onScreenStreamActive(session, type, false)
                    }
                    session.close()
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        sink.onScreenStreamActive(session, type, true)
        return port

        }
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        return withOpenSession(session, null) {
        val streamKey = StreamKey(session, type)
        streams.remove(streamKey)?.close()
        audioMeta.remove(streamKey)
        audioCaptures.remove(streamKey)?.close()
        if (pendingMicrophone.remove(streamKey) != null) sink.onMicrophoneStopped(session, type)
        sink.onAudioStopped(session, type)

        val key = outputKey(session, stream) ?: return null
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        if (microphone != null) pendingMicrophone[streamKey] = microphone

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[streamKey] = capture
        val audio = AudioStream(key, type, session::logDebug)
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) = withOpenSession(session, Unit) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(session, type, format, firstSample)
                    microphone?.let { sink.onMicrophoneStarted(session, type, it) }
                }

                override fun onRtp(rtp: ByteArray, sample: Int) = withOpenSession(session, Unit) {
                    sink.onAudioRtp(session, type, format, rtp, sample)
                }

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) = withOpenSession(session, Unit) {
                    capture?.record(wire, rtp, sample, error)
                }
            },
        )
        streams[streamKey] = audio
        audioMeta[streamKey] = meta
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )

        }
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        return withOpenSession(session, null) {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = withOpenSession(session, Unit) {
                            sink.onIapMessage(bytes)
                        }

                        override fun onClosed(cause: Throwable?) = withOpenSession(session, Unit) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }

        }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        return withOpenSession(session, Unit) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }

        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        return withOpenSession(session, null) {
        val active = audioMeta.filterKeys { it.session === session }.values.toList()
        if (active.isEmpty()) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        }
        return linkedMapOf("streams" to streams)

        }
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        return withOpenSession(session, Unit) {
        val streamKey = StreamKey(session, type)
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        if (pendingMicrophone.remove(streamKey) != null) sink.onMicrophoneStopped(session, type)
        audioMeta.remove(streamKey)
        audioCaptures.remove(streamKey)?.close()
        sink.onAudioStopped(session, type)
        streams.remove(streamKey)?.close()
        if (isScreenStreamType(type)) sink.onScreenStreamActive(session, type, false)

        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        val lock = sessionLocks.computeIfAbsent(session) { Any() }
        synchronized(lock) {
            if (!closedSessions.add(session)) return

        sink.onSessionClosed(session)
        clearPendingIapTunnel(session)
        val sessionStreams = streams.keys.filter { it.session === session }
        sessionStreams
            .filter { isScreenStreamType(it.type) }
            .forEach { sink.onScreenStreamActive(session, it.type, false) }
        sessionStreams.forEach { streams.remove(it)?.close() }
        audioMeta.keys.filter { it.session === session }.forEach { key ->
            audioMeta.remove(key)
            sink.onAudioStopped(session, key.type)
        }
        pendingMicrophone.keys.filter { it.session === session }.forEach { key ->
            pendingMicrophone.remove(key)?.let { sink.onMicrophoneStopped(session, key.type) }
        }
        audioCaptures.keys.filter { it.session === session }.forEach { key ->
            audioCaptures.remove(key)?.close()
        }

            sessionLocks.remove(session, lock)
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (format.audioType != "telephony" && format.audioType != "speechrecognition") return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val TAG = "nexuscp-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        const val OPUS_24K = 0x20000000L
        const val OPUS_48K = 0x40000000L
    }
}

/**
 * Long.toUnsignedString and Integer.toUnsignedString are API 26. Masking to the unsigned width
 * produces the same digits on every supported release, so the encoder never depends on them.
 */
private val UNSIGNED_LONG_MASK: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

private fun unsignedLongDecimal(value: Long): String =
    BigInteger.valueOf(value).and(UNSIGNED_LONG_MASK).toString()

private fun unsignedIntDecimal(value: Int): String =
    (value.toLong() and 0xffffffffL).toString()

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> unsignedLongDecimal(value)
    is Int -> unsignedIntDecimal(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(::unsignedLongDecimal)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(unsignedLongDecimal(value)) else value
    is Int -> if (value < 0) BigInteger(unsignedIntDecimal(value)) else value
    else -> value ?: 0L
}
