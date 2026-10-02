package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.*
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LightOperationTest {
    private class Fixture(scope: CoroutineScope) {
        var connection: HomeAssistantConnection? = HomeAssistantConnection("https://test.invalid","test")
        var state = VersionedEntityState("light.a","off","off",1,1,
            brightness=LightBrightness.parse(JSONObject("""{"brightness":166,"supported_color_modes":["color_temp","rgbww"],"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500}""")))
        var errors=0; var refreshes=0; var active=0; var maximum=0
        var echo=true; var fail=false
        var blocked: CompletableDeferred<Unit>?=null
        val calls=mutableListOf<Map<String,*>>()
        val engine=BrightnessCoordinator(connection={connection},truth={state},changed={},failure={errors++},
            send={ _,_,target ->
                active++;maximum=maxOf(active,maximum)
                try { blocked?.await(); calls+=mapOf("brightness_pct" to target); update(state.brightness.copy(value=(target*255/100.0).toInt())) }
                finally {active--}
            }, refresh={_,_->refreshes++},scope=scope,coalesceMs=10,confirmationTicks=3,tickMs=10,
            sendColor={_,_,data ->
                active++;maximum=maxOf(active,maximum);calls+=data
                try {
                    blocked?.await()
                    if(fail) error("failure")
                    if(echo) {
                        val kelvin=data["color_temp_kelvin"] as? Int
                        update(if(kelvin!=null) state.brightness.copy(colorMode="color_temp",temperatureKelvin=kelvin)
                            else state.brightness.copy(colorMode="rgbww",color=LightColor.parse(JSONObject(data))))
                    }
                } finally {active--}
            })
        fun update(light:LightBrightness) {state=state.copy(confirmedRawState="on",brightness=light,confirmedHaLastUpdatedMillis=(state.confirmedHaLastUpdatedMillis?:0)+1)}
        suspend fun settle() {withTimeout(2000){while(engine.temperatureTarget("light.a")!=null || engine.colorTarget("light.a")!=null || engine.overlay("light.a")!=null || active>0) delay(5)}}
    }
    @Test fun offTemperatureClampsCoalescesAndPreservesBrightness()=runBlocking {
        val f=Fixture(this)
        f.engine.temperature("light.a",2700).join();f.engine.temperature("light.a",9999).join();f.settle()
        assertEquals(1,f.calls.size);assertEquals(6500,f.state.brightness.temperatureKelvin)
        assertEquals("on",f.state.confirmedRawState);assertEquals(166,f.state.brightness.value)
    }
    @Test fun colorModeTargetSupersedesTemperature()=runBlocking {
        val f=Fixture(this)
        f.engine.temperature("light.a",4000).join();f.engine.color("light.a",LightColor(120.0,75.0)).join();f.settle()
        assertEquals(1,f.calls.size);assertFalse(f.calls.single().containsKey("brightness"))
        assertEquals(LightColor(120.0,75.0),f.state.brightness.color)
    }
    @Test fun staleEchoCannotRevokeLatestTarget()=runBlocking {
        val f=Fixture(this);val gate=CompletableDeferred<Unit>();f.blocked=gate
        f.engine.temperature("light.a",3000).join()
        withTimeout(1000){while(f.calls.isEmpty())delay(1)}
        f.engine.temperature("light.a",5000).join();f.update(f.state.brightness.copy(colorMode="color_temp",temperatureKelvin=3000))
        assertEquals(5000,f.engine.temperatureTarget("light.a"))
        gate.complete(Unit);f.settle();assertEquals(5000,f.state.brightness.temperatureKelvin)
    }
    @Test fun errorAndTimeoutRemoveOverlayWithoutInventingTruth()=runBlocking {
        for(networkError in listOf(true,false)) {
            val f=Fixture(this);f.echo=false;f.fail=networkError
            f.engine.temperature("light.a",4000).join();f.settle()
            assertNull(f.state.brightness.temperatureKelvin);assertEquals(1,f.errors)
            assertEquals(if(networkError)0 else 1,f.refreshes)
        }
    }
    @Test fun reconnectAndServerSwitchDiscardCommands()=runBlocking {
        val f=Fixture(this);f.engine.temperature("light.a",4000).join();f.engine.invalidate();delay(30);assertTrue(f.calls.isEmpty())
        f.engine.color("light.a",LightColor(60.0,50.0)).join()
        f.connection=HomeAssistantConnection("https://other.invalid","test");delay(30)
        assertTrue(f.calls.isEmpty());assertNull(f.engine.colorTarget("light.a"))
    }
    @Test fun powerCancelsQueuedModesAndWaitsForNetwork()=runBlocking {
        val f=Fixture(this);val gate=CompletableDeferred<Unit>();f.blocked=gate
        f.engine.temperature("light.a",4000).join();withTimeout(1000){while(f.calls.isEmpty())delay(1)}
        f.engine.color("light.a",LightColor(200.0,75.0)).join()
        val power=launch {f.engine.power("light.a"){assertEquals(0,f.active)}}
        delay(5);gate.complete(Unit);power.join();delay(30)
        assertEquals(1,f.calls.size);assertNull(f.engine.colorTarget("light.a"))
    }
    @Test fun brightnessAndColorHaveOneInFlightNetworkCall()=runBlocking {
        val f=Fixture(this)
        f.engine.submit("light.a",absolute=40).join();f.engine.color("light.a",LightColor(300.0,60.0)).join();f.settle()
        assertEquals(1,f.maximum);assertEquals(LightColor(300.0,60.0),f.state.brightness.color)
        assertEquals(40,LightBrightness.percent(f.state.brightness.value))
    }
    @Test fun lossOfCapabilityBeforeDrainPreventsSend()=runBlocking {
        val f=Fixture(this);f.engine.temperature("light.a",4000).join()
        f.state=f.state.copy(brightness=LightBrightness(modesPresent=true,modes=listOf("onoff")))
        f.settle();assertTrue(f.calls.isEmpty())
    }
}
