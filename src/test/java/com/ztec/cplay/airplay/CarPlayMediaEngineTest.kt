package com.ztec.cplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(session: AirPlaySession, type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(session: AirPlaySession, type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }


    @Test
    fun closedSessionRejectsNewMediaSetupAndFeedback() {
        val session = testSession()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            engine.onSessionClosed(session)

            assertEquals(null, engine.onScreen(session, 110, emptyMap()))
            assertEquals(null, engine.onAudio(session, 100, emptyMap()))
            assertEquals(null, engine.onDataStream(session, emptyMap()))
            assertEquals(null, engine.onFeedback(session))

            val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
                isAccessible = true
            }
            val streams = streamsField.get(engine) as Map<*, *>
            assertTrue("closed session must not register new streams", streams.isEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun closingOneSessionPreservesOtherSessionsAudioFeedback() {
        val first = testSession()
        val second = testSession()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val field = CarPlayMediaEngine::class.java.getDeclaredField("audioMeta").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val metadata = field.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Any>
        val format = AudioFormat(AudioCodecKind.AAC_LC, 44100, 2, 100)
        val firstMeta = CarPlayMediaEngine.AudioMeta(100, format, 11L, 0)
        val secondMeta = CarPlayMediaEngine.AudioMeta(100, format, 22L, 0)
        metadata[CarPlayMediaEngine.StreamKey(first, 100)] = firstMeta
        metadata[CarPlayMediaEngine.StreamKey(second, 100)] = secondMeta

        engine.onSessionClosed(first)
        val feedback = engine.onFeedback(second)
        assertEquals(1, metadata.size)
        val feedbackStreams = feedback?.get("streams") as List<*>
        assertEquals(1, feedbackStreams.size)
        assertEquals(100, (feedbackStreams.first() as Map<*, *>)["type"])
        first.close()
        second.close()
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
