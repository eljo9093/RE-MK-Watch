package eu.remk.coaching.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.degrees
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.expand
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.StateBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RE:MK 360° 4 Direct-Action Wear OS 5 Home-Kacheln (v37.24 Hyper-Chrono Final)
 * - 100 % tiefes Rauchglas (#090D15 auf True-Black #05070B) + gebogene ArcText-Kopfzeile am Rand!
 * - Echte In-Tile-Bedienung via ActionBuilders.LoadAction + StateBuilders.State:
 *   Jeder Aktions-Button (−2,5, +2,5, ✓ SATZ, 60s/90s/120s/+30s/Stopp, 💧+250 ml, Sport-Wechsel)
 *   arbeitet direkt AUF DER KACHEL — 0 App-Weiterleitung, 0 Aufblitzen, sofortige 35-ms-Vibration!
 */
class TileLogSetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        RemkTileHelper.vibrateClick(this)
        RemkTileHelper.handleDirectAction(this, "tile_log_set", prefs)
        RemkTileHelper.requestAllTilesRefresh(this)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
        Toast.makeText(applicationContext, "✓ Satz abgehakt!", Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

abstract class BaseOpenAppPageActivity(private val targetPage: Int) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(this, WearMainActivity::class.java).apply {
            putExtra("start_page", targetPage)
            putExtra("skip_home_hub", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }
}

class TileOpenWorkoutActivity : BaseOpenAppPageActivity(0)
class TileOpenNutritionActivity : BaseOpenAppPageActivity(1)
class TileOpenCardioActivity : BaseOpenAppPageActivity(4)

@Suppress("DEPRECATION")
object RemkTileHelper {

    const val TILE_RES_VERSION = "v37_25_neon_sapphire_wow"

    private val CLEAN_CARDIO_SPORTS = arrayOf(
        "Laufen", "Rad", "Indoor-Cycling", "Hybrid-Race", "Verein"
    )

    fun vibrateClick(context: Context) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(35L, 180))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(35L)
                }
            }
        } catch (e: Exception) {}
    }

    fun getSanitizedOpenKcal(prefs: android.content.SharedPreferences): Int {
        val raw = prefs.getInt("open_kcal", 770)
        val target = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        return if (raw > 4800) {
            prefs.edit().putInt("open_kcal", target).apply()
            target
        } else {
            raw.coerceAtLeast(0)
        }
    }

    fun formatGermanInt(value: Int): String {
        return try {
            NumberFormat.getIntegerInstance(Locale.GERMANY).format(value)
        } catch (e: Exception) {
            value.toString()
        }
    }

    fun extractClickedId(requestParams: RequestBuilders.TileRequest): String {
        val c1 = requestParams.currentState?.lastClickableId ?: ""
        if (c1.isNotEmpty()) return c1
        return requestParams.state?.lastClickableId ?: ""
    }

    fun requestAllTilesRefresh(context: Context) {
        try {
            val updater = TileService.getUpdater(context)
            updater.requestUpdate(RemkTileService::class.java)
            updater.requestUpdate(RemkTimerTileService::class.java)
            updater.requestUpdate(RemkNutritionTileService::class.java)
            updater.requestUpdate(RemkCardioShopTileService::class.java)
        } catch (e: Exception) {}
    }

    fun handleDirectAction(context: Context, actionId: String, prefs: android.content.SharedPreferences) {
        if (actionId.isEmpty()) return
        vibrateClick(context)
        val ed = prefs.edit()
        when (actionId) {
            "tile_kg_minus" -> {
                val cur = prefs.getFloat("cur_kg", 40f)
                val step = prefs.getFloat("weight_step", 2.5f)
                val nextKg = (cur - step).coerceAtLeast(0f)
                ed.putFloat("cur_kg", nextKg).commit()
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_kg_plus" -> {
                val cur = prefs.getFloat("cur_kg", 40f)
                val step = prefs.getFloat("weight_step", 2.5f)
                val nextKg = cur + step
                ed.putFloat("cur_kg", nextKg).commit()
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_log_set" -> {
                val dIdx = prefs.getInt("day_idx", 0)
                val exIdx = prefs.getInt("cur_ex_idx", 0)
                val exId = prefs.getString("cur_ex_id", "ex_0_0") ?: "ex_0_0"
                val exName = prefs.getString("cur_ex_name", "Schrägbankdrücken") ?: "Schrägbankdrücken"
                val sIdx = prefs.getInt("cur_set_idx", 1)
                val totS = prefs.getInt("cur_total_sets", 3).coerceAtLeast(1)
                val curKg = prefs.getFloat("cur_kg", 40f)
                val curReps = prefs.getInt("cur_reps", 10)
                val doneToday = prefs.getInt("done_sets_today", 0) + 1
                val movedVol = prefs.getInt("today_volume_kg", 0) + (curKg * curReps).toInt()
                ed.putInt("done_sets_today", doneToday)
                ed.putInt("today_volume_kg", movedVol)
                try {
                    val mapObj = org.json.JSONObject(prefs.getString("completed_sets_map_json", "{}") ?: "{}")
                    mapObj.put("${dIdx}_${exId}", sIdx.coerceAtMost(totS))
                    mapObj.put(exId, sIdx.coerceAtMost(totS))
                    ed.putString("completed_sets_map_json", mapObj.toString())
                } catch (e: Exception) {}
                val nextSet = if (sIdx < totS) sIdx + 1 else 1
                ed.putInt("cur_set_idx", nextSet)
                if (sIdx >= totS) {
                    ed.putInt("cur_ex_idx", (exIdx + 1) % 4)
                }
                val restSec = prefs.getInt("rest_target_sec", 90).coerceIn(30, 180)
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", restSec)
                ed.commit()

                val kgStr = if (curKg <= 0f) "BW" else if (curKg % 1f == 0f) "${curKg.toInt()}kg" else "${curKg.toString().replace(".", ",")}kg"
                RestTimerScheduler.scheduleRestEndAlarm(context, restSec, exName, nextSet, totS, kgStr, curReps)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_SET_COMPLETED",
                    "exId=$exId&setIdx=$sIdx&kg=$curKg&reps=$curReps&rir=2",
                    mapOf("exId" to exId, "setIdx" to sIdx, "kg" to curKg.toDouble(), "reps" to curReps, "rir" to 2)
                )
                try { TileService.getUpdater(context).requestUpdate(RemkTimerTileService::class.java) } catch (e: Exception) {}
            }
            "tile_rest_60", "tile_rest_90", "tile_rest_120" -> {
                val sec = when (actionId) { "tile_rest_60" -> 60; "tile_rest_120" -> 120; else -> 90 }
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", sec)
                ed.commit()
                RestTimerScheduler.scheduleRestEndAlarm(
                    context, sec,
                    prefs.getString("cur_ex_name", "Satzpause") ?: "Satzpause",
                    prefs.getInt("cur_set_idx", 1),
                    prefs.getInt("cur_total_sets", 3),
                    "${prefs.getFloat("cur_kg", 40f).toInt()}kg",
                    prefs.getInt("cur_reps", 10)
                )
            }
            "tile_rest_plus30" -> {
                val lastStart = prefs.getLong("last_rest_start_ms", 0L)
                val target = prefs.getInt("rest_target_sec", 90)
                val elapsed = if (lastStart > 0L) ((System.currentTimeMillis() - lastStart) / 1000L).toInt() else target
                val rem = (target - elapsed).coerceAtLeast(0) + 30
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", rem)
                ed.commit()
                RestTimerScheduler.scheduleRestEndAlarm(
                    context, rem,
                    prefs.getString("cur_ex_name", "Satzpause") ?: "Satzpause",
                    prefs.getInt("cur_set_idx", 1),
                    prefs.getInt("cur_total_sets", 3),
                    "${prefs.getFloat("cur_kg", 40f).toInt()}kg",
                    prefs.getInt("cur_reps", 10)
                )
            }
            "tile_rest_stop" -> {
                ed.putLong("last_rest_start_ms", 0L).commit()
                RestTimerScheduler.cancelRestEndAlarm(context)
            }
            "tile_water_250" -> {
                val curL = ((prefs.getFloat("drank_liters", 0.0f) + 0.25f) * 100f).toInt() / 100f
                ed.putFloat("drank_liters", curL).commit()
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_WATER_ADDED",
                    "addedMl=250&drankLiters=$curL",
                    mapOf("addedMl" to 250, "drankLiters" to curL.toDouble())
                )
            }
            "tile_cardio_next_sport" -> {
                val idx = (prefs.getInt("cardio_sport_idx", 0) + 1) % CLEAN_CARDIO_SPORTS.size
                ed.putInt("cardio_sport_idx", idx)
                ed.putString("cardio_sport_name", CLEAN_CARDIO_SPORTS[idx])
                ed.commit()
            }
        }
        RemkComplicationHelper.requestAllComplicationsRefresh(context)
    }

    /**
     * Echter In-Tile-Button (LoadAction + StateBuilders.State) — 100% ohne App-Weiterleitung!
     */
    fun buildInTileActionPill(
        label: String,
        clickId: String,
        bgColor: Int,
        txtColor: Int,
        borderColor: Int? = null,
        fontSp: Float = 13f,
        padH: Float = 14f,
        padV: Float = 8f,
        cornerRadiusDp: Float = 22f
    ): LayoutElementBuilders.LayoutElement {
        val loadClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LoadAction.Builder()
                    .setRequestState(StateBuilders.State.Builder().build())
                    .build()
            )
            .build()

        val mod = ModifiersBuilders.Modifiers.Builder()
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                    .build()
            )
            .setClickable(loadClickable)
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(bgColor))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(cornerRadiusDp)).build())
                    .build()
            )
        if (borderColor != null) {
            mod.setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(1.6f)).setColor(argb(borderColor)).build())
        }

        return LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(mod.build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(fontSp))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    fun buildLaunchActivityPill(
        pkg: String,
        targetClassName: String,
        label: String,
        clickId: String,
        bgColor: Int,
        txtColor: Int,
        borderColor: Int? = null,
        fontSp: Float = 13f,
        padH: Float = 18f,
        padV: Float = 8f,
        cornerRadiusDp: Float = 22f
    ): LayoutElementBuilders.LayoutElement {
        val launchClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(pkg)
                            .setClassName(targetClassName)
                            .build()
                    )
                    .build()
            )
            .build()

        val mod = ModifiersBuilders.Modifiers.Builder()
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                    .build()
            )
            .setClickable(launchClickable)
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(bgColor))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(cornerRadiusDp)).build())
                    .build()
            )
        if (borderColor != null) {
            mod.setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(1.3f)).setColor(argb(borderColor)).build())
        }

        return LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(mod.build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(fontSp))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    fun buildMacroProgressRow(label: String, eatenG: Int, targetG: Int, barColor: Int): LayoutElementBuilders.LayoutElement {
        val pct = (eatenG.toFloat() / targetG.coerceAtLeast(1).toFloat()).coerceIn(0.08f, 1.0f)
        val totalBarDp = 128f
        val fillDp = (totalBarDp * pct).coerceAtLeast(10f)

        val barTrack = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(totalBarDp))
            .setHeight(dp(5.8f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF141C2B.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(fillDp))
                    .setHeight(dp(5.8f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(barColor))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$label ${eatenG}g / ${targetG}g")
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(9.5f))
                            .setColor(argb(0xFFCBD5E1.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(1.5f)).build())
            .addContent(barTrack)
            .build()
    }

    /**
     * Hyper-Chrono Tile-Rahmen: Tiefes AMOLED-Schwarz (#05070B), gebogener ArcText oben am Rand,
     * schlanke seitliche Glow-Bögen links/rechts ohne Überdeckung des oberen ArcText!
     */
    fun wrapInHyperChronoRingTile(
        column: LayoutElementBuilders.Column,
        curvedTopHeader: String,
        curvedHeaderColor: Int,
        primaryArcColor: Int,
        primaryArcSweepDeg: Float,
        primaryAnchorDeg: Float = 215f,
        secondaryArcColor: Int? = null,
        secondaryArcSweepDeg: Float = 0f,
        secondaryAnchorDeg: Float = 40f,
        freshnessMs: Long = 15000L
    ): ListenableFuture<TileBuilders.Tile> {
        val leftTrackArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(215f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(105f))
                    .setThickness(dp(4.5f))
                    .setColor(argb(0xFF141C2B.toInt()))
                    .build()
            )
            .build()

        val rightTrackArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(40f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(105f))
                    .setThickness(dp(4.5f))
                    .setColor(argb(0xFF141C2B.toInt()))
                    .build()
            )
            .build()

        val primaryArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(primaryAnchorDeg))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(primaryArcSweepDeg.coerceIn(24f, 105f)))
                    .setThickness(dp(5.8f))
                    .setColor(argb(primaryArcColor))
                    .build()
            )
            .build()

        val curvedHeaderArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
            .addContent(
                LayoutElementBuilders.ArcText.Builder()
                    .setText(curvedTopHeader)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(9.5f))
                            .setColor(argb(curvedHeaderColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()

        val rootBoxBuilder = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(4f)).setEnd(dp(4f)).setTop(dp(4f)).setBottom(dp(4f))
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF05070B.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(leftTrackArc)
            .addContent(rightTrackArc)
            .addContent(primaryArc)
            .addContent(curvedHeaderArc)

        if (secondaryArcColor != null && secondaryArcSweepDeg > 1f) {
            val secondaryArc = LayoutElementBuilders.Arc.Builder()
                .setAnchorAngle(degrees(secondaryAnchorDeg))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .addContent(
                    LayoutElementBuilders.ArcLine.Builder()
                        .setLength(degrees(secondaryArcSweepDeg.coerceIn(20f, 105f)))
                        .setThickness(dp(5.8f))
                        .setColor(argb(secondaryArcColor))
                        .build()
                )
                .build()
            rootBoxBuilder.addContent(secondaryArc)
        }

        val innerBox = LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(13f)).setEnd(dp(13f)).setTop(dp(19f)).setBottom(dp(11f))
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()

        rootBoxBuilder.addContent(innerBox)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(TILE_RES_VERSION)
            .setFreshnessIntervalMillis(freshnessMs)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(rootBoxBuilder.build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }
}

