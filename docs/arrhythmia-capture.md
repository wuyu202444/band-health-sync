# Huawei Band arrhythmia capture (.5)

This is a protocol capture build, not an implemented arrhythmia report viewer.

Observed on the user device after a completed measurement:
- 12:09:52: config message 900300003 from hw.watch.health.arrhythmia.
- 12:10:46: dictionary-update notification includes 700004 (0x000AAE64).
- No record reader for this class existed. Control messages and notifications do not establish a medical result.

On a full activity refresh, when the device reports arrhythmia support and its activation preference is enabled, request a sequence file for class 700004 over the last seven days. This request path is experimental: a notification does not prove this download transport will return records.

Keep only the latest response under the app internal files directory as huawei-arrhythmia-<address>.bin. Files over 1 MiB are not saved or parsed. Log a 64-byte header, at most eight sequence records, at most 4096 summary bytes and 128 detail bytes per record. The logs contain private measurement data and must not be committed. Ordinary sync continues if the optional read fails. Incoming control responses log identifiers and bounded bytes without interpreting result codes.

No GPS request, inferred diagnosis, server upload, clinical labels, or chart is added. Next acceptance step: install over the existing personal build, wait for connection initialization, manually refresh activity data once, then export the new log. Reuse the already recorded measurement first. Do not require a new measurement merely to populate the UI.
