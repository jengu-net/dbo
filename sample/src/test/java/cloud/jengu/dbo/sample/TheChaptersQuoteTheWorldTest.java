package cloud.jengu.dbo.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A chapter quoting the world it is written about has to be quoting it.
 *
 * <p>A chapter that shows a whole file includes it, and a renamed file fails
 * the site build. The quotes this covers are the other kind: three lines of a
 * tenant's spec, shown where showing the whole file would bury the point. They
 * were typed, and nothing noticed when the file moved underneath them — which
 * is the drift the sample exists to make impossible.
 *
 * <p>A quote opts in by naming its source on the fence, so the reader is told
 * where the lines come from and this can check them. A block with no title is
 * not a quote of anything and is left alone.
 */
class TheChaptersQuoteTheWorldTest {

    /** The chapters, from this module: the guide is documentation about it. */
    private static final Path GUIDE = Path.of("..", "docs", "guide");

    private static final String FENCE = "```json title=\"";

    @Test
    @DisplayName("every line a chapter quotes from the world is in the world, as written")
    void everyQuotedLineIsInTheWorld() throws IOException {
        List<String> checked = new ArrayList<>();
        try (Stream<Path> chapters = Files.list(GUIDE)) {
            for (Path chapter : chapters.filter(f -> f.toString().endsWith(".md")).toList()) {
                for (String[] quote : quotesIn(Files.readString(chapter))) {
                    Path source = Path.of("..", quote[0]);
                    if (!Files.exists(source)) {
                        fail(chapter.getFileName() + " quotes '" + quote[0]
                                + "', which is not a file");
                    }
                    String held = squeezed(Files.readString(source));
                    if (!held.contains(squeezed(quote[1]))) {
                        fail(chapter.getFileName() + " quotes " + quote[0]
                                + ", and what it quotes is not in it:\n" + quote[1]);
                    }
                    checked.add(chapter.getFileName() + " -> " + quote[0]);
                }
            }
        }
        // A check that stopped finding anything to check would pass forever,
        // which is the failure mode of every guard over a convention.
        assertTrue(checked.size() >= 4,
                "the chapters quote the world in fewer places than they did; if a quote was "
                        + "removed on purpose, lower this: " + checked);
    }

    /** Each titled json fence as {source path, body}. */
    private static List<String[]> quotesIn(String chapter) {
        List<String[]> found = new ArrayList<>();
        int at = chapter.indexOf(FENCE);
        while (at >= 0) {
            int nameEnds = chapter.indexOf('"', at + FENCE.length());
            String source = chapter.substring(at + FENCE.length(), nameEnds);
            int bodyBegins = chapter.indexOf('\n', nameEnds) + 1;
            int bodyEnds = chapter.indexOf("\n```", bodyBegins);
            found.add(new String[] {source, chapter.substring(bodyBegins, bodyEnds)});
            at = chapter.indexOf(FENCE, bodyEnds);
        }
        return found;
    }

    /**
     * Whitespace out, so a quote may be re-wrapped to the page's width and a
     * spec may be re-indented, without either becoming a false alarm. A
     * trailing comma goes too: a line lifted out of a list has none.
     */
    private static String squeezed(String text) {
        StringBuilder out = new StringBuilder();
        for (byte each : text.getBytes(StandardCharsets.UTF_8)) {
            char one = (char) each;
            if (!Character.isWhitespace(one) && one != ',') {
                out.append(one);
            }
        }
        return out.toString();
    }
}
