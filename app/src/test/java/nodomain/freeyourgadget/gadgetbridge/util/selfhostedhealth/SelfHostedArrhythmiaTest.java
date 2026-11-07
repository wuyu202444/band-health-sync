package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSequenceDataFileParser;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.ZoneId;
import java.util.Collections;
import static org.junit.Assert.*;

public class SelfHostedArrhythmiaTest {
    private HuaweiSequenceDataFileParser.SequenceFileData fixture(int version) {
        HuaweiSequenceDataFileParser.SequenceFileData file = new HuaweiSequenceDataFileParser.SequenceFileData();
        file.setDictId(700004); file.setFileType(1); file.setFileVersion(version);
        HuaweiSequenceDataFileParser.SequenceData record = new HuaweiSequenceDataFileParser.SequenceData();
        record.setStartTime(1767326992); record.setEndTime(1767326992);
        record.setDataVersion(1); record.setSummaryType(2);
        byte[] fields = new HuaweiTLV()
            .put(0x82, new HuaweiTLV().put(3, 700004347).put(4, (byte)1).put(5, (byte)56))
            .put(0x82, new HuaweiTLV().put(3, 700004809).put(4, (byte)1).put(5, (byte)1))
            .put(0x82, new HuaweiTLV().put(3, 700004095).put(4, (byte)7).put(5, new byte[]{1,2,3})).serialize();
        record.setSummary(new HuaweiTLV().put(0x81, fields).serialize());
        record.setDetails(new byte[]{1,2,3}); file.getSequenceDataList().add(record);
        return file;
    }
    @Test public void knownFieldsRemainUninterpretedAndWaveformIsOmitted() throws Exception {
        JSONObject row = HuaweiArrhythmiaRecords.project(fixture(1)).getJSONObject(0);
        assertEquals("2026-01-02T04:09:52Z", row.getString("measured_at"));
        assertEquals(1, row.getInt("field_700004809"));
        assertEquals(56, row.getInt("field_700004347"));
        assertEquals("unresolved", row.getString("interpretation"));
        assertFalse(row.has("heart_rate")); assertFalse(row.has("diagnosis"));
        assertFalse(row.has("details")); assertFalse(row.has("field_700004095"));
    }
    @Test public void unknownFormatKeepsRecordWithoutGuessingCodes() throws Exception {
        JSONObject row = HuaweiArrhythmiaRecords.project(fixture(2)).getJSONObject(0);
        assertFalse(row.has("field_700004809"));
        assertEquals("unresolved", row.getString("interpretation"));
    }
    @Test public void repeatedReadsMergeAndUploadOnMeasurementDay() throws Exception {
        JSONArray rows = HuaweiArrhythmiaRecords.project(fixture(1));
        assertEquals(1, HuaweiArrhythmiaRecords.merge(rows, rows).length());
        SelfHostedHealthPayloadSet base = new SelfHostedHealthPayloadSet(Collections.emptyList(), 0L);
        SelfHostedHealthPayloadSet payload = HuaweiArrhythmiaRecords.INSTANCE.attach(base, rows, ZoneId.of("Asia/Shanghai"), 1767327992L);
        assertEquals("2026-01-02", payload.getDays().get(0).getDate());
        assertEquals(1, payload.getDays().get(0).getBody().getJSONArray("arrhythmia").length());
    }
}
