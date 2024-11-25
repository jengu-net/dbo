package io.dbobjects.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

public final class Misc {
    public static void runWithLoggerLevel(String logger, String level, Runnable op) {
        var log = (Logger) LoggerFactory.getLogger(logger);
        var oldLevel = log.getLevel();
        try {
            log.setLevel(Level.valueOf(level));
            op.run();
        } finally {
            log.setLevel(oldLevel);
        }
    }
}