/**
 * KACHEL 1: RE:MK 1. Kraft — Smoked-Sapphire #090D15 + Gebogener ArcText + Direkte In-Tile Bedienung!
 */
@Suppress("DEPRECATION")
class RemkTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        val liveBpm = prefs.getInt("last_live_bpm", 118).coerceIn(45, 220)
        val exName = prefs.getString("cur_ex_name", "Schrägbankdrücken") ?: "Schrägbankdrücken"
        val prevRef = prefs.getString("cur_prev_ref", "W1: 37,5×10") ?: "W1: 37,5×10"
        val setIdx = prefs.getInt("cur_set_idx", 1)
        val totalSets = prefs.getInt("cur_total_sets", 3).coerceAtLeast(1)
        val doneToday = prefs.getInt("done_sets_today", 0)
        val totToday = prefs.getInt("total_sets_today", 12).coerceAtLeast(1)
        val curKg = prefs.getFloat("cur_kg", 40.0f)
        val curReps = prefs.getInt("cur_reps", 10)
        val kgStr = if (curKg <= 0f) "BW" else if (curKg % 1.0f == 0.0f) "${curKg.toInt()}kg" else "${curKg.toString().replace(".", ",")}kg"
        val step = prefs.getFloat("weight_step", 2.5f)
        val stepStr = if (step % 1f == 0f) "${step.toInt()}" else step.toString().replace(".", ",")

        val sweep = ((doneToday.toFloat() / totToday.toFloat()) * 105f).coerceIn(32f, 105f)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                RemkTileHelper.buildLaunchActivityPill(
                    packageName, TileOpenWorkoutActivity::class.java.name,
                    exName.take(18), "open_workout_title",
                    0x00000000, 0xFFF8FAFC.toInt(), null, 13.5f, 6f, 1f, 8f
                )
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Satz $setIdx/$totalSets • ${prevRef.take(12)}")
                    .setMaxLines(1)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10f)).setColor(argb(0xFF00E5FF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        RemkTileHelper.buildInTileActionPill(
                            "−$stepStr", "tile_kg_minus",
                            0xFF060910.toInt(), 0xFFFF6B00.toInt(), 0xFFFF6B00.toInt(), 12f, 9f, 9f, 22f
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(
                        RemkTileHelper.buildLaunchActivityPill(
                            packageName, TileOpenWorkoutActivity::class.java.name,
                            "$kgStr × $curReps", "open_workout_val",
                            0xFF060910.toInt(), 0xFFFFFFFF.toInt(), 0xFF334155.toInt(), 13f, 9f, 9f, 22f
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(
                        RemkTileHelper.buildInTileActionPill(
                            "+$stepStr", "tile_kg_plus",
                            0xFF060910.toInt(), 0xFFFF6B00.toInt(), 0xFFFF6B00.toInt(), 12f, 9f, 9f, 22f
                        )
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(
                RemkTileHelper.buildInTileActionPill(
                    "✓ SATZ $setIdx", "tile_log_set",
                    0xFF14F195.toInt(), 0xFF05070B.toInt(), null, 13.5f, 26f, 9f, 24f
                )
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            curvedTopHeader = "$nowClock • ● LIVE • ❤️ $liveBpm",
            curvedHeaderColor = 0xFF94A3B8.toInt(),
            primaryArcColor = 0xFFFF6B00.toInt(),
            primaryArcSweepDeg = sweep,
            primaryAnchorDeg = 215f,
            secondaryArcColor = 0xFFFF6B00.toInt(),
            secondaryArcSweepDeg = sweep * 0.85f,
            secondaryAnchorDeg = 40f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 2: RE:MK 2. Timer — Smoked-Sapphire #090D15 + Gebogener ArcText + Direkte In-Tile Steuerung!
 */
@Suppress("DEPRECATION")
class RemkTimerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        val lastStart = prefs.getLong("last_rest_start_ms", 0L)
        val targetSec = prefs.getInt("rest_target_sec", 90).coerceAtLeast(30)
        val elapsed = if (lastStart > 0L) ((System.currentTimeMillis() - lastStart) / 1000L).toInt() else targetSec
        val remSec = (targetSec - elapsed).coerceAtLeast(0)
        val displaySec = if (remSec > 0) remSec else targetSec
        val m = (displaySec / 60).toString().padStart(2, '0')
        val s = (displaySec % 60).toString().padStart(2, '0')
        val liveBpm = prefs.getInt("last_live_bpm", 116).coerceIn(45, 220)
        val isReady = (liveBpm < 122)

        val sweep = if (remSec > 0) ((remSec.toFloat() / targetSec.toFloat()) * 105f).coerceIn(24f, 105f) else 86f

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$m:$s")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(34f)).setColor(argb(0xFFF8FAFC.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(10f)).setEnd(dp(10f)).setTop(dp(3f)).setBottom(dp(3f)).build())
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(0xFF060910.toInt()))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(14f)).build())
                                    .build()
                            )
                            .setBorder(
                                ModifiersBuilders.Border.Builder()
                                    .setWidth(dp(1.2f))
                                    .setColor(argb(if (isReady) 0xFF14F195.toInt() else 0xFFF59E0B.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(if (isReady) "💚 $liveBpm BPM • Bereit" else "❤️ $liveBpm BPM • Erholung")
                            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10f)).setColor(argb(if (isReady) 0xFF14F195.toInt() else 0xFFF59E0B.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildInTileActionPill("60s", "tile_rest_60", 0xFF060910.toInt(), 0xFFF8FAFC.toInt(), if (targetSec == 60) 0xFFFF6B00.toInt() else 0xFF334155.toInt(), 11.5f, 9f, 7f, 18f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(RemkTileHelper.buildInTileActionPill("90s", "tile_rest_90", 0xFF060910.toInt(), 0xFFF8FAFC.toInt(), if (targetSec == 90) 0xFFFF6B00.toInt() else 0xFF334155.toInt(), 11.5f, 9f, 7f, 18f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(RemkTileHelper.buildInTileActionPill("120s", "tile_rest_120", 0xFF060910.toInt(), 0xFFF8FAFC.toInt(), if (targetSec == 120) 0xFFFF6B00.toInt() else 0xFF334155.toInt(), 11.5f, 8f, 7f, 18f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5.8f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildInTileActionPill("+30s", "tile_rest_plus30", 0xFF060910.toInt(), 0xFF00E5FF.toInt(), 0xFF00E5FF.toInt(), 10.5f, 12f, 5f, 16f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5.8f)).build())
                    .addContent(RemkTileHelper.buildInTileActionPill("Stopp", "tile_rest_stop", 0xFF1A0D12.toInt(), 0xFFEF4444.toInt(), 0xFFEF4444.toInt(), 10.5f, 12f, 5f, 16f))
                    .build()
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            curvedTopHeader = if (remSec > 0) "$nowClock • PAUSE LÄUFT" else "$nowClock • PAUSEN-TIMER",
            curvedHeaderColor = 0xFFFF6B00.toInt(),
            primaryArcColor = 0xFFFF6B00.toInt(),
            primaryArcSweepDeg = sweep,
            primaryAnchorDeg = 215f,
            secondaryArcColor = 0xFF00E5FF.toInt(),
            secondaryArcSweepDeg = 82f,
            secondaryAnchorDeg = 40f,
            freshnessMs = 5000L
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 3: RE:MK 3. Makros — Gebogener ArcText + Cyan-Emerald Bögen + 3 Makro-Balken + In-Tile 💧 +250 ml!
 */
@Suppress("DEPRECATION")
class RemkNutritionTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val targetKcal = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        val openKcal = RemkTileHelper.getSanitizedOpenKcal(prefs)
        val eatenKcal = (targetKcal - openKcal).coerceIn(0, targetKcal)
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val ep = prefs.getInt("macro_eaten_p", 148).coerceAtLeast(0)
        val tp = prefs.getInt("macro_target_p", 154).coerceAtLeast(40)
        val ec = prefs.getInt("macro_eaten_c", 190).coerceAtLeast(0)
        val tc = prefs.getInt("macro_target_c", 280).coerceAtLeast(50)
        val ef = prefs.getInt("macro_eaten_f", 54).coerceAtLeast(0)
        val tf = prefs.getInt("macro_target_f", 72).coerceAtLeast(25)

        val kcalSweep = ((eatenKcal.toFloat() / targetKcal.toFloat()) * 105f).coerceIn(28f, 105f)
        val waterSweep = ((drankL / 3.0f) * 105f).coerceIn(28f, 105f)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                RemkTileHelper.buildLaunchActivityPill(
                    packageName, TileOpenNutritionActivity::class.java.name,
                    RemkTileHelper.formatGermanInt(eatenKcal),
                    "open_nutri_header",
                    0x00000000, 0xFFF8FAFC.toInt(), null, 20f, 6f, 0f, 8f
                )
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("von ${RemkTileHelper.formatGermanInt(targetKcal)} kcal")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10f)).setColor(argb(0xFF94A3B8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(RemkTileHelper.buildMacroProgressRow("EW", ep, tp, 0xFFFF6B00.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(RemkTileHelper.buildMacroProgressRow("KH", ec, tc, 0xFF00E5FF.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(RemkTileHelper.buildMacroProgressRow("F", ef, tf, 0xFF14F195.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                RemkTileHelper.buildInTileActionPill(
                    "💧 +250 ml • $waterStr L", "tile_water_250",
                    0xFF00E5FF.toInt(), 0xFF05070B.toInt(), null, 11.5f, 16f, 8f, 22f
                )
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            curvedTopHeader = "RE:MK 360° • MAKROS",
            curvedHeaderColor = 0xFF14F195.toInt(),
            primaryArcColor = 0xFF00E5FF.toInt(),
            primaryArcSweepDeg = waterSweep,
            primaryAnchorDeg = 215f,
            secondaryArcColor = 0xFF14F195.toInt(),
            secondaryArcSweepDeg = kcalSweep,
            secondaryAnchorDeg = 40f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 4: RE:MK 4. Cardio — Gebogener ArcText + Smoked-Sapphire #090D15 + In-Tile Sport-Wechsel!
 */
@Suppress("DEPRECATION")
class RemkCardioShopTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        val sportName = prefs.getString("cardio_sport_name", "Laufen") ?: "Laufen"
        val liveBpm = prefs.getInt("last_live_bpm", 118).coerceIn(45, 220)
        val lastDist = prefs.getString("last_cardio_dist", "5,00 km") ?: "5,00 km"
        val lastDur = prefs.getString("last_cardio_dur", "26:10") ?: "26:10"

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                RemkTileHelper.buildInTileActionPill(
                    "${sportName.uppercase(Locale.GERMANY)} ⟳", "tile_cardio_next_sport",
                    0xFF060910.toInt(), 0xFF38D6FF.toInt(), 0xFF3B9EFF.toInt(), 11f, 14f, 5f, 16f
                )
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5.8f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("❤️ $liveBpm BPM")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(25f)).setColor(argb(0xFFF8FAFC.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$lastDist • $lastDur")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(11.5f)).setColor(argb(0xFF94A3B8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(
                RemkTileHelper.buildLaunchActivityPill(
                    packageName, TileOpenCardioActivity::class.java.name,
                    "▶ START", "open_cardio_gps",
                    0xFF14F195.toInt(), 0xFF05070B.toInt(), null, 13.5f, 28f, 9f, 24f
                )
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            curvedTopHeader = "$nowClock • CARDIO & GPS",
            curvedHeaderColor = 0xFF38D6FF.toInt(),
            primaryArcColor = 0xFF3B9EFF.toInt(),
            primaryArcSweepDeg = 92f,
            primaryAnchorDeg = 215f,
            secondaryArcColor = 0xFF14F195.toInt(),
            secondaryArcSweepDeg = 92f,
            secondaryAnchorDeg = 40f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}
