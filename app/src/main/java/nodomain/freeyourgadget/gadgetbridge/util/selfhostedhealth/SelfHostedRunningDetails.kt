package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Read only explicit non-location columns. Missing measurements are never synthesized. */
object SelfHostedRunningDetails {
    private val LOG = LoggerFactory.getLogger(SelfHostedRunningDetails::class.java)
    const val MAX_SAMPLES = 20000
    val SUMMARY_COLUMNS = listOf("STEP_COUNT", "WORKOUT_LOAD", "WORKOUT_AEROBIC_EFFECT",
        "WORKOUT_ANAEROBIC_EFFECT", "RECOVERY_TIME", "MIN_HEART_RATE_PEAK", "MAX_HEART_RATE_PEAK",
        "MAX_MET", "HR_ZONE_TYPE", "RUN_PACE_ZONE1_MIN", "RUN_PACE_ZONE2_MIN", "RUN_PACE_ZONE3_MIN",
        "RUN_PACE_ZONE4_MIN", "RUN_PACE_ZONE5_MIN", "RUN_PACE_ZONE5_MAX", "RUN_PACE_ZONE1_TIME",
        "RUN_PACE_ZONE2_TIME", "RUN_PACE_ZONE3_TIME", "RUN_PACE_ZONE4_TIME", "RUN_PACE_ZONE5_TIME",
        "ALG_TYPE", "TRAINING_POINTS", "MAX_ALTITUDE", "MIN_ALTITUDE", "ELEVATION_GAIN", "ELEVATION_LOSS")
    val SAMPLE_COLUMNS = listOf("HEART_RATE", "SPEED", "STEP_RATE", "CADENCE", "STEP_LENGTH",
        "GROUND_CONTACT_TIME", "IMPACT", "SWING_ANGLE", "FORE_FOOT_LANDING", "MID_FOOT_LANDING",
        "BACK_FOOT_LANDING", "EVERSION_ANGLE", "CALORIES", "ALTITUDE", "HANG_TIME", "IMPACT_HANG_RATE",
        "AP", "VO", "GTB", "VR", "CEILING", "TEMP", "SPO2")
    val METRIC_KEYS = setOf("activeSeconds", "averageAltitude", "maxAltitude", "minAltitude", "averageCadence",
        "active_calories", "distanceMeters", "elevationGain", "elevationLoss", "eversionAngleAvg",
        "eversionAngleMax", "backFootLandings", "foreFootLandings", "midFootLandings", "groundContactTimeAvg",
        "averageHR", "maxHR", "minHR", "impactAvg", "impactMax", "maximumOxygenUptake",
        "averageKMPaceSeconds", "maxPace", "recoveryTime", "averageSpeed", "maxSpeed", "steps",
        "stepLengthAvg", "stepRateAvg", "swingAngleAvg", "aerobicTrainingEffect", "anaerobicTrainingEffect",
        "currentWorkoutLoad", "hrZoneWarmUp", "hrZoneFatBurn", "hrZoneAerobic", "hrZoneAnaerobic", "hrZoneExtreme")

    data class Pending(val payload: SelfHostedHealthPayloadSet, val hashes: Map<String, String>, val complete: Boolean)

    @JvmStatic fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun attach(base: SelfHostedHealthPayloadSet, device: GBDevice, endpoint: String,
               previous: (String) -> String?): Pending {
        val hashes = linkedMapOf<String, String>()
        var complete = true
        val days = GBApplication.acquireDbReadOnly().use { db ->
            val selected = DBHelper.getDevice(device, db.daoSession) ?: return Pending(base, hashes, false)
            fun query(table: String, columns: List<String>, id: Long, order: String = "", limit: Int = MAX_SAMPLES): List<JSONObject> {
                val result = mutableListOf<JSONObject>()
                db.database.rawQuery("SELECT ${columns.joinToString(",")} FROM $table WHERE WORKOUT_ID=? $order LIMIT ${limit + 1}",
                    arrayOf(id.toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for ((i, column) in columns.withIndex()) if (!cursor.isNull(i)) row.put(column, cursor.getDouble(i))
                        result.add(row)
                    }
                }
                require(result.size <= limit) { "running_details_limit" }
                return result
            }
            base.days.map { day ->
                val body = JSONObject(day.body.toString())
                val workouts = body.optJSONArray("workouts") ?: JSONArray()
                for (i in 0 until workouts.length()) {
                    val workout = workouts.getJSONObject(i)
                    if (workout.optInt("raw_type") !in listOf(1, 5, 11)) continue
                    try {
                        val id = workout.getString("id").split(':')[1].toLong()
                        var header: JSONObject? = null
                        var recovery: String? = null
                        db.database.rawQuery("SELECT ${SUMMARY_COLUMNS.joinToString(",")},RECOVERY_HEART_RATES FROM HUAWEI_WORKOUT_SUMMARY_SAMPLE WHERE WORKOUT_ID=? AND DEVICE_ID=?",
                            arrayOf(id.toString(), selected.id.toString())).use { cursor ->
                            if (cursor.moveToFirst()) {
                                header = JSONObject()
                                SUMMARY_COLUMNS.forEachIndexed { n, name -> if (!cursor.isNull(n)) header!!.put(name, cursor.getDouble(n)) }
                                if (!cursor.isNull(SUMMARY_COLUMNS.size)) recovery = String(cursor.getBlob(SUMMARY_COLUMNS.size), StandardCharsets.US_ASCII)
                            }
                        }
                        if (header == null) { complete = false; continue }
                        var summary = JSONObject()
                        db.database.rawQuery("SELECT SUMMARY_DATA FROM BASE_ACTIVITY_SUMMARY WHERE DEVICE_ID=? AND START_TIME=?",
                            arrayOf(selected.id.toString(), Instant.parse(workout.getString("start_time")).toEpochMilli().toString())).use { cursor ->
                            if (cursor.moveToFirst() && !cursor.isNull(0)) summary = JSONObject(cursor.getString(0))
                        }
                        val details = project(header!!, summary,
                            query("HUAWEI_WORKOUT_DATA_SAMPLE", listOf("TIMESTAMP") + SAMPLE_COLUMNS, id, "ORDER BY TIMESTAMP"),
                            query("HUAWEI_WORKOUT_PACE_SAMPLE", listOf("PACE_INDEX", "DISTANCE", "TYPE", "PACE", "POINT_INDEX", "CORRECTION"), id, "ORDER BY PACE_INDEX,DISTANCE,TYPE", 4096), recovery)
                        val key = "selfhosted_running_v1_${hash(endpoint)}_${device.address}_$id"
                        val digest = hash(details.toString())
                        if (previous(key) != digest) {
                            workout.put("details", details)
                            hashes[key] = digest
                        }
                    } catch (e: Exception) {
                        complete = false
                        LOG.warn("Running details unavailable ({}); preserving workout summary", e.javaClass.simpleName)
                    }
                }
                SelfHostedHealthDay(day.date, body)
            }
        }
        return Pending(SelfHostedHealthPayloadSet(days, base.sleepUploadedThrough), hashes, complete)
    }

