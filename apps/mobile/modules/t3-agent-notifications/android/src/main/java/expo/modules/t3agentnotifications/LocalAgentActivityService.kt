package expo.modules.t3agentnotifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/** Holds one SSE connection per saved server so agent activity arrives without FCM. */
class LocalAgentActivityService : Service() {
  private data class Source(val id: String, val urls: List<String>, val token: String)

  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // Heartbeats arrive every 20 seconds, so longer silence means the connection is dead.
    .readTimeout(70, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
  private val workers = HashMap<String, Worker>()
  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      synchronized(workers) { workers.values.forEach { it.reconnectNow() } }
    }
  }

  override fun onCreate() {
    super.onCreate()
    createChannel()
    getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val sources = parseSources(prefs.getString("sources", "[]").orEmpty())
    val scheme = prefs.getString("scheme", "t3code") ?: "t3code"
    val ongoing = prefs.getBoolean("ongoing", false)
    startInForeground(sources.size, scheme)
    reconcile(sources, scheme, ongoing)
    if (sources.isEmpty()) stopSelf()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    synchronized(workers) {
      workers.values.forEach { it.stop() }
      workers.clear()
    }
    super.onDestroy()
  }

  private fun reconcile(sources: List<Source>, scheme: String, ongoing: Boolean) {
    synchronized(workers) {
      val byId = sources.associateBy { it.id }
      // A parked worker is replaced so that the next sources update retries it.
      val stale = workers.filter { (id, worker) ->
        !worker.matches(byId[id], scheme, ongoing)
      }
      stale.forEach { (id, worker) ->
        worker.stop()
        workers.remove(id)
      }
      sources.forEach { source ->
        if (!workers.containsKey(source.id)) {
          workers[source.id] = Worker(source, scheme, ongoing).also { it.start() }
        }
      }
    }
  }

  private fun startInForeground(count: Int, scheme: String) {
    val servers = if (count == 1) "server" else "servers"
    val notification = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.agent_activity_mark)
      .setContentTitle("T3 Code")
      .setContentText("Listening to $count $servers")
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setOngoing(true)
      .setSilent(true)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setShowWhen(false)
      .setContentIntent(launchIntent(scheme))
      .build()
    if (Build.VERSION.SDK_INT >= 34) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun launchIntent(scheme: String): PendingIntent? {
    val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    intent.setAction(Intent.ACTION_VIEW).setData(Uri.parse("$scheme://"))
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
      this,
      NOTIFICATION_ID,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Server connection", NotificationManager.IMPORTANCE_MIN)
      )
    }
  }

  private inner class Worker(
    val source: Source,
    val scheme: String,
    val ongoing: Boolean,
  ) {
    @Volatile var parked = false
      private set
    @Volatile private var stopped = false
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var call: Call? = null
    // Rotates through the server's routes so leaving one network falls back to another.
    @Volatile private var urlIndex = 0
    private val thread = Thread({ loop() }, "t3-local-activity")

    fun matches(current: Source?, currentScheme: String, currentOngoing: Boolean) =
      current == source && currentScheme == scheme && currentOngoing == ongoing && !parked

    fun start() = thread.start()

    fun stop() {
      stopped = true
      call?.cancel()
      thread.interrupt()
    }

    fun reconnectNow() {
      backoffMs = INITIAL_BACKOFF_MS
      urlIndex = 0
      call?.cancel()
      thread.interrupt()
    }

    private fun loop() {
      while (!stopped) {
        if (connect()) {
          parked = true
          return
        }
        if (stopped) return
        urlIndex = (urlIndex + 1) % source.urls.size
        // Try every route once before backing off.
        if (urlIndex != 0) continue
        val delayMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        try {
          Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
          // Woken by stop or reconnectNow; the loop condition decides what happens next.
        }
      }
    }

    /** Returns true when the server rejected the credentials, which should not be retried. */
    private fun connect(): Boolean {
      val base = source.urls[urlIndex % source.urls.size].trimEnd('/')
      val request = Request.Builder()
        .url("$base/api/agent-activity/stream?live=${if (ongoing) 1 else 0}&notify=1")
        .header("Authorization", "Bearer ${source.token}")
        .header("Accept", "text/event-stream")
        .build()
      val next = client.newCall(request)
      call = next
      // stop() may have read a null call just before it was stored.
      if (stopped) next.cancel()
      try {
        next.execute().use { response ->
          when {
            response.code == 401 || response.code == 403 -> {
              Log.w(TAG, "Server ${source.id} rejected the token (HTTP ${response.code})")
              // A replaced worker's token was revoked on purpose when its successor was issued.
              if (!stopped) markRejected(applicationContext, source.id)
              return true
            }
            !response.isSuccessful -> {
              Log.w(TAG, "Server ${source.id} returned HTTP ${response.code}")
            }
            else -> {
              Log.i(TAG, "Connected to server ${source.id} via route $urlIndex")
              backoffMs = INITIAL_BACKOFF_MS
              response.body?.let { body ->
                body.charStream().buffered().use { readEvents(it) }
              }
              Log.i(TAG, "Disconnected from server ${source.id}")
            }
          }
        }
      } catch (_: InterruptedException) {
        // stop() and reconnectNow() interrupt this thread; OkHttp can surface that from its pool.
        Thread.currentThread().interrupt()
      } catch (error: Exception) {
        // Any escape here kills the app process, so every failure becomes a reconnect.
        if (!stopped) {
          Log.w(TAG, "Connection to server ${source.id} failed: ${error.javaClass.simpleName}")
        }
      } finally {
        call = null
      }
      return false
    }

    private fun readEvents(reader: BufferedReader) {
      val data = mutableListOf<String>()
      while (true) {
        val line = reader.readLine() ?: break
        when {
          line.isEmpty() -> {
            if (data.isNotEmpty()) dispatch(data.joinToString("\n"))
            data.clear()
          }
          line.startsWith(":") -> Unit
          line.startsWith("data:") -> data.add(line.removePrefix("data:").removePrefix(" "))
        }
      }
    }

    private fun dispatch(json: String) {
      runCatching {
        val event = JSONObject(json)
        val fields = event.keys().asSequence().associateWith { event.optString(it) }
        AgentNotifications.receiveLocal(applicationContext, source.id, fields, scheme, ongoing)
      }.onFailure {
        Log.w(TAG, "Dropped an event from server ${source.id}: ${it.javaClass.simpleName}")
      }
    }
  }

  companion object {
    private const val PREFS = "t3-local-agent-activity"
    private const val CHANNEL = "agent-connection"
    private const val NOTIFICATION_ID = 73002
    private const val TAG = "T3LocalActivity"
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val REJECTED = "rejected"

    fun setSources(context: Context, sourcesJson: String, scheme: String, ongoing: Boolean) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("sources", sourcesJson)
        .putString("scheme", scheme)
        .putBoolean("ongoing", ongoing)
        .apply()
      val sources = parseSources(sourcesJson)
      AgentNotifications.retainLocalSources(context, sources.mapTo(HashSet()) { it.id })
      val intent = Intent(context, LocalAgentActivityService::class.java)
      if (sources.isNotEmpty()) {
        try {
          context.startForegroundService(intent)
        } catch (error: IllegalStateException) {
          // Android 12+ refuses background starts; the next foreground sync applies the sources.
          Log.w(TAG, "Deferred service start: ${error.javaClass.simpleName}")
        }
      } else {
        context.stopService(intent)
      }
    }

    private val rejectedLock = Any()

    internal fun markRejected(context: Context, id: String) = synchronized(rejectedLock) {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val rejected = prefs.getStringSet(REJECTED, emptySet()).orEmpty() + id
      prefs.edit().putStringSet(REJECTED, rejected).apply()
    }

    /** Source ids whose token a server refused since the last call, so the app can replace them. */
    fun takeRejectedSources(context: Context): List<String> = synchronized(rejectedLock) {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val rejected = prefs.getStringSet(REJECTED, emptySet()).orEmpty().toList()
      if (rejected.isNotEmpty()) prefs.edit().remove(REJECTED).apply()
      rejected
    }

    private fun parseSources(json: String): List<Source> = try {
      val array = JSONArray(json)
      (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        val token = item.optString("token")
        val urlArray = item.optJSONArray("urls") ?: JSONArray()
        val urls = (0 until urlArray.length()).map { urlArray.optString(it) }.filter { it.isNotEmpty() }
        if (id.isEmpty() || urls.isEmpty() || token.isEmpty()) null else Source(id, urls, token)
      }
    } catch (error: JSONException) {
      Log.w(TAG, "Ignoring malformed local source list: ${error.javaClass.simpleName}")
      emptyList()
    }
  }
}
