# Complete running metrics upload

Version `0.92.8-sleep-hrv.2` (code 259) adds full running metrics to the tested
sleep-HRV branch. It does not merge the separate upstream 0.92.9 changes.

Enable **Upload workout summaries** and **Upload complete running metrics** in
self-hosted health settings. The new switch defaults to off. First use backfills
the configured initial-sync range, up to 31 days. Later uploads use the existing
look-back window. Huawei outdoor, indoor and trail running reports are supported.

## Available data

The optional `details` object in each running entry contains:

- `version: 1`, `source: "huawei_running"`.
- `summary_metrics`: saved numeric app metrics with their existing `value` and
  `unit`, including available training effects, recovery duration, oxygen-uptake
  estimate, step count, pace, heart-rate-zone durations and running dynamics.
- `device_summary`: selected numeric Huawei columns, preserving device encoding.
  `RECOVERY_TIME` is minutes; training effects are tenths; `MAX_MET` is still the
  encoded raw device value. Prefer `summary_metrics` for display when available.
  Pace-zone bounds, durations and the heart-rate-zone method are also retained.
- `samples`: Unix `timestamp_seconds` plus available sensor values. `heart_rate`
  is bpm, `step_rate` and running `cadence` are steps/minute, `speed_mps` is m/s,
  and `pace_seconds_per_km` is 1000/speed (absent when speed is zero).
  `step_length` is cm, `ground_contact_time` is ms, and `altitude` retains device
  decimeters. Other optional fields retain device representations. Existing gaps
  and pauses are preserved without interpolation or downsampling.
- `pace_samples`: original `pace_index`, `distance`, `type`, `pace`, `point_index`
  and optional `correction`. Type 0 is kilometer-based, type 1 mile-based; the
  correction is the fractional distance in units of 1/10000.
- `recovery_heart_rate`: saved `values_bpm` with five-second spacing and
  `time_reference: "first_recovery_sample"`. Invalid values are null. Alignment
  with the exact stop time has not been independently established, so no
  one-/two-minute recovery drop is invented. This is distinct from suggested
  recovery duration in hours.

No GPS coordinates, tracks, file paths, user/device identifiers or opaque packet
blobs are included in details. Missing sensor values remain absent. Step rate is
not relabeled as a measured step length or as the separate cadence field.
Zero-filled default float dynamics fields are not treated as measurements.

## Efficiency and failure handling

This reads existing SQLite records and starts no new sensor, GPS or service.
Content fingerprints are cached per endpoint/device/workout only after all daily
requests succeed. Unchanged details are omitted; new samples, extended recovery
curves or changed saved metrics trigger another upload. The server preserves
details when later requests contain only ordinary summaries.

Limits per workout are 20,000 activity points and 4,096 pace records. An exceeded
limit skips optional details with a log entry, preserving ordinary uploads.
There is still database, serialization and network overhead. Actual phone peak
memory and battery impact have not been benchmarked.

Install server support first. Use the same personal signing key for an in-place
APK update; do not uninstall or factory-reset the band. Reports must first be
fetched into the app: this change does not alter Bluetooth report-fetch triggers.

## Reading and testing

The [matching server branch](https://github.com/wuyu202444/health-mcp/tree/running-metrics)
supports `health_read` with `data_type: "workouts"`, `workout_id` from the list,
and optional `sample_offset`/`sample_limit`. Lists and `all` remain compact, with
sample counts; detailed reads page the recorded curves.

```text
gradlew :app:assembleMainlineRelease :app:testMainlineDebugUnitTest --tests "*SelfHosted*" --tests "*HuaweiSleepHrv*"
```

Use the existing release-signing setup. After dependencies are cached, `--offline`
avoids network version lookups during release checks. Tests use synthetic data.
An optional `RUNNING_FIXTURE_PATH` enables local private-backup projection replay;
its inputs and outputs belong under ignored `app/build`, never in version control.
