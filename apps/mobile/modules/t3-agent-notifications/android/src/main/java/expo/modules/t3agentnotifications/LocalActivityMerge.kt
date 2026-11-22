package expo.modules.t3agentnotifications

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class LocalActivityRow(
  val phase: String,
  val status: String,
  val threadTitle: String,
  val projectTitle: String,
  val deepLink: String,
  val updatedAt: String,
  val expiresAt: Long
)

/** One server's latest card. [updatedAt] is that server's clock and orders only its updates. */
internal data class LocalActivitySource(
  val updatedAt: Long,
  val activeCount: Int,
  val rows: List<LocalActivityRow>
) {
  val expiresAt get() = rows.maxOfOrNull { it.expiresAt } ?: 0L
}

private const val MAX_ACTIVITY_ROWS = 5

private fun activityPhasePriority(phase: String) = when (phase) {
  "waiting_for_approval", "waiting_for_input" -> 0
  "failed" -> 1
  "starting", "running" -> 2
  else -> 3
}

private fun isTerminalPhase(phase: String) = phase == "completed" || phase == "failed"

private val whitespace = Regex("(?U)\\s+")

private fun clean(value: String) = value.replace(whitespace, " ").trim()

/**
 * Merges every server's rows into the relay's single card. Mirrors makeAggregateState's row
 * selection and androidActivityData in packages/shared/src/agentActivity; keep them in step.
 */
internal fun mergedActivityData(sources: Collection<LocalActivitySource>): Map<String, String> {
  val all = sources.flatMap { it.rows }
  val active = all.filterNot { isTerminalPhase(it.phase) }
  val terminal = all.filter { isTerminalPhase(it.phase) }.sortedByDescending { it.updatedAt }
  val displayed =
    (active.sortedBy { activityPhasePriority(it.phase) }.take(MAX_ACTIVITY_ROWS) + terminal)
      .take(MAX_ACTIVITY_ROWS)
  val rows = displayed.sortedBy { activityPhasePriority(it.phase) }
  val activeCount = sources.sumOf { it.activeCount }
  val attentionCount = rows.count { activityPhasePriority(it.phase) == 0 }
  val failed = rows.any { it.phase == "failed" }
  val hero = rows.firstOrNull()
  val title = when {
    activeCount > 0 -> {
      val attention = if (attentionCount > 0) {
        " · $attentionCount need${if (attentionCount == 1) "s" else ""} attention"
      } else {
        ""
      }
      "$activeCount active agent${if (activeCount == 1) "" else "s"}$attention"
    }
    failed -> "Agent work failed"
    else -> "Agent work completed"
  }
  return buildMap {
    put("active", (activeCount > 0).toString())
    val chip = if (attentionCount > 0) "Review" else "Active"
    put("activity_chip", if (activeCount > 0) chip else "")
    put("activity_title", title)
    put("activity_phase", hero?.phase.orEmpty())
    put("activity_active_count", activeCount.toString())
    put("activity_attention_count", attentionCount.toString())
    put(
      "activity_body",
      hero?.let { "${it.status}: ${clean(it.threadTitle)} · ${clean(it.projectTitle)}" }.orEmpty()
    )
    rows.forEachIndexed { index, row ->
      put(
        "activity_line_$index",
        listOf(row.status, clean(row.threadTitle), clean(row.projectTitle)).joinToString("\t")
      )
    }
    put("activity_path", hero?.deepLink ?: "/")
    put("activity_expires_at", maxOf(0L, rows.maxOfOrNull { it.expiresAt } ?: 0L).toString())
  }
}

/** Reads a server event's structured rows; a server without any reports an empty source. */
internal fun localActivitySource(data: Map<String, String>, updatedAt: Long) =
  LocalActivitySource(
    updatedAt,
    data["activity_active_count"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    parseRows(data["activity_rows"])
  )

private fun parseRows(json: String?): List<LocalActivityRow> {
  val array = try {
    JSONArray(json ?: return emptyList())
  } catch (_: JSONException) {
    return emptyList()
  }
  return (0 until array.length()).mapNotNull { index ->
    val row = array.optJSONObject(index) ?: return@mapNotNull null
    LocalActivityRow(
      row.optString("phase"),
      row.optString("status"),
      row.optString("threadTitle"),
      row.optString("projectTitle"),
      row.optString("deepLink"),
      row.optString("updatedAt"),
      row.optLong("expiresAt")
    )
  }
}

internal fun encodeLocalSources(sources: Map<String, LocalActivitySource>): String =
  JSONObject().apply {
    sources.forEach { (id, source) ->
      put(
        id,
        JSONObject()
          .put("updatedAt", source.updatedAt)
          .put("activeCount", source.activeCount)
          .put(
            "rows",
            JSONArray().apply {
              source.rows.forEach {
                put(
                  JSONObject().put("phase", it.phase).put("status", it.status)
                    .put("threadTitle", it.threadTitle).put("projectTitle", it.projectTitle)
                    .put("deepLink", it.deepLink).put("updatedAt", it.updatedAt)
                    .put("expiresAt", it.expiresAt)
                )
              }
            }
          )
      )
    }
  }.toString()

internal fun decodeLocalSources(json: String?): MutableMap<String, LocalActivitySource> {
  val sources = sortedMapOf<String, LocalActivitySource>()
  val root = try {
    JSONObject(json ?: return sources)
  } catch (_: JSONException) {
    return sources
  }
  root.keys().forEach { id ->
    val source = root.optJSONObject(id) ?: return@forEach
    sources[id] = LocalActivitySource(
      source.optLong("updatedAt"),
      source.optInt("activeCount"),
      parseRows(source.optJSONArray("rows")?.toString())
    )
  }
  return sources
}
