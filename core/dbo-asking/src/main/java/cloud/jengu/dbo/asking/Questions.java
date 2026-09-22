package cloud.jengu.dbo.asking;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Holder;

import java.time.Instant;
import java.util.stream.Stream;

/**
 * The questions, said once, so that answering them from inside the deployment
 * and from across a network are two implementations of one vocabulary.
 *
 * <p>That is the whole claim: a lifecycle callback and a product in another
 * building write the same code, and neither can tell which binding it holds.
 * A vocabulary declared as a class could not deliver it — the second binding
 * would be a second vocabulary that looked similar, and looking similar is
 * what drifts.
 *
 * <p>Nothing here mentions a tenant. One instance is one tenant, so a
 * fleet-wide view holds several and asks each, which is a walk rather than a
 * join and is slower on purpose.
 */
public interface Questions {

    /** What this tenant has been asked to do, and what became of it. */
    Work work();

    /** The records themselves, of one type. */
    Records records(String type);

    /** What was done here, and by whom. */
    Trail trail();

    /**
     * The same vocabulary, with somebody watching what is asked of it.
     *
     * <p>Off until this is called, and what the watcher sees is the shape of
     * a question rather than the question.
     */
    Questions watching(Watching watching);

    /** Runs, narrowed by something the answerer can narrow by. */
    interface Work {

        /** Everything not finished with. */
        Work open();

        /** Whose it is right now. */
        Work heldBy(Holder holder);

        /** Of one step, by the code whoever performs it declared. */
        Work ofStep(String step);

        /** Where the work happened. */
        Work inScope(String scope);

        /** What one executor is named on. */
        Work by(String executor);

        /** Everything filed under one correlation. */
        Work correlated(String correlation);

        /**
         * The answer, walked as it is produced. Close it.
         *
         * <p>{@link Ongoing} rather than a {@code Run}: what a rendering of a
         * run carries is what a screen asks about, and a binding that handed
         * back the whole shape with most of it null would leave a caller
         * unable to tell an empty field from one the wire does not carry.
         * Whoever needs the rest of a run asks the store for it by id.
         */
        Stream<Ongoing> stream();

        /** How many, without fetching them. */
        long count();
    }

    /** Records of one type, narrowed the same way. */
    interface Records {

        /** Those carrying this value at this path. */
        Records where(String path, String value);

        /** Those carrying this coded value at this path. */
        Records whereCoded(String path, String system, String code);

        /** Those NOT carrying it. */
        Records whereNot(String path, String value);

        /** Most recently changed first. */
        Records newestFirst();

        /** Bring the referenced records along. Declared and not built. */
        Records including(String reference);

        /** Those pointed at by something else. Declared and not built. */
        Records havingAny(String type, String reference);

        /** The answer, walked as it is produced. Close it. */
        Stream<StoredObject> stream();

        /** How many, without fetching them. */
        long count();
    }

    /** The trail, narrowed by the questions somebody asks of it. */
    interface Trail {

        /** What happened to one record. */
        Trail about(String type, String id);

        /** What one actor did. */
        Trail by(String actor);

        /** What happened under one run. */
        Trail underRun(String run);

        /** One kind of act. */
        Trail of(String interaction);

        /** Which appliance it happened on. */
        Trail at(String appliance);

        /** Since when. */
        Trail since(Instant when);

        /** The answer, walked as it is produced. Close it. */
        Stream<StoredObject> stream();

        /** How many, without fetching them. */
        long count();
    }
}
