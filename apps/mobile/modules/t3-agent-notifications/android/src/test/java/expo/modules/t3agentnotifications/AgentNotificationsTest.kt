package expo.modules.t3agentnotifications

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

abstract class AgentNotificationsFixture {
  protected lateinit var context: Application
  protected lateinit var manager: NotificationManager
  protected lateinit var lifecycle: LifecycleRegistry

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    manager = context.getSystemService(NotificationManager::class.java)
    shadowOf(manager).setNotificationsEnabled(true)
    lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    lifecycle.currentState = Lifecycle.State.CREATED

    val launcher = ComponentName(context, Activity::class.java)
    shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
    shadowOf(context.packageManager).addIntentFilterForActivity(
      launcher,
      IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
      }
    )
    AgentNotifications.clear(context)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.setThreadOnScreen("/threads/environment/thread")
  }

  protected fun update(alertId: String, active: Boolean) = mapOf(
    "device_id" to "device",
    "user_id" to "user",
    "updated_at" to System.currentTimeMillis().toString(),
    "active" to active.toString(),
    "activity_title" to "1 active agent",
    "activity_body" to "Test thread · Working",
    "activity_path" to "/threads/environment/thread",
    "alert_id" to alertId,
    "alert_group" to "environment/thread",
    "alert_title" to "Test thread",
    "alert_body" to "Done: Test project",
    "alert_path" to "/threads/environment/thread",
  )
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 33, 36], manifest = Config.NONE)
class AgentNotificationsTest : AgentNotificationsFixture() {
  @Test
  fun alertHistoryEvictsOnlyTheOldestEntryAfterCapacity() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    for (id in 0..64) AgentNotifications.receive(context, update("alert-$id", false))
    lifecycle.currentState = Lifecycle.State.CREATED
    for (id in 1..64) AgentNotifications.receive(context, update("alert-$id", false))
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.receive(context, update("alert-0", false))
    assertEquals("alert-0".hashCode(), manager.activeNotifications.single().id)
  }

  @Test
  fun alertsStackByThreadGroup() {
    AgentNotifications.receive(context, update("grouped", false))
    assertEquals("environment/thread", manager.activeNotifications.single().notification.group)
  }

  @Test
  fun missingLauncherDoesNotDiscardTheAlert() {
    shadowOf(context.packageManager).removeActivity(ComponentName(context, Activity::class.java))
    AgentNotifications.receive(context, update("no-launcher", false))
    assertEquals(
      "Test thread",
      manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE)
    )
  }

  @Test
  fun foregroundAlertsForThreadsThatAreNotOnScreen() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.setThreadOnScreen("/threads/environment/other")
    AgentNotifications.receive(context, update("elsewhere", false))
    assertEquals("t3-agent-alert", manager.activeNotifications.single().tag)
  }

  @Test
  fun foregroundSuppressesAlertsWhileOngoingActivityStillUpdatesAndClears() {
    lifecycle.currentState = Lifecycle.State.RESUMED

    AgentNotifications.receive(context, update("attention", true))

    val ongoing = manager.activeNotifications.single()
    assertEquals("t3-agent-activity", ongoing.tag)
    assertEquals("1 active agent", ongoing.notification.extras.getString(Notification.EXTRA_TITLE))
    assertTrue(ongoing.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

    AgentNotifications.receive(context, update("completion", false))

    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun retryOfForegroundSuppressedAlertDoesNotAppearAfterBackgrounding() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val suppressed = update("foreground-completion", false)
    AgentNotifications.receive(context, suppressed)
    lifecycle.currentState = Lifecycle.State.CREATED

    AgentNotifications.receive(context, suppressed)

    assertTrue(manager.activeNotifications.isEmpty())

    AgentNotifications.receive(context, update("later-background-completion", false))

    assertEquals("later-background-completion".hashCode(), manager.activeNotifications.single().id)
  }

  @Test
  fun returningToForegroundSuppressesNewAlertsWithoutRemovingPreviousOnes() {
    AgentNotifications.receive(context, update("background-completion", false))
    lifecycle.currentState = Lifecycle.State.RESUMED

    AgentNotifications.receive(context, update("foreground-completion", false))

    assertEquals("background-completion".hashCode(), manager.activeNotifications.single().id)
  }

  @Test
  fun repeatedThreadAlertsHaveOneSilentSummaryAndClearTogether() {
    AgentNotifications.receive(context, update("first", false) + ("alert_group" to "thread-group"))
    AgentNotifications.receive(context, update("second", false) + ("alert_group" to "thread-group"))

    val summary = manager.activeNotifications.single {
      it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
    }
    assertEquals("thread-group", summary.notification.group)
    assertEquals(3, manager.activeNotifications.size)
    assertEquals(null, summary.notification.sound)

    AgentNotifications.clear(context)
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun groupedAlertDisplaysEveryThreadAndRetriesStaySilent() {
    val titles = (1..5).map { "Thread $it " + "x".repeat(111) }.joinToString(", ")
    val grouped = update("group-completion", false) + mapOf(
      "alert_title" to "5 agents finished",
      "alert_body" to titles,
      "alert_path" to "/",
    )

    AgentNotifications.receive(context, grouped)
    AgentNotifications.receive(
      context,
      grouped + ("alert_body" to "A retry must not replace this alert")
    )

    val alert = manager.activeNotifications.single()
    assertEquals("5 agents finished", alert.notification.extras.getString(Notification.EXTRA_TITLE))
    assertEquals(titles, alert.notification.extras.getString(Notification.EXTRA_BIG_TEXT))
    assertEquals("t3code-dev://", shadowOf(alert.notification.contentIntent).savedIntent.dataString)
  }

  @Test
  fun foregroundGroupForOtherThreadsAlertsOnceAcrossBackgroundRetry() {
    val grouped = update("group-attention", true) + mapOf(
      "alert_title" to "2 agents need attention",
      "alert_body" to "First thread, Second thread",
      "alert_path" to "/",
    )
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, grouped)
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-alert" })
    lifecycle.currentState = Lifecycle.State.CREATED
    AgentNotifications.receive(context, grouped)

    assertEquals(2, manager.activeNotifications.size)
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-alert" })
  }

  @Test
  fun reopeningSameAccountPreservesCardsDeduplicationAndDismissal() {
    val message = update("attention", true)
    AgentNotifications.receive(context, message)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    assertEquals(2, manager.activeNotifications.size)
    AgentNotifications.dismiss(context)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, message)
    assertEquals("t3-agent-alert", manager.activeNotifications.single().tag)
  }

  @Test
  fun changingAccountOrDeviceClearsOldCardsAndRejectsOldPushes() {
    AgentNotifications.receive(context, update("attention", true))
    AgentNotifications.configure(context, "device", "different-user", "t3code-dev", true)
    AgentNotifications.receive(context, update("attention", true))
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.configure(context, "different-device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, update("attention", true))
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.clear(context)
    AgentNotifications.receive(context, update("attention", true))
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun expandedActivityShowsFiveRowsAndUsesThePriorityThreadRoute() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val lines =
      listOf(
        "Approval: First · Project",
        "Input: Second · Project",
        "Failed: Third · Project",
        "Working: Fourth · Project",
        "Done: Fifth · Project"
      )
    AgentNotifications.receive(
      context,
      update("attention", true) +
        lines.mapIndexed { index, line -> "activity_line_$index" to line }.toMap()
    )
    val card = manager.activeNotifications.single().notification
    assertEquals(lines.joinToString("\n"), card.extras.getString(Notification.EXTRA_BIG_TEXT))
    assertEquals(
      "t3code-dev://threads/environment/thread",
      shadowOf(card.contentIntent).savedIntent.dataString
    )
  }

  @Test
  fun quietWorkUsesAbsoluteRelayLifetimeInsteadOfTenMinuteRemoval() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val expiresAt = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
    AgentNotifications.receive(
      context,
      update("work", true) + ("activity_expires_at" to expiresAt.toString())
    )
    val card = manager.activeNotifications.single().notification
    assertTimeout(card, 119 * 60 * 1000L..120 * 60 * 1000L)
  }

  @Test
  fun finishedCardIsRetainedSilentlyAndExpiresAtTheOriginalDeadline() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L
    val finished = update("finished", false) + mapOf(
      "activity_title" to "Agent work failed",
      "activity_body" to "Failed: Test thread · Project",
      "activity_expires_at" to expiresAt.toString(),
    )
    AgentNotifications.receive(context, finished)
    val card = manager.activeNotifications.single().notification
    assertEquals("Agent work failed", card.extras.getString(Notification.EXTRA_TITLE))
    assertEquals(Build.VERSION.SDK_INT >= 36, card.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTimeout(card, 1..15 * 60 * 1000L)
    AgentNotifications.receive(
      context,
      finished + ("activity_expires_at" to (System.currentTimeMillis() - 1).toString())
    )
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun dismissalIncludesFinishedReplaysAndANewRunRearmsTheCard() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("work", true))
    AgentNotifications.dismiss(context)
    val finished =
      update("finished", false) +
        ("activity_expires_at" to (System.currentTimeMillis() + 900000).toString())
    AgentNotifications.receive(context, finished)
    AgentNotifications.receive(context, finished)
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.receive(context, update("new-work", true))
    assertEquals("t3-agent-activity", manager.activeNotifications.single().tag)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", false)
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun severalThreadsListEveryRowWithItsStatusAndActionsFollowThePriorityThread() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val title = "A long thread title that should wrap rather than disappear"
    val data = update("attention", true) + mapOf(
      "activity_line_0" to "Approval	$title	Project",
      "activity_line_1" to "Working	Another thread	Other project",
      "activity_phase" to "waiting_for_approval",
      "activity_active_count" to "8",
      "activity_attention_count" to "1",
    )
    AgentNotifications.receive(context, data)
    val card = manager.activeNotifications.single().notification
    assertEquals("1 needs you", card.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals("8 active", card.extras.getString(Notification.EXTRA_SUB_TEXT))
    assertEquals(
      "Approval $title · Project\nWorking Another thread · Other project",
      card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    )
    assertEquals(
      "Approval $title · Project",
      card.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    )
    assertEquals(listOf("Approve", "Dismiss"), card.actions.map { it.title.toString() })
    assertEquals(
      "t3code-dev://threads/environment/thread",
      shadowOf(card.actions[0].actionIntent).savedIntent.dataString
    )
    assertEquals("Approve", card.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT))

    AgentNotifications.receive(
      context,
      data + mapOf(
        "activity_phase" to "waiting_for_input",
        "activity_attention_count" to "2",
        "activity_path" to "/threads/another-environment/another-thread",
      )
    )
    val next = manager.activeNotifications.single().notification
    assertEquals("2 need you", next.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals("Answer", next.actions[0].title.toString())
    assertEquals(
      "t3code-dev://threads/another-environment/another-thread",
      shadowOf(next.actions[0].actionIntent).savedIntent.dataString
    )
    assertEquals("Answer", next.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT))
  }

  @Test
  fun waitingCardsIgnoreMutableThreadTimestampsEvenAfterRename() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val now = System.currentTimeMillis()
    for (phase in listOf("waiting_for_approval", "waiting_for_input")) {
      val status = if (phase == "waiting_for_approval") "Approval" else "Input"
      for ((title, updatedAt) in listOf("Original" to now - 1200000L, "Renamed" to now)) {
        AgentNotifications.receive(
          context,
          update("waiting", true) + mapOf(
            "activity_line_0" to "$status\t$title\tProject",
            "activity_phase" to phase,
            "activity_since" to updatedAt.toString()
          )
        )
        val card = manager.activeNotifications.single().notification
        assertEquals(title, card.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(card.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertFalse(card.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
      }
    }
  }

  @Test
  fun aSingleThreadUsesItsTitleAndNeverShowsAProgressBar() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val data = update("work", true) + mapOf(
      "activity_line_0" to "Working	Update dashboard	Project",
      "activity_phase" to "running",
    )
    AgentNotifications.receive(context, data)
    val working = manager.activeNotifications.single().notification
    assertEquals(
      "Update dashboard",
      working.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    )
    assertEquals(
      "Working Project",
      working.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    )
    assertEquals(null, working.extras.getString(Notification.EXTRA_SUB_TEXT))
    assertEquals("Working", working.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT))
    assertFalse(working.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    for (phase in listOf(
      "waiting_for_approval",
      "waiting_for_input",
      "stale",
      "failed",
      "completed"
    )) {
      val active = phase != "failed" && phase != "completed"
      AgentNotifications.receive(
        context,
        data + mapOf(
          "activity_phase" to phase,
          "active" to active.toString(),
          "activity_expires_at" to (System.currentTimeMillis() + 900000).toString(),
        )
      )
      val card = manager.activeNotifications.single().notification
      assertEquals(
        "android.app.Notification\$BigTextStyle",
        card.extras.getString(Notification.EXTRA_TEMPLATE)
      )
      assertEquals(
        active || Build.VERSION.SDK_INT >= 36,
        NotificationCompat.isRequestPromotedOngoing(card)
      )
      if (!active) {
        assertEquals(
          if (Build.VERSION.SDK_INT < 36) {
            null
          } else if (phase == "failed") {
            "Failed"
          } else {
            "Done"
          },
          card.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)
        )
        assertEquals(if (Build.VERSION.SDK_INT >= 36) 1 else 0, card.actions?.size ?: 0)
      }
    }
  }

  @Test
  fun olderRelayRowsStillSelectTheNativeStateAndAction() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(
      context,
      update("input", true) + mapOf(
        "activity_line_0" to "Input	Choose an icon	Project",
      )
    )
    val card = manager.activeNotifications.single().notification
    assertEquals("Choose an icon", card.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals("Answer", card.actions.first().title.toString())
    assertEquals(2, card.actions.size)
    assertFalse(card.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
  }

  @Test
  fun multipleAgentsUseTheChipCountAndFinishedThreadsRemainInTheSummary() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val data = update("multiple", true) + mapOf(
      "activity_line_0" to "Working	Build feature	Project",
      "activity_line_1" to "Done	Write tests	Project",
      "activity_phase" to "running",
      "activity_active_count" to "2",
    )
    AgentNotifications.receive(context, data)
    val card = manager.activeNotifications.single().notification
    assertEquals("2 live", card.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT))
    assertEquals("2 working", card.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals("Project · 2 active", card.extras.getString(Notification.EXTRA_SUB_TEXT))
    assertEquals(
      "Working Build feature\nDone Write tests",
      card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    )
    AgentNotifications.receive(context, data + ("activity_active_count" to "15"))
    assertEquals(
      "9+ live",
      manager.activeNotifications.single().notification.extras
        .getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)
    )
    AgentNotifications.receive(
      context,
      data + mapOf(
        "activity_line_0" to "Failed	Build feature	Project",
        "activity_phase" to "failed",
        "activity_active_count" to "0",
        "active" to "false",
        "activity_expires_at" to (System.currentTimeMillis() + 900000).toString(),
      )
    )
    val finished = manager.activeNotifications.single().notification
    assertEquals(
      "Finished, 1 failed",
      finished.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    )
    assertEquals("Project · 2 threads", finished.extras.getString(Notification.EXTRA_SUB_TEXT))
  }

  private fun assertTimeout(card: Notification, expected: LongRange) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      assertTrue(card.timeoutAfter in expected)
      assertTrue(
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty()
      )
    } else {
      val alarm = shadowOf(
        context.getSystemService(AlarmManager::class.java)
      ).scheduledAlarms.single()
      assertTrue(alarm.triggerAtTime - System.currentTimeMillis() in expected)
      assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
    }
  }

  @Test
  @Config(sdk = [24, 25])
  fun legacyExpiryRemovesOnlyTheCardAndCannotRemoveANewerRun() {
    val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    val expiresAt = System.currentTimeMillis() + 900000
    AgentNotifications.receive(
      context,
      update("done", false) + ("activity_expires_at" to expiresAt.toString())
    )
    val oldExpiry = alarms.scheduledAlarms.single().operation!!
    AgentNotifications.receive(context, update("next-run", true))
    assertEquals(1, alarms.scheduledAlarms.size)
    val receiver = AgentActivityExpiryReceiver()
    receiver.onReceive(context, shadowOf(oldExpiry).savedIntent)
    AgentNotifications.expire(context, expiresAt + 60_000)
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    AgentNotifications.expire(context, expiresAt + 2 * 60 * 60 * 1000L)
    assertEquals(2, manager.activeNotifications.count { it.tag == "t3-agent-alert" })
    assertTrue(manager.activeNotifications.none { it.tag == "t3-agent-activity" })
    assertTrue(alarms.scheduledAlarms.isEmpty())
  }

  @Test
  @Config(sdk = [24, 25])
  fun legacyExpiryIsCancelledOnDismissDisableAndSignOut() {
    val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    AgentNotifications.receive(context, update("work", true))
    AgentNotifications.dismiss(context)
    assertTrue(alarms.scheduledAlarms.isEmpty())
    AgentNotifications.configure(context, "device", "user", "t3code-dev", false)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, update("work", true))
    assertEquals(1, alarms.scheduledAlarms.size)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", false)
    assertTrue(alarms.scheduledAlarms.isEmpty())
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, update("work", true))
    AgentNotifications.clear(context)
    assertTrue(alarms.scheduledAlarms.isEmpty())
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun alertsAndActivityUseVersionAppropriatePriorityAndPromotion() {
    AgentNotifications.receive(context, update("work", true))
    val alert = manager.activeNotifications.single { it.tag == "t3-agent-alert" }.notification
    val card = manager.activeNotifications.single { it.tag == "t3-agent-activity" }.notification
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      assertEquals(Notification.PRIORITY_HIGH, alert.priority)
      assertTrue(alert.defaults and Notification.DEFAULT_SOUND != 0)
      assertEquals(Notification.PRIORITY_LOW, card.priority)
      assertEquals(0, card.defaults)
    } else {
      assertEquals(
        NotificationManager.IMPORTANCE_HIGH,
        manager.getNotificationChannel(alert.channelId).importance
      )
      assertEquals(
        NotificationManager.IMPORTANCE_LOW,
        manager.getNotificationChannel(card.channelId).importance
      )
    }
    assertTrue(NotificationCompat.isRequestPromotedOngoing(card))
    assertFalse(NotificationCompat.isRequestPromotedOngoing(alert))
    // Robolectric's API 36 image predates the shipped Live Update rules;
    // its hasPromotableCharacteristics() incorrectly requires colorization.
    assertFalse(card.extras.getBoolean(NotificationCompat.EXTRA_COLORIZED))
    assertTrue(card.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertEquals(Notification.VISIBILITY_PRIVATE, card.visibility)
  }

  @Test
  fun liveUpdateChipChangesWithActivityAndShowsDoneOnCompletion() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("work", true))
    assertEquals(
      "Active",
      manager.activeNotifications.single().notification.extras
        .getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)
    )
    AgentNotifications.receive(context, update("input", true) + ("activity_chip" to "Review"))
    val activeCard = manager.activeNotifications.single().notification
    assertEquals(
      "Review",
      activeCard.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)
    )
    assertTrue(NotificationCompat.isRequestPromotedOngoing(activeCard))

    AgentNotifications.receive(
      context,
      update("done", false) + mapOf(
        "activity_chip" to "Review",
        "activity_expires_at" to (System.currentTimeMillis() + 900000).toString()
      )
    )
    val finishedCard = manager.activeNotifications.single().notification
    val promoted = Build.VERSION.SDK_INT >= 36
    assertEquals(
      if (promoted) "Done" else null,
      finishedCard.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)
    )
    assertEquals(promoted, NotificationCompat.isRequestPromotedOngoing(finishedCard))
    assertEquals(promoted, finishedCard.flags and Notification.FLAG_ONGOING_EVENT != 0)
  }

  @Test
  fun blankTitleCannotMakeAnActivityIneligibleForPromotion() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("work", true) + ("activity_title" to "  "))
    assertEquals(
      "Agent activity",
      manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE)
    )
  }

  @Test
  fun expiredMalformedAndFutureMessagesCannotDisplayOrPoisonLaterUpdates() {
    val invalid = update("invalid", true)
    AgentNotifications.receive(context, invalid - "updated_at")
    AgentNotifications.receive(context, invalid + ("updated_at" to "invalid"))
    AgentNotifications.receive(
      context,
      invalid + ("updated_at" to (System.currentTimeMillis() - 600001).toString())
    )
    AgentNotifications.receive(
      context,
      invalid + ("updated_at" to (System.currentTimeMillis() + 3600000).toString())
    )
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.receive(context, update("valid", true))
    assertEquals(2, manager.activeNotifications.size)
  }

  @Test
  fun deniedPermissionDoesNotConsumeAnAlertBeforeTheUserAllowsNotifications() {
    shadowOf(manager).setNotificationsEnabled(false)
    val message = update("attention", true)
    AgentNotifications.receive(context, message)
    assertTrue(manager.activeNotifications.isEmpty())
    shadowOf(manager).setNotificationsEnabled(true)
    AgentNotifications.receive(context, message)
    assertEquals(2, manager.activeNotifications.size)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36], manifest = Config.NONE)