    @JvmStatic fun project(header: JSONObject, summary: JSONObject, samples: List<JSONObject>, paces: List<JSONObject>, recoveryHex: String?): JSONObject {
        require(samples.size <= MAX_SAMPLES && paces.size <= 4096)
        val device = JSONObject()
        for (key in SUMMARY_COLUMNS) {
            val value = header.opt(key) as? Number ?: continue
            var n = value.toDouble()
            if (key in listOf("MIN_HEART_RATE_PEAK", "MAX_HEART_RATE_PEAK")) n = (n.toInt() and 255).toDouble()
            if (n.isFinite() && n >= 0 && !(key.endsWith("HEART_RATE_PEAK") && (n == 0.0 || n == 255.0))) device.put(key, n)
        }
        val metrics = JSONObject()
        for (key in METRIC_KEYS) {
            val entry = summary.optJSONObject(key) ?: continue
            val value = entry.opt("value") as? Number ?: continue
            val unit = entry.optString("unit")
            if (value.toDouble().isFinite() && unit.length <= 64) metrics.put(key, JSONObject().put("value", value).put("unit", unit))
        }
        val points = JSONArray()
        for (raw in samples) {
            val timestamp = raw.optLong("TIMESTAMP", -1)
            if (timestamp <= 0) continue
            val point = JSONObject().put("timestamp_seconds", timestamp)
            for (column in SAMPLE_COLUMNS) {
                var value = (raw.opt(column) as? Number)?.toDouble() ?: continue
                if (column == "HEART_RATE" || column == "STEP_RATE") value = (value.toInt() and 255).toDouble()
                if (!value.isFinite() || value == -1.0 || value == -32768.0) continue
                if (column in listOf("HEART_RATE", "STEP_RATE") && (value <= 0 || value == 255.0)) continue
                // These float columns default to zero when the device supplies no running dynamics.
                if (column in listOf("AP", "VO", "GTB", "VR") && value == 0.0) continue
                if (column == "SPEED") {
                    if (value < 0) continue
                    point.put("speed_mps", value / 10.0)
                    if (value > 0) point.put("pace_seconds_per_km", 10000.0 / value)
                } else point.put(column.lowercase(), value)
            }
            if (point.length() > 1) points.put(point)
        }
        val splits = JSONArray()
        val paceKeys = listOf("PACE_INDEX", "DISTANCE", "TYPE", "PACE", "POINT_INDEX", "CORRECTION")
        for (raw in paces) {
            val pace = JSONObject()
            for (key in paceKeys) if (raw.opt(key) is Number) pace.put(key.lowercase(), raw.get(key))
            if (pace.length() > 0) splits.put(pace)
        }
        val result = JSONObject().put("version", 1).put("source", "huawei_running")
            .put("device_summary", device).put("summary_metrics", metrics).put("samples", points).put("pace_samples", splits)
        if (!recoveryHex.isNullOrEmpty() && recoveryHex.length <= 1024 && recoveryHex.length % 2 == 0 && recoveryHex.matches(Regex("[0-9A-Fa-f]+"))) {
            val values = JSONArray()
            recoveryHex.chunked(2).forEach { val value = it.toInt(16); values.put(if (value in 1..254) value else JSONObject.NULL) }
            result.put("recovery_heart_rate", JSONObject().put("sample_interval_seconds", 5)
                .put("time_reference", "first_recovery_sample").put("values_bpm", values))
        }
        return result
    }
}
