package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.Assume;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class SelfHostedRunningDetailsTest {
    @Test public void unsignedValuesSpeedAndMissingMeasurementsHaveExplicitMeaning() throws Exception {
        JSONObject header = new JSONObject("{\"STEP_COUNT\":1000,\"MAX_HEART_RATE_PEAK\":-89,\"DEVICE_ID\":1}");
        JSONObject row = new JSONObject("{\"TIMESTAMP\":1000,\"HEART_RATE\":-89,\"STEP_RATE\":-86,\"SPEED\":30,\"CADENCE\":-1,\"STEP_LENGTH\":-1,\"AP\":0,\"LATITUDE\":30}");
        JSONObject summary = new JSONObject("{\"averageHR\":{\"value\":140,\"unit\":\"bpm\"},\"latitude\":{\"value\":30,\"unit\":\"degrees\"}}");
        JSONObject out = SelfHostedRunningDetails.project(header, summary, Collections.singletonList(row), Collections.emptyList(), "9690FF00");
        JSONObject point = out.getJSONArray("samples").getJSONObject(0);
        assertEquals(167, point.getInt("heart_rate"));
        assertEquals(170, point.getInt("step_rate"));
        assertEquals(3.0, point.getDouble("speed_mps"), 0);
        assertEquals(1000.0/3, point.getDouble("pace_seconds_per_km"), 0.00001);
        assertFalse(point.has("cadence")); assertFalse(point.has("step_length")); assertFalse(point.has("ap"));
        assertFalse(out.toString().contains("LATITUDE")); assertFalse(out.toString().contains("latitude"));
        assertFalse(out.toString().contains("DEVICE_ID"));
        JSONArray recovery = out.getJSONObject("recovery_heart_rate").getJSONArray("values_bpm");
        assertEquals(150, recovery.getInt(0)); assertTrue(recovery.isNull(2)); assertTrue(recovery.isNull(3));
        assertEquals("first_recovery_sample", out.getJSONObject("recovery_heart_rate").getString("time_reference"));
        assertEquals(SelfHostedRunningDetails.hash(out.toString()), SelfHostedRunningDetails.hash(out.toString()));
    }
    @Test public void stoppedSpeedAndAbsentRecoveryAreNotFabricated() throws Exception {
        JSONObject row = new JSONObject("{\"TIMESTAMP\":1000,\"HEART_RATE\":-1,\"SPEED\":0}");
        JSONObject out = SelfHostedRunningDetails.project(new JSONObject(),new JSONObject(),Collections.singletonList(row),Collections.emptyList(),"bad");
        assertEquals(0.0,out.getJSONArray("samples").getJSONObject(0).getDouble("speed_mps"),0);
        assertFalse(out.getJSONArray("samples").getJSONObject(0).has("pace_seconds_per_km"));
        assertFalse(out.has("recovery_heart_rate"));
    }
    // Input and projected output stay under app/build and are never committed.
    @Test public void privateBackupProjectionWhenExplicitlyProvided() throws Exception {
        String fixture = System.getenv("RUNNING_FIXTURE_PATH");
        Assume.assumeTrue(fixture != null && !fixture.isEmpty());
        JSONObject input = new JSONObject(Files.readString(Path.of(fixture)));
        JSONObject out = SelfHostedRunningDetails.project(input.getJSONObject("header"),input.getJSONObject("summary"),
                rows(input.getJSONArray("samples")),rows(input.getJSONArray("paces")),input.optString("recovery_hex",null));
        assertEquals(input.getJSONArray("samples").length(),out.getJSONArray("samples").length());
        assertEquals(input.getString("recovery_hex").length()/2,out.getJSONObject("recovery_heart_rate").getJSONArray("values_bpm").length());
        assertFalse(out.toString().contains("GPX")); assertFalse(out.toString().contains("latitude"));
        Files.writeString(Path.of(fixture+".projected.json"),out.toString());
    }
    private static List<JSONObject> rows(JSONArray array) throws Exception {
        List<JSONObject> list=new ArrayList<>();
        for(int i=0;i<array.length();i++) list.add(array.getJSONObject(i));
        return list;
    }
}
