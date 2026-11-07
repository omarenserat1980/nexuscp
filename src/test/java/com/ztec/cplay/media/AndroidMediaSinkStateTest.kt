package com.ztec.cplay.media

import com.ztec.cplay.airplay.AirPlayConfig
import com.ztec.cplay.airplay.AirPlayIdentity
import com.ztec.cplay.airplay.AirPlayDisplayConfig
import com.ztec.cplay.airplay.AirPlayMediaHandler
import com.ztec.cplay.airplay.AirPlaySession
import com.ztec.cplay.airplay.AirPlaySessionListener
import com.ztec.cplay.airplay.PairingStore
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AndroidMediaSinkStateTest {
    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        val session = testSession()
        sink.onScreenStreamActive(session, 110, true)
        sink.onScreenStreamActive(session, 111, true)
        sink.onScreenStreamActive(session, 111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        session.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }

    @Test fun videoRecoveryForOneSessionDoesNotSuppressAnotherSession() {
        val first = testSession()
        val second = testSession()
        val sink = AndroidMediaSink()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCalled = CountDownLatch(1)
        sink.setVideoRecoveryHandler(first, 110) {
            firstStarted.countDown()
            releaseFirst.await(2, TimeUnit.SECONDS)
        }
        sink.setVideoRecoveryHandler(second, 110) { secondCalled.countDown() }

        try {
            val keyClass = Class.forName("com.ztec.cplay.media.AndroidMediaSink\$StreamKey")
            val constructor = keyClass.getDeclaredConstructor(AirPlaySession::class.java, Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
            val request = AndroidMediaSink::class.java.getDeclaredMethod("requestVideoRecovery", keyClass)
                .apply { isAccessible = true }

            request.invoke(sink, constructor.newInstance(first, 110))
            assertTrue("first recovery should start", firstStarted.await(2, TimeUnit.SECONDS))
            request.invoke(sink, constructor.newInstance(second, 110))
            releaseFirst.countDown()
            assertTrue("second session recovery should not be suppressed", secondCalled.await(2, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            sink.close()
            first.close()
            second.close()
        }
    }

    @Test fun displaySurfaceOwnershipMovesToNextActiveSessionWhenOwnerStops() {
        val first = testSession()
        val second = testSession()
        val sink = AndroidMediaSink()
        try {
            sink.onScreenStreamActive(first, 110, true)
            sink.onScreenStreamActive(second, 110, true)
            assertSame(first, displayOwner(sink, 110))

            sink.onScreenStreamActive(first, 110, false)
            assertSame(second, displayOwner(sink, 110))

            sink.onScreenStreamActive(second, 110, false)
            assertNull(displayOwner(sink, 110))
        } finally {
            sink.close()
            first.close()
            second.close()
        }
    }


    @Test fun closingDisplayOwnerSessionTransfersOwnershipAndRejectsItsLateCallbacks() {
        val first = testSession()
        val second = testSession()
        val sink = AndroidMediaSink()
        try {
            sink.onScreenStreamActive(first, 110, true)
            sink.onScreenStreamActive(second, 110, true)
            assertSame(first, displayOwner(sink, 110))

            sink.onSessionClosed(first)
            assertSame("remaining session should inherit display ownership", second, displayOwner(sink, 110))

            sink.onVideoCodec(first, 110, com.ztec.cplay.airplay.VideoCodec.H264)
            sink.onVideoConfig(first, 110, byteArrayOf(1, 2, 3))
            sink.onVideoFrame(first, 110, byteArrayOf(0, 0, 1, 0x65))
            sink.onScreenStreamActive(first, 110, true)
            assertSame("late callbacks must not steal display ownership", second, displayOwner(sink, 110))

            val decoders = AndroidMediaSink::class.java.getDeclaredField("videoDecoders")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            assertTrue("closed owner must not recreate its decoder", decoders.keys.none {
                it!!.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(it) === first
            })
        } finally {
            sink.close()
            first.close()
            second.close()
        }
    }

    @Test fun sessionCloseClearsVideoMetadataEvenBeforeDecoderCreation() {
        val session = testSession()
        val sink = AndroidMediaSink()
        try {
            sink.onVideoCodec(session, 110, com.ztec.cplay.airplay.VideoCodec.H264)
            sink.setVideoRecoveryHandler(session, 110) { }
            sink.onSessionClosed(session)

            val pending = AndroidMediaSink::class.java.getDeclaredField("pendingVideoCodec")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            val handlers = AndroidMediaSink::class.java.getDeclaredField("videoRecoveryHandlers")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            assertTrue("session close must clear codec metadata without a decoder", pending.isEmpty())
            assertTrue("session close must clear recovery handlers", handlers.isEmpty())
        } finally {
            sink.close()
            session.close()
        }
    }

    @Test fun lateVideoCallbacksCannotRecreateDecoderAfterSessionClose() {
        val session = testSession()
        val sink = AndroidMediaSink()
        try {
            sink.onScreenStreamActive(session, 110, true)
            sink.onSessionClosed(session)
            sink.onVideoCodec(session, 110, com.ztec.cplay.airplay.VideoCodec.H264)
            sink.onVideoConfig(session, 110, byteArrayOf(1, 2, 3))
            sink.onVideoFrame(session, 110, byteArrayOf(0, 0, 1, 0x65))
            sink.onScreenStreamActive(session, 110, true)

            val decoders = AndroidMediaSink::class.java.getDeclaredField("videoDecoders")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            assertTrue("late callbacks must not recreate a closed session decoder", decoders.isEmpty())
            assertNull(displayOwner(sink, 110))
        } finally {
            sink.close()
            session.close()
        }
    }

    @Test fun callbacksAfterSinkCloseCannotRecreateMediaResources() {
        val session = testSession()
        val sink = AndroidMediaSink()
        sink.close()
        try {
            sink.onVideoCodec(session, 110, com.ztec.cplay.airplay.VideoCodec.H264)
            sink.onVideoConfig(session, 110, byteArrayOf(1, 2, 3))
            sink.onVideoFrame(session, 110, byteArrayOf(0, 0, 1, 0x65))
            sink.onScreenStreamActive(session, 110, true)

            val decoders = AndroidMediaSink::class.java.getDeclaredField("videoDecoders")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            val owners = AndroidMediaSink::class.java.getDeclaredField("videoDisplayOwners")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            assertTrue("closed sink must reject late decoder creation", decoders.isEmpty())
            assertTrue("closed sink must not assign display ownership", owners.isEmpty())
        } finally {
            session.close()
        }
    }

    @Test fun sinkCloseClearsPendingCodecAndRejectsLateListenerRegistration() {
        val session = testSession()
        val sink = AndroidMediaSink()
        try {
            sink.onVideoCodec(session, 110, com.ztec.cplay.airplay.VideoCodec.H264)
            sink.close()

            val pending = AndroidMediaSink::class.java.getDeclaredField("pendingVideoCodec")
                .apply { isAccessible = true }.get(sink) as Map<*, *>
            assertTrue("closing the sink must release pending codec metadata", pending.isEmpty())

            val events = mutableListOf<Pair<Int, Boolean>>()
            sink.setScreenStreamActiveChangedListener { type, active -> events += type to active }
            val listener = AndroidMediaSink::class.java.getDeclaredField("screenStreamActiveChanged")
                .apply { isAccessible = true }.get(sink)
            assertNull("a closed sink must not retain an Activity listener", listener)
            assertTrue(events.isEmpty())
        } finally {
            sink.close()
            session.close()
        }
    }

    private fun displayOwner(sink: AndroidMediaSink, type: Int): AirPlaySession? {
        val field = AndroidMediaSink::class.java.getDeclaredField("videoDisplayOwners")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val owners = field.get(sink) as Map<Int, AirPlaySession>
        return owners[type]
    }

    @Test fun closingOneSessionKeepsTheOtherSessionsScreenState() {
        val first = testSession()
        val second = testSession()
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(first, 110, true)
        sink.onScreenStreamActive(second, 110, true)
        sink.onScreenStreamActive(first, 110, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events += type to active }
        assertEquals(listOf(110 to true), events)
        sink.close()
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
