package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.danila.hacustomwidgets.data.model.*
import com.danila.hacustomwidgets.data.remote.CompressedEntitySubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DashboardRc4RepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(): Context = object : ContextWrapper(context) {
        private val prefix = "rc4-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private val now = Instant.now()
    private fun entity(minutes: Int, at: Instant) = HaEntity("timer.rc4", "active", "Timer", null,
        at.toString(), timerDuration = AutoOffTimerPolicy.durationPayload(minutes),
        timerRemaining = AutoOffTimerPolicy.durationPayload(minutes), timerFinishesAt = at.plusSeconds(minutes * 60L).toString())
    private fun setup(c: Context, e: HaEntity): DashboardRepository {
        val config = DashboardConfig(704, emptyList(), emptyMap(), listOf("device"), emptyMap(), emptyMap(), false, true,
            autoOffTimersByDevice = mapOf("device" to AutoOffTimerConfig(enabled = true, timerEntityId = e.entityId, controlEntityId = "switch.rc4")))
        return DashboardRepository(c).also {
            it.saveConfiguration(config, HaCatalog(listOf(HaDeviceGroup(HaDevice("device", "Socket"),
                listOf(e, HaEntity("switch.rc4", "on", "Socket", null, e.lastUpdated))))))
        }
    }
    private fun local(c: Context, confirmed: Boolean, at: Instant = now.minusSeconds(180)) {
        TimerResetStore(c).put(TimerReset("local", "timer.rc4", 704, "switch.rc4", "switch", 30,
            at.toEpochMilli(), at.minusSeconds(1).toEpochMilli(), at.plusSeconds(1800).toEpochMilli(), true,
            at.toEpochMilli().takeIf { confirmed }, baselineFinishAt = at.plusSeconds(1000).toEpochMilli()))
    }
    private fun incoming(e: HaEntity, source: DashboardStateSource): HaEntity {
        if (source != DashboardStateSource.EVENT) return e
        return CompressedEntitySubscriptionParser().apply(JSONObject().put("a", JSONObject().put(e.entityId,
            JSONObject().put("s", "active").put("lu", Instant.parse(e.lastUpdated).toEpochMilli() / 1000.0)
                .put("a", JSONObject().put("friendly_name", "Timer").put("duration", e.timerDuration)
                    .put("remaining", e.timerRemaining).put("finishes_at", e.timerFinishesAt))))).entities.single()
    }
    private fun assertShown(repo: DashboardRepository, e: HaEntity) {
        val metric = repo.get(704)!!.cards.single().timerState!!
        assertEquals(e.timerFinishesAt, metric.timerFinishesAt)
        assertEquals(e.timerDuration, metric.timerDuration)
        val presentation = HaTimerPresentationPolicy.resolve(metric, Instant.parse(e.lastUpdated))
        assertEquals(HaTimerPresentationPolicy.parseDuration(e.timerDuration), presentation.remainingMillis)
    }
    private fun roundTrip(source: DashboardStateSource, reverse: Boolean) {
        val ca = isolated(); val cb = isolated()
        val start = entity(30, now.minusSeconds(180))
        val a = setup(ca, start); val b = setup(cb, start)
        local(ca, reverse); local(cb, !reverse)
        val sixty = entity(60, now)
        listOf(a,b).forEach { it.updateEntityStates(704, listOf(incoming(sixty, source)), source); assertShown(it, sixty) }
        val thirty = entity(30, now.plusSeconds(180))
        listOf(b,a).forEach { it.updateEntityStates(704, listOf(incoming(thirty, source)), source); assertShown(it, thirty) }
        // A delayed old snapshot cannot roll either repository or presentation backwards.
        listOf(a,b).forEach { it.updateEntityStates(704, listOf(start), source); assertShown(it, thirty) }
    }
    @Test fun twoRepositoriesAToBRest() = roundTrip(DashboardStateSource.MANUAL_REFRESH, false)
    @Test fun twoRepositoriesBToARest() = roundTrip(DashboardStateSource.MANUAL_REFRESH, true)
    @Test fun twoRepositoriesAToBWebSocket() = roundTrip(DashboardStateSource.EVENT, false)
    @Test fun twoRepositoriesBToAWebSocket() = roundTrip(DashboardStateSource.EVENT, true)
    @Test fun freshServerRetiresRecentOverlayAndPersistsAcrossReload() {
        val c = isolated(); val start = entity(30, now.minusSeconds(5)); val repo = setup(c, start)
        local(c, false, now.minusSeconds(5))
        val fresh = entity(120, now)
        repo.updateEntityStates(704, listOf(fresh), DashboardStateSource.EVENT)
        assertShown(repo, fresh)
        assertShown(DashboardRepository(c), fresh)
        assertFalse(TimerResetPolicy.showOverlay(TimerResetStore(c).get("timer.rc4")!!, now.toEpochMilli()))
    }
    @Test fun httpAcknowledgementPreservesEarlierWebsocketConfirmation() {
        val c = isolated(); local(c, false, now.minusSeconds(5))
        val store = TimerResetStore(c)
        val fresh = entity(60, now)
        store.reconcile(store.get("timer.rc4")!!, fresh)
        assertTrue(store.markAccepted("timer.rc4", "local", now.plusSeconds(2).toEpochMilli()))
        assertEquals(Instant.parse(fresh.timerFinishesAt).toEpochMilli(), store.get("timer.rc4")!!.finishAt)
        assertFalse(TimerResetPolicy.showOverlay(store.get("timer.rc4")!!, now.toEpochMilli()))
        assertFalse(store.markAccepted("timer.rc4", "obsolete", now.toEpochMilli()))
    }
    @Test fun upgradeCancelsWorkAlarmAndClearsOldRunsOnlyOnce() {
        val c = isolated(); local(c, true)
        val wm = WorkManager.getInstance(context)
        val orphan = OneTimeWorkRequestBuilder<DashboardTimerExpiryWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        wm.enqueue(orphan).result.get(20, TimeUnit.SECONDS)
        val work = OneTimeWorkRequestBuilder<DashboardTimerExpiryWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        wm.enqueueUniqueWork(TimerExpiryMigration.workNames("timer.rc4")[0], ExistingWorkPolicy.REPLACE, work).result.get(20, TimeUnit.SECONDS)
        val intent = Intent(c, DashboardTimerAlarmReceiver::class.java).setData(TimerExpiryMigration.alarmUri("timer.rc4"))
        val alarm = PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+86_400_000, alarm)
        TimerExpiryMigration.cleanup(c)
        assertTrue(TimerResetStore(c).all().isEmpty())
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(orphan.id).get(20, TimeUnit.SECONDS)!!.state)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(work.id).get(20, TimeUnit.SECONDS)!!.state)
        assertNull(PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        local(c, false)
        TimerExpiryMigration.cleanup(c)
        assertNotNull(TimerResetStore(c).get("timer.rc4"))
    }
}
