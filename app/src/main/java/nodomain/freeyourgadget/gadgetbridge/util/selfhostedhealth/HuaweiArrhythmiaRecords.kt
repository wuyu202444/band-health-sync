package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import android.content.Context
import android.util.AtomicFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiDictTypes
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSequenceDataFileParser
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Firmware records, without assigning clinical meanings to undocumented fields. */
object HuaweiArrhythmiaRecords {
    @JvmStatic fun show(context: Context, address: String) {
        try {
            val array = read(context, address)
            val rows = (0 until array.length()).map { array.getJSONObject(it) }.reversed()
            if (rows.isEmpty()) {
                MaterialAlertDialogBuilder(context).setTitle(R.string.huawei_arrhythmia_records)
                    .setMessage(R.string.huawei_arrhythmia_empty).setPositiveButton(android.R.string.ok, null).show()
                return
            }
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val labels = rows.map { formatter.format(Instant.parse(it.getString("measured_at")).atZone(ZoneId.systemDefault())) +
                " · " + context.getString(R.string.huawei_arrhythmia_unresolved) }.toTypedArray()
            MaterialAlertDialogBuilder(context).setTitle(R.string.huawei_arrhythmia_records)
                .setItems(labels) { _, index ->
                    val row = rows[index]
                    val fields = listOf("field_700004347", "field_700004809").filter { row.has(it) }
                        .joinToString("\n") { it.removePrefix("field_") + " = " + row.getInt(it) }
                    MaterialAlertDialogBuilder(context).setTitle(labels[index])
                        .setMessage(context.getString(R.string.huawei_arrhythmia_detail, fields))
                        .setPositiveButton(android.R.string.ok, null).show()
                }.setNegativeButton(android.R.string.cancel, null).show()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.huawei_arrhythmia_records)
                .setMessage(R.string.huawei_arrhythmia_read_failed).setPositiveButton(android.R.string.ok, null).show()
        }
    }
    private fun stem(address: String) = "huawei-arrhythmia-" + address.replace(":", "").also {
        require(it.matches(Regex("[0-9A-Fa-f]{12}")))
    }

    @JvmStatic fun project(file: HuaweiSequenceDataFileParser.SequenceFileData): JSONArray {
        require(file.dictId == HuaweiDictTypes.ARRHYTHMIA_CLASS)
        val rows = JSONArray()
        for (record in file.sequenceDataList.take(256)) {
            if (record.startTime <= 0) continue
            val row = JSONObject().put("measured_at", Instant.ofEpochSecond(record.startTime.toLong()).toString())
                .put("source", "huawei_ppg_arrhythmia").put("interpretation", "unresolved")
                .put("format_version", record.dataVersion)
            if (file.fileVersion == 1 && record.dataVersion == 1 && record.summaryType == 2 && file.fileType != 2) {
                val summary = record.summary
                if (summary != null && summary.size <= 65536) {
                    val containers = HuaweiTLV().parse(summary).allContainerObjects
                    for (container in containers) for (entry in container.allContainerObjects) {
                        val field = entry.getAsInteger(3)
                        if (field != 700004347 && field != 700004809) continue
                        val bytes = entry.getBytes(5)
                        if (entry.getAsInteger(4) != 1 || bytes.size !in 1..2) continue
                        var value = 0
                        for (byte in bytes) value = (value shl 8) or (byte.toInt() and 255)
                        row.put("field_$field", value)
                    }
                }
            }
            rows.put(row)
        }
        return rows
    }

    @JvmStatic fun merge(old: JSONArray, incoming: JSONArray): JSONArray {
        val rows = sortedMapOf<String, JSONObject>()
        for (array in listOf(old, incoming)) for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            rows[row.getString("measured_at")] = row
        }
        return JSONArray(rows.values.toList().takeLast(256))
    }

    @JvmStatic @Synchronized fun read(context: Context, address: String): JSONArray {
        val json = File(context.filesDir, stem(address) + ".json")
        if (json.exists()) return JSONArray(AtomicFile(json).openRead().bufferedReader().use { it.readText() })
        // Import the already captured .5 file without requiring another measurement.
        val raw = File(context.filesDir, stem(address) + ".bin")
        if (!raw.exists() || raw.length() !in 1..1048576) return JSONArray()
        val parsed = HuaweiSequenceDataFileParser.parseSequenceFileData(raw.readBytes()) ?: return JSONArray()
        return project(parsed)
    }

    @JvmStatic @Synchronized fun save(context: Context, address: String, file: HuaweiSequenceDataFileParser.SequenceFileData) {
        val rows = merge(read(context, address), project(file))
        val target = AtomicFile(File(context.filesDir, stem(address) + ".json"))
        val output = target.startWrite()
        try { output.write(rows.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(output) }
        catch (e: Exception) { target.failWrite(output); throw e }
    }

    fun attach(base: SelfHostedHealthPayloadSet, rows: JSONArray, zone: ZoneId, captured: Long): SelfHostedHealthPayloadSet {
        val days = base.days.associateBy { it.date }.toMutableMap()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val instant = Instant.parse(row.getString("measured_at"))
            if (instant.epochSecond > captured || instant.epochSecond < captured - 31 * 86400L) continue
            val date = instant.atZone(zone).toLocalDate().toString()
            val day = days[date] ?: SelfHostedHealthDay(date, JSONObject().put("date", date))
            val records = day.body.optJSONArray("arrhythmia") ?: JSONArray().also { day.body.put("arrhythmia", it) }
            records.put(JSONObject(row.toString()))
            days[date] = day
        }
        return SelfHostedHealthPayloadSet(days.toSortedMap().values.toList(), base.sleepUploadedThrough)
    }
}
