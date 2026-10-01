package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.promise.Story;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The names a story gives to what it makes.
 *
 * <p>Every story runs at once on one world, and the world's database outlives
 * the run wherever its container is reused. So a name carries two parts: the
 * story's prefix, which keeps one story out of another's way, and a mark
 * minted once per JVM, which keeps a run out of the way of the data an earlier
 * run left behind. A story that names its records here can find exactly what
 * it made, however busy the tenant is.
 *
 * <p>Built from the story's catalogue constant, so the prefix cannot drift from
 * the story it names, and handed out from one place, so two stories cannot
 * both choose the same name.
 */
public final class StoryNames {

    /** One per JVM: a rerun against a reused database meets nothing of its own. */
    private static final String RUN = mark();

    private final String prefix;

    private StoryNames(String prefix) {
        this.prefix = prefix;
    }

    /** The names for this story: {@code US-DBO-CLINICAL-RECORD} gives {@code clinical-record}. */
    public static StoryNames of(Story story) {
        String code = story.code();
        String bare = code.startsWith("US-DBO-") ? code.substring("US-DBO-".length()) : code;
        return new StoryNames(bare.toLowerCase(Locale.ROOT));
    }

    /** The story's prefix, as it appears in every name below. */
    public String prefix() {
        return prefix;
    }

    /** This run's mark. */
    public String run() {
        return RUN;
    }

    /**
     * A tenant this story declares: {@code <prefix>-<role>-<run>}, inside the
     * store's rule for a tenant code.
     */
    public String tenant(String role) {
        return prefix + "-" + role + "-" + RUN;
    }

    /**
     * The identifier system this story keys its people and things by, on a
     * tenant that lets a type be keyed by any system.
     */
    public String system() {
        return "urn:dbo:story:" + prefix;
    }

    /**
     * A value that is this story's and this run's, for a system the world
     * fixes — a national number on a tenant keyed by one, where the system
     * cannot carry the prefix.
     */
    public String value(String local) {
        return prefix + "-" + RUN + "-" + local;
    }

    /** A code for a step, a client, an audience or a subscription. */
    public String code(String local) {
        return prefix + "." + RUN + "." + local;
    }

    /** A canonical URL for content this story authors. */
    public String canonical(String path) {
        return "https://story.dbo.test/" + prefix + "/" + RUN + "/" + path;
    }

    private static String mark() {
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder mark = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            mark.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return mark.toString();
    }
}
