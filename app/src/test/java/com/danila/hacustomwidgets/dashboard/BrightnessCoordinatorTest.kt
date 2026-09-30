package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.LightBrightness
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class BrightnessCoordinatorTest {
    @Test fun slider88To39OwnsValueUntilMatchingConfirmation() = runBlocking {
        val f = Fixture(this); f.confirm(88)
        val gate = CompletableDeferred<Unit>(); f.blocked = gate
        val selection = BrightnessSelection()
        assertEquals(88, f.engine.displayPercent("light.a"))
        selection.change(70f); selection.change(39f)
        assertTrue(f.calls.isEmpty())
        f.engine.submit("light.a", absolute = selection.finish(true)!!).join()
        assertNull(selection.finish(true))
        assertEquals(88, LightBrightness.percent(f.state.brightness.value))
        assertEquals(39, f.engine.displayPercent("light.a"))
        withTimeout(1000) { while (f.calls.isEmpty()) delay(1) }
        f.confirm(88) // stale value, even with a later timestamp
        assertEquals(39, f.engine.displayPercent("light.a"))
        gate.complete(Unit); f.settle()
        assertEquals(listOf(39), f.calls)
        assertEquals(39, f.engine.displayPercent("light.a"))
        assertNull(f.engine.overlay("light.a"))
    }

    @Test fun sliderErrorAndTimeoutReleaseOwnershipToConfirmed88() = runBlocking {
        for (networkError in listOf(true, false)) {
            val f = Fixture(this); f.confirm(88); f.fail = networkError; f.echo = false
            f.engine.submit("light.a", absolute = 39).join()
            assertEquals(39, f.engine.displayPercent("light.a"))
            f.settle()
            assertEquals(88, f.engine.displayPercent("light.a"))
            assertEquals(1, f.errors)
        }
    }

    @Test fun newerSliderTargetSurvivesOldConfirmationAndOldFailure() = runBlocking {
        for (oldFails in listOf(false, true)) {
            val f = Fixture(this); f.confirm(88)
            val gate = CompletableDeferred<Unit>(); f.blocked = gate; f.fail = oldFails
            f.engine.submit("light.a", absolute = 39).join()
            withTimeout(1000) { while (f.calls.isEmpty()) delay(1) }
            f.engine.submit("light.a", absolute = 65).join()
            f.confirm(39)
            assertEquals(65, f.engine.displayPercent("light.a"))
            gate.complete(Unit)
            // Let the old call complete, but retain enough coalescing time to inspect ownership.
            delay(1)
            assertEquals(65, f.engine.displayPercent("light.a"))
            f.fail = false; f.blocked = null
            f.settle()
            assertEquals(listOf(39, 65), f.calls)
            assertEquals(65, f.engine.displayPercent("light.a"))
            assertEquals(0, f.errors)
        }
    }

    private class Fixture(val scope: CoroutineScope) {
        var connection: HomeAssistantConnection? = HomeAssistantConnection("https://test.invalid", "test")
        var state = VersionedEntityState("light.a", "on", "on", 1, 1,
            brightness = LightBrightness(166, true, listOf("brightness")), lastConfirmedBrightness = 166)
        val calls = mutableListOf<Int>()
        var errors = 0
        var fetches = 0
        var inFlight = 0
        var maxInFlight = 0
        var fail = false
        var echo = true
        var blocked: CompletableDeferred<Unit>? = null
        val engine = BrightnessCoordinator(
            connection = { connection }, truth = { state }, changed = {}, failure = { errors++ },
            send = { _, _, target ->
                inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
                calls += target
                try {
                    blocked?.await()
                    if (fail) error("network failure")
                    if (echo) confirm(target)
                } finally { inFlight-- }
            }, refresh = { _, _ -> fetches++ }, scope = scope,
            coalesceMs = 10, confirmationTicks = 3, tickMs = 10,
        )
        fun confirm(percent: Int) {
            state = state.copy(confirmedRawState = "on", confirmedHaLastUpdatedMillis = (state.confirmedHaLastUpdatedMillis ?: 0) + 1,
                brightness = state.brightness.copy(value = (percent * 255.0 / 100).roundToInt()))
        }
        suspend fun settle() = withTimeout(2000) { while (engine.overlay("light.a") != null || inFlight > 0) delay(5) }
    }
    @Test fun rapidPlusUsesLatestLocalTarget() = runBlocking {
        val f = Fixture(this)
        repeat(3) { f.engine.submit("light.a", step = 1).join() }
        assertEquals(80, f.engine.overlay("light.a"))
        f.settle()
        assertEquals(listOf(80), f.calls)
        assertEquals(1, f.maxInFlight)
    }
    @Test fun inFlightReplacesQueueAndOldEchoDoesNotClearIt() = runBlocking {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>(); f.blocked = gate
        f.engine.submit("light.a", step = 1).join()
        withTimeout(1000) { while (f.calls.isEmpty()) delay(1) }
        f.engine.submit("light.a", step = 1).join()
        f.engine.submit("light.a", step = 1).join()
        f.confirm(70)
        assertEquals(80, f.engine.overlay("light.a"))
        gate.complete(Unit); f.settle()
        assertEquals(listOf(70, 80), f.calls)
        assertEquals(1, f.maxInFlight)
    }
    @Test fun rapidDirectionChangeAndAbsoluteSliderShareQueue() = runBlocking {
        val f = Fixture(this)
        f.engine.submit("light.a", step = 1).join()
        f.engine.submit("light.a", step = -1).join()
        f.engine.submit("light.a", absolute = 95).join()
        f.engine.submit("light.a", step = 1).join()
        f.settle(); assertEquals(listOf(100), f.calls)
    }
    @Test fun offUsesConfirmedHistoryAndUnknownBaseIsDisabled() = runBlocking {
        val f = Fixture(this)
        f.state = f.state.copy(confirmedRawState = "off", brightness = f.state.brightness.copy(value = null))
        f.engine.submit("light.a", step = -1).join(); f.settle()
        assertEquals(listOf(60), f.calls)
        f.state = f.state.copy(brightness = f.state.brightness.copy(value = null), lastConfirmedBrightness = null)
        f.engine.submit("light.a", step = 1).join()
        assertNull(f.engine.overlay("light.a")); assertEquals(1, f.calls.size)
    }
    @Test fun unavailableUnknownAndOnOffOnlyRejectCommands() = runBlocking {
        val f = Fixture(this)
        for (state in listOf("unknown", "unavailable")) {
            f.state = f.state.copy(confirmedRawState = state)
            f.engine.submit("light.a", absolute = 50).join()
        }
        f.state = f.state.copy(confirmedRawState = "on", brightness = LightBrightness(166, true, listOf("onoff")))
        f.engine.submit("light.a", step = 1).join(); assertTrue(f.calls.isEmpty())
    }
    @Test fun errorAndTimeoutRollbackWithoutInventingTruth() = runBlocking {
        for (networkError in listOf(true, false)) {
            val f = Fixture(this); f.fail = networkError; f.echo = false
            f.engine.submit("light.a", step = 1).join(); f.settle()
            assertEquals(1, f.errors); assertNull(f.engine.overlay("light.a"))
            assertEquals(166, f.state.brightness.value)
            assertEquals(if (networkError) 0 else 1, f.fetches)
        }
    }
    @Test fun connectionChangeAndReconnectDiscardQueuedTargets() = runBlocking {
        val f = Fixture(this)
        f.engine.submit("light.a", step = 1).join()
        f.engine.invalidate()
        delay(30); assertTrue(f.calls.isEmpty())
        f.engine.submit("light.a", step = 1).join()
        f.connection = HomeAssistantConnection("https://other.invalid", "other")
        delay(30); assertTrue(f.calls.isEmpty()); assertNull(f.engine.overlay("light.a"))
    }
    @Test fun powerWaitsForInFlightAndCancelsQueue() = runBlocking {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>(); f.blocked = gate
        f.engine.submit("light.a", step = 1).join()
        withTimeout(1000) { while (f.calls.isEmpty()) delay(1) }
        f.engine.submit("light.a", step = 1).join()
        var powered = false
        val power = launch { f.engine.power("light.a") { assertEquals(0, f.inFlight); powered = true } }
        delay(5); assertFalse(powered)
        gate.complete(Unit); power.join(); delay(30)
        assertTrue(powered); assertEquals(listOf(70), f.calls)
    }
}
