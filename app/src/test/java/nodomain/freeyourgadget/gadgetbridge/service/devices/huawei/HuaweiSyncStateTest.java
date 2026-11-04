package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HuaweiSyncStateTest {
    @Test
    public void repeatedWorkoutChecksDoNotCreateAQueueBacklog() {
        final HuaweiSyncState state = new HuaweiSyncState(null);
        state.addWorkoutSyncToQueue();
        state.addWorkoutSyncToQueue();
        org.junit.Assert.assertEquals(nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes.TYPE_GPS_TRACKS, state.getCurrentSyncType());
        assertTrue(state.startHeartRateSync());
        assertFalse(state.startWorkoutSync());
    }

    @Test
    public void fullActivitySyncBlocksBackgroundHeartRateSync() {
        final HuaweiSyncState syncState = new HuaweiSyncState(null);

        assertTrue(syncState.startActivitySync());
        assertFalse(syncState.startHeartRateSync());
    }

    @Test
    public void backgroundHeartRateSyncBlocksFullActivitySync() {
        final HuaweiSyncState syncState = new HuaweiSyncState(null);

        assertTrue(syncState.startHeartRateSync());
        assertFalse(syncState.startActivitySync());
    }
}