class AgentNotificationChannelsTest {
  @Test
  fun mutedAlertChannelKeepsTheSameActivityThroughCompletionUntilDismissedOrExpired() {
    val context = RuntimeEnvironment.getApplication()
    val manager = context.getSystemService(NotificationManager::class.java)
    shadowOf(manager).setNotificationsEnabled(true)
    AgentNotifications.clear(context)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    val running = mapOf(
      "device_id" to "device",
      "user_id" to "user",
      "updated_at" to System.currentTimeMillis().toString(),
      "active" to "true",
      "activity_title" to "Agent activity"
    )
    manager.createNotificationChannel(
      NotificationChannel("agent-alerts", "Agent alerts", NotificationManager.IMPORTANCE_NONE)
    )
    AgentNotifications.receive(context, running)
    val initialCard = manager.activeNotifications.single()
    assertEquals("t3-agent-activity", initialCard.tag)

    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(
      context,
      running + mapOf(
        "activity_phase" to "waiting_for_approval",
        "activity_attention_count" to "1"
      )
    )
    val card = manager.activeNotifications.single().notification
    assertEquals("Approve", card.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT))

    val finished = running + mapOf(
      "active" to "false",
      "activity_phase" to "completed",
      "activity_line_0" to "Done\tTest thread\tTest project",
      "activity_expires_at" to (System.currentTimeMillis() + 900000).toString()
    )
    AgentNotifications.receive(context, finished)
    AgentNotifications.receive(context, finished)
    val completedCard = manager.activeNotifications.single()
    assertEquals(initialCard.id, completedCard.id)
    assertEquals(initialCard.tag, completedCard.tag)
    assertEquals("agent-activity", completedCard.notification.channelId)
    assertEquals(
      "Test thread",
      completedCard.notification.extras.getString(Notification.EXTRA_TITLE)
    )
    assertEquals(
      "Done Test project",
      completedCard.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    )
    val promoted = Build.VERSION.SDK_INT >= 36
    assertEquals(promoted, NotificationCompat.isRequestPromotedOngoing(completedCard.notification))
    assertEquals(
      promoted,
      completedCard.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    )
    assertEquals(
      if (promoted) "Done" else null,
      completedCard.notification.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)
    )

    if (promoted) {
      val dismiss = completedCard.notification.actions.single()
      assertEquals("Dismiss", dismiss.title.toString())
      AgentActivityDismissReceiver().onReceive(context, shadowOf(dismiss.actionIntent).savedIntent)
    } else {
      AgentNotifications.dismiss(context)
    }
    AgentNotifications.receive(context, finished)
    assertTrue(manager.activeNotifications.isEmpty())

    AgentNotifications.receive(context, running)
    assertEquals("t3-agent-activity", manager.activeNotifications.single().tag)
    AgentNotifications.receive(
      context,
      finished + ("activity_expires_at" to (System.currentTimeMillis() - 1).toString())
    )
    assertTrue(manager.activeNotifications.isEmpty())
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 33, 36], manifest = Config.NONE)
class AgentNotificationsCompletionTest : AgentNotificationsFixture() {
  private fun finished(alertId: String, expiresAt: Long) = update(alertId, false) + mapOf(
    "activity_line_0" to "Done\tTest thread\tTest project",
    "activity_phase" to "completed",
    "activity_expires_at" to expiresAt.toString(),
  )

