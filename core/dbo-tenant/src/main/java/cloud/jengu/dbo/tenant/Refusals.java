package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.sync.ConfigApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which refusals are worth saying again.
 *
 * <p>A declaration the store refuses is already a card in front of a person,
 * which is the right home for it — somebody has to change the file. What it
 * was not is <b>visible</b>: a tenant whose spec will not parse never reaches
 * bring-up, so none of the reporting there fires, and the deployment answers
 * what it serves without mentioning the one it could not. Six of seven reads
 * exactly like six.
 *
 * <p>So it is said. Once, because the pass that produces it runs on every beat
 * of the scan, and a refusal repeated every few seconds is how a log stops
 * being read — the same mistake as the failure that repeated silently, made
 * from the other side.
 *
 * <p>A declaration whose reason CHANGES is said again. Somebody editing a
 * broken file is working through its problems one at a time, and the second
 * problem is news.
 */
final class Refusals {

    /** The last reason said for each declaration. */
    private final Map<String, String> said = new ConcurrentHashMap<>();

    /**
     * The refusals that should be reported now, remembering them as said.
     *
     * <p>Order is the order they arrived, so a deployment reports its
     * declarations in the order it read them.
     */
    List<ConfigApplication.Card> worthSaying(List<ConfigApplication.Card> cards) {
        List<ConfigApplication.Card> fresh = new ArrayList<>();
        for (ConfigApplication.Card card : cards) {
            String reason = String.valueOf(card.reason());
            if (!reason.equals(said.put(card.declaration(), reason))) {
                fresh.add(card);
            }
        }
        return fresh;
    }

    /**
     * Forgets a declaration, so its next refusal is said again.
     *
     * <p>Called when it applies: a file that was fixed and then broken again
     * is a new thing to be told about, not a repeat of the old one.
     */
    void applied(String declaration) {
        said.remove(declaration);
    }
}
