package io.dbobjects.util;

import static com.aventrix.jnanoid.jnanoid.NanoIdUtils.DEFAULT_NUMBER_GENERATOR;
import static com.aventrix.jnanoid.jnanoid.NanoIdUtils.randomNanoId;

public class RandomIdUtil {
    private static final char[] alphabet = new char[]{
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
            't', 'u', 'v', 'x', 'y', 'z'};

    public static String randomAlpha(int length) {
        return randomNanoId(DEFAULT_NUMBER_GENERATOR, alphabet, length);
    }
}