  @Test
  fun backgroundCompletionAlertsAndClearsOngoingActivity() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("running", true))
    lifecycle.currentState = Lifecycle.State.CREATED

    val expiresAt = System.currentTimeMillis() + 900000
    val completion = finished("completion", expiresAt)
    AgentNotifications.receive(context, completion)
    AgentNotifications.receive(context, completion)
    AgentNotifications.receive(context, completion.filterKeys { !it.startsWith("alert_") })

    val alert = manager.activeNotifications.single()
    assertEquals("t3-agent-alert", alert.tag)
    assertEquals("Test thread", alert.notification.extras.getString(Notification.EXTRA_TITLE))
    assertFalse(alert.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    AgentNotifications.receive(context, update("next-run", true))
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })

    // An alert for another thread, or a card that also lists another result, stays.
    AgentNotifications.receive(
      context,
      finished("other-thread", expiresAt + 60000) + ("alert_path" to "/threads/other/thread")
    )
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    AgentNotifications.receive(
      context,
      finished("with-muted-result", expiresAt + 120000) +
        ("activity_line_1" to "Done\tMuted thread\tOther project")
    )
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
  }

  @Test
  @Config(sdk = [26, 33, 36])
  fun blockedAlertChannelKeepsTheFinishedCard() {
    manager.getNotificationChannel("agent-alerts").importance = NotificationManager.IMPORTANCE_NONE
    AgentNotifications.receive(
      context,
      finished("completion", System.currentTimeMillis() + 900000)
    )
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
  }

  @Test
  fun reorderedActivityDoesNotEraseNewerCardOrDropAnIndependentAlert() {
    val now = System.currentTimeMillis()
    AgentNotifications.receive(context, update("new", true) + ("updated_at" to now.toString()))
    AgentNotifications.receive(
      context,
      finished("older-alert", now + 900000) + ("updated_at" to (now - 1000).toString())
    )
    assertEquals(4, manager.activeNotifications.size)
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    // A newer replay of a finish can show its card before the original alert lands.
    val finish = finished("finish", now + 960000)
    AgentNotifications.receive(
      context,
      finish.filterKeys { !it.startsWith("alert_") } + ("updated_at" to (now + 2000).toString())
    )
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    AgentNotifications.receive(context, finish + ("updated_at" to (now + 1000).toString()))
    assertTrue(manager.activeNotifications.none { it.tag == "t3-agent-activity" })
    assertTrue(manager.activeNotifications.any { it.id == "finish".hashCode() })
    AgentNotifications.receive(
      context,
      finished("previous-run", now + 900000) + ("updated_at" to now.toString())
    )
    AgentNotifications.receive(
      context,
      finish.filterKeys { !it.startsWith("alert_") } + ("updated_at" to (now + 3000).toString())
    )
    assertTrue(manager.activeNotifications.none { it.tag == "t3-agent-activity" })
    shadowOf(manager).setNotificationsEnabled(false)
    AgentNotifications.receive(context, update("revoked-permission", true))
    assertEquals(5, manager.activeNotifications.size)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalAgentActivityRejectedSourcesTest {
  @Test
  fun takesEachRejectedSourceOnce() {
    val context = RuntimeEnvironment.getApplication()
    LocalAgentActivityService.markRejected(context, "env-a")
    LocalAgentActivityService.markRejected(context, "env-b")
    LocalAgentActivityService.markRejected(context, "env-a")
    assertEquals(setOf("env-a", "env-b"), LocalAgentActivityService.takeRejectedSources(context).toSet())
    assertEquals(emptyList<String>(), LocalAgentActivityService.takeRejectedSources(context))
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], manifest = Config.NONE)
class LocalAgentActivityMergeTest : AgentNotificationsFixture() {
  private val hourMs = 60 * 60 * 1000L

  private fun row(phase: String, status: String, title: String, minute: Int = 0) = LocalActivityRow(
    phase,
    status,
    title,
    "Project",
    "/threads/env/$title",
    "2026-01-01T00:%02d:00.000Z".format(minute),
    System.currentTimeMillis() + hourMs
  )

  private fun source(vararg rows: LocalActivityRow) = LocalActivitySource(
    0,
    rows.count { it.phase != "completed" && it.phase != "failed" },
    rows.toList()
  )

  private fun event(updatedAt: Long, vararg rows: LocalActivityRow) = mapOf(
    "t3_kind" to "agent_activity",
    "updated_at" to updatedAt.toString(),
    "activity_active_count" to source(*rows).activeCount.toString(),
    "activity_rows" to JSONArray(
      rows.map {
        JSONObject().put("phase", it.phase).put("status", it.status)
          .put("threadTitle", it.threadTitle).put("projectTitle", it.projectTitle)
          .put("deepLink", it.deepLink).put("updatedAt", it.updatedAt)
          .put("expiresAt", it.expiresAt)
      }
    ).toString()
  )

  private fun lines(data: Map<String, String>) =
    (0..5).mapNotNull { data["activity_line_$it"]?.split('\t')?.get(1) }

  private fun card() = manager.activeNotifications.singleOrNull { it.tag == "t3-agent-activity" }
    ?.notification

  @Test
  fun mergeRanksEveryServersRowsLikeTheRelay() {
    val merged = mergedActivityData(
      listOf(
        source(row("running", "Working", "a1"), row("running", "Working", "a2"),
          row("completed", "Done", "a3", minute = 1)),
        source(
          row("waiting_for_approval", "Approval", "b1"),
          row("failed", "Failed", "b2", minute = 2)
        ),
      )
    )
    assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), lines(merged))
    assertEquals("3 active agents · 1 needs attention", merged["activity_title"])
    assertEquals("Review", merged["activity_chip"])
    assertEquals("waiting_for_approval", merged["activity_phase"])
    assertEquals("Approval: b1 · Project", merged["activity_body"])
    assertEquals("/threads/env/b1", merged["activity_path"])

    val busy = mergedActivityData(
      listOf(
        source(*(1..5).map { row("running", "Working", "a$it") }.toTypedArray()),
        source(row("waiting_for_input", "Input", "b1"), row("completed", "Done", "b2")),
      )
    )
    assertEquals(listOf("b1", "a1", "a2", "a3", "a4"), lines(busy))
    assertEquals("6 active agents · 1 needs attention", busy["activity_title"])

    val finished = mergedActivityData(
      listOf(
        source(row("completed", "Done", "a1", minute = 1)),
        source(row("completed", "Done", "b1", minute = 3), row("failed", "Failed", "b2")),
      )
    )
    assertEquals(listOf("b2", "b1", "a1"), lines(finished))
    assertEquals("false", finished["active"])
    assertEquals("Agent work failed", finished["activity_title"])
  }

  @Test
  fun eachServerOrdersOnlyItsOwnUpdatesAndTheCardMergesThem() {
    val now = System.currentTimeMillis()
    AgentNotifications.receiveLocal(
      context, "a", event(now, row("running", "Working", "Alpha")), "t3code-dev", true
    )
    // B's clock runs an hour behind A's; its update still belongs on the card.
    AgentNotifications.receiveLocal(
      context,
      "b",
      event(now - hourMs, row("waiting_for_input", "Input", "Beta")),
      "t3code-dev",
      true
    )
    val both = card()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    assertEquals(listOf("Input Beta", "Working Alpha"), both.lines())

    AgentNotifications.receiveLocal(context, "a", event(now - 1000), "t3code-dev", true)
    assertTrue(
      card()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Alpha")
    )

    AgentNotifications.receiveLocal(context, "b", event(now - hourMs + 1), "t3code-dev", true)
    assertEquals("Alpha", card()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

    AgentNotifications.retainLocalSources(context, setOf("b"))
    assertEquals(null, card())
  }

  @Test
  fun aCoveredFinishStaysHiddenWhenAnotherServerRepaintsTheCard() {
    val now = System.currentTimeMillis()
    AgentNotifications.receiveLocal(
      context,
      "a",
      event(now, row("completed", "Done", "Alpha")) + mapOf(
        "alert_id" to "alpha-done",
        "alert_title" to "Alpha",
        "alert_body" to "Done: Project",
        "alert_path" to "/threads/env/Alpha",
      ),
      "t3code-dev",
      true
    )
    assertEquals(null, card())
    assertEquals("alpha-done".hashCode(), manager.activeNotifications.single().id)

    AgentNotifications.receiveLocal(context, "b", event(now - hourMs), "t3code-dev", true)
    assertEquals(null, card())

    AgentNotifications.receiveLocal(
      context, "b", event(now - hourMs + 1, row("running", "Working", "Beta")), "t3code-dev", true
    )
    assertEquals(
      listOf("Working Beta", "Done Alpha"),
      card()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().lines()
    )
  }
}
