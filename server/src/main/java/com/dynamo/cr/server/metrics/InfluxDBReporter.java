package com.dynamo.cr.server.metrics;

import com.codahale.metrics.*;
import com.codahale.metrics.Timer;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class InfluxDBReporter extends ScheduledReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InfluxDBReporter.class);
    private final boolean skipIdleMetrics;
    private final Map<String, Long> previousValues;

    private final InfluxDB influxDB = InfluxDBFactory.connect("http://metrics.defold.com:8086", "root", "root");
    private static final String DATABASE_NAME = "serverMetrics";

    private final String reportingHost;

    public InfluxDBReporter(final MetricRegistry registry,
                            final TimeUnit rateUnit,
                            final TimeUnit durationUnit,
                            final boolean skipIdleMetrics) {

        super(registry, "influxDb-reporter", MetricFilter.ALL, rateUnit, durationUnit);
        this.skipIdleMetrics = skipIdleMetrics;
        this.previousValues = new TreeMap<>();

        influxDB.createDatabase(DATABASE_NAME);

        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "Unknown";
        }
        this.reportingHost = hostname;
    }

    @Override
    public void report() {
        super.report();
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {

        final long now = System.currentTimeMillis();

        try {
            BatchPoints batchPoints = BatchPoints
                    .database(DATABASE_NAME)
                    .tag("host", reportingHost)
                    .build();

            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                reportGauge(batchPoints, entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(batchPoints, entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(batchPoints, entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMeter(batchPoints, entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(batchPoints, entry.getKey(), entry.getValue(), now);
            }

            influxDB.write(batchPoints);
        } catch (Exception e) {
            LOGGER.warn("Unable to report to InfluxDB. Discarding data.", e);
        }
    }

    private void reportTimer(BatchPoints batchPoints, String name, Timer timer, long now) {
        if (canSkipMetric(name, timer)) {
            return;
        }
        final Snapshot snapshot = timer.getSnapshot();
        Map<String, Object> fields = new HashMap<>();
        fields.put("count", timer.getCount());
        fields.put("min", convertDuration(snapshot.getMin()));
        fields.put("max", convertDuration(snapshot.getMax()));
        fields.put("mean", convertDuration(snapshot.getMean()));
        fields.put("std-dev", convertDuration(snapshot.getStdDev()));
        fields.put("median", convertDuration(snapshot.getMedian()));
        fields.put("50-percentile", convertDuration(snapshot.getMedian()));
        fields.put("75-percentile", convertDuration(snapshot.get75thPercentile()));
        fields.put("95-percentile", convertDuration(snapshot.get95thPercentile()));
        fields.put("98-percentile", convertDuration(snapshot.get98thPercentile()));
        fields.put("99-percentile", convertDuration(snapshot.get99thPercentile()));
        fields.put("999-percentile", convertDuration(snapshot.get999thPercentile()));
        fields.put("one-minute", convertRate(timer.getOneMinuteRate()));
        fields.put("five-minute", convertRate(timer.getFiveMinuteRate()));
        fields.put("fifteen-minute", convertRate(timer.getFifteenMinuteRate()));
        fields.put("mean-rate", convertRate(timer.getMeanRate()));
        fields.put("run-count", timer.getCount());

        Point point = Point.measurement(name).time(now, TimeUnit.MILLISECONDS).fields(fields).build();
        batchPoints.point(point);
    }

    private void reportHistogram(BatchPoints batchPoints, String name, Histogram histogram, long now) {
        if (canSkipMetric(name, histogram)) {
            return;
        }
        final Snapshot snapshot = histogram.getSnapshot();
        Map<String, Object> fields = new HashMap<>();
        fields.put("count", histogram.getCount());
        fields.put("min", snapshot.getMin());
        fields.put("max", snapshot.getMax());
        fields.put("mean", snapshot.getMean());
        fields.put("median", snapshot.getMedian());
        fields.put("std-dev", snapshot.getStdDev());
        fields.put("50-percentile", snapshot.getMedian());
        fields.put("75-percentile", snapshot.get75thPercentile());
        fields.put("95-percentile", snapshot.get95thPercentile());
        fields.put("98-percentile", snapshot.get98thPercentile());
        fields.put("99-percentile", snapshot.get99thPercentile());
        fields.put("999-percentile", snapshot.get999thPercentile());
        fields.put("run-count", histogram.getCount());

        Point point = Point.measurement(name).time(now, TimeUnit.MILLISECONDS).fields(fields).build();
        batchPoints.point(point);
    }

    private void reportCounter(BatchPoints batchPoints, String name, Counter counter, long now) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("count", counter.getCount());

        Point point = Point.measurement(name).time(now, TimeUnit.MILLISECONDS).fields(fields).build();
        batchPoints.point(point);
    }

    private void reportGauge(BatchPoints batchPoints, String name, Gauge<?> gauge, long now) {
        Map<String, Object> fields = new HashMap<>();
        Object value = gauge.getValue();

        // Hack to deal with values set to empty sets
        if (value == Collections.EMPTY_SET) {
            return;
        }

        fields.put("value", value);

        Point point = Point.measurement(name).time(now, TimeUnit.MILLISECONDS).fields(fields).build();
        batchPoints.point(point);
    }

    private void reportMeter(BatchPoints batchPoints, String name, Metered meter, long now) {
        if (canSkipMetric(name, meter)) {
            return;
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("count", meter.getCount());
        fields.put("one-minute", convertRate(meter.getOneMinuteRate()));
        fields.put("five-minute", convertRate(meter.getFiveMinuteRate()));
        fields.put("fifteen-minute", convertRate(meter.getFifteenMinuteRate()));
        fields.put("mean-rate", convertRate(meter.getMeanRate()));

        Point point = Point.measurement(name).time(now, TimeUnit.MILLISECONDS).fields(fields).build();
        batchPoints.point(point);
    }

    private boolean canSkipMetric(String name, Counting counting) {
        boolean isIdle = (calculateDelta(name, counting.getCount()) == 0);
        if (skipIdleMetrics && !isIdle) {
            previousValues.put(name, counting.getCount());
        }
        return skipIdleMetrics && isIdle;
    }

    private long calculateDelta(String name, long count) {
        Long previous = previousValues.get(name);
        if (previous == null) {
            return -1;
        }
        if (count < previous) {
            LOGGER.warn("Saw a non-monotonically increasing value for metric '{}'", name);
            return 0;
        }
        return count - previous;
    }
}
