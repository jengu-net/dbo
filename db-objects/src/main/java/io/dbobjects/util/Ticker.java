package io.dbobjects.util;

import io.dbobjects.db.Database;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;

@Slf4j(topic = Database.LOGGER_NAME)
public class Ticker implements AutoCloseable {

    private int statsPrintingFrequency;
    private boolean started;
    private final Map<String, StatsItem> statsItems = new HashMap<>();

    public static Ticker instance(int statsPrintingFrequency) {
        var ticker = new Ticker();
        ticker.statsPrintingFrequency = statsPrintingFrequency;
        return ticker;
    }

    private final Timer loggerTimer = new Timer("TickerLoggerTimer");


    public Ticker start() {
        started = true;
        if (statsPrintingFrequency >= 0) {
            loggerTimer.scheduleAtFixedRate(new LoggerTimerTask(), statsPrintingFrequency,
                    statsPrintingFrequency);

        }
        return this;
    }

    public static <R> R timed(Ticker ticker, String name, Callable<R> c) {
        try {
            return ticker == null ? c.call() : ticker.timed(name, c);
        } catch (Exception e) {
            throw new RuntimeException("Error in running: " + name, e);
        }
    }

    public static void timed(Ticker ticker, String name, Runnable c) {
        if (ticker == null) {
            c.run();
        } else {
            ticker.timed(name, c);
        }
    }

    public <R> R timed(String name, Callable<R> c) {
        try {
            var t = System.currentTimeMillis();
            var r = c.call();
            addStats(name, System.currentTimeMillis() - t);
            return r;
        } catch (Exception e) {
            throw new RuntimeException("Error in running: " + name, e);
        }
    }

    public void timed(String name, Runnable c) {
        try {
            var t = System.currentTimeMillis();
            c.run();
            addStats(name, System.currentTimeMillis() - t);
        } catch (Exception e) {
            throw new RuntimeException("Error in running: " + name, e);
        }
    }

    private synchronized void addStats(String name, long time) {
        statsItems.computeIfAbsent(name, k -> new StatsItem().setName(k)).addTime(time);
    }

    private synchronized String resetIntervalGetStats() {
        var stats = statsItems.values().toString();
        statsItems.values().forEach(StatsItem::resetInterval);
        return stats;
    }

    @Override
    public void close() {
        loggerTimer.cancel();
        final StringBuffer sb = new StringBuffer().append("averages X/per second");
        statsItems.values().forEach(i -> sb.append("; ").append(i.getName())
                .append(": (avg ").append(i.getCount() * 1000L / (i.getTimeSum())).append("/sec")
                .append("; total ").append(i.getCount()).append(" in ").append(i.getTimeSum() / 1000)
                .append("s.")
                .append(")"));
        log.info(sb.toString());
    }


    private static class StatsItem {
        @Getter
        private int count;
        @Getter
        private long timeSum;

        private int intervalCount;

        @Setter
        @Getter
        private String name;

        public synchronized void addTime(long time) {
            timeSum += time;
            count++;
            intervalCount++;
        }

        public void resetInterval() {
            this.intervalCount = 0;
        }

        @Override
        public String toString() {
            return new StringBuffer()
                    .append(name).append(": ")
                    .append("; gCnt: ").append(count)
                    .append("; gTime: ").append(timeSum).append("ms.")
                    .append("; intCnt: ").append(intervalCount).toString();
        }
    }

    private class LoggerTimerTask extends TimerTask {
        @Override
        public void run() {
            log.info("Ticker stats: {}", resetIntervalGetStats());
        }
    }

}
