package cloud.jengu.dbo.fhir.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faces handed out here are held to their provider's lifetime.
 *
 * <p>A face is a service, so it can go: a bundle is uninstalled, or updated, or
 * simply stops. What a caller took from it keeps working — the classes are
 * loaded and the object is reachable — so a tenant carries on being served by a
 * bundle that is no longer installed. Nothing fails; the store is just answering
 * from something nobody can see any more.
 *
 * <p>So a handed-out version is refused once its provider unregisters, and the
 * refusal says <b>where it was taken</b> — the answer to the only question a
 * reader of that message has, and one nothing else can reconstruct afterwards.
 *
 * <p>Off unless asked for. The cost is a volatile read per call and a stack walk
 * per hand-out, and the failure it names is a deployment fault rather than a
 * daily one: worth paying for while a container's dynamics are being changed,
 * not for ever.
 *
 * <p><b>Deliberately not here:</b> a warning for a cursor-backed stream held
 * past a threshold, or collected having never been closed. No such stream
 * exists — a page is a materialised chunk, and the cursor does not outlive the
 * call — and a detector for a hazard that cannot happen is a test nobody can
 * write and a comment that rots. It arrives with the stream, or not at all.
 */
public final class WatchedVersions implements FhirVersions {

    private final FhirVersions installed;
    private final Map<String, Watched> handed = new ConcurrentHashMap<>();

    public WatchedVersions(FhirVersions installed) {
        this.installed = installed;
    }

    @Override
    public Optional<FhirVersion> byCode(String code) {
        // Walked here rather than inside the map's own call, where the first
        // frame outside this class is the map's.
        String takenAt = whereItWasTaken();
        return installed.byCode(code).map(version -> handed
                .compute(code, (ignored, previous) -> previous != null && previous.serves(version)
                        ? previous : new Watched(version, takenAt)));
    }

    @Override
    public Set<String> codes() {
        return installed.codes();
    }

    /**
     * The provider under this code has gone: what was taken from it stops
     * working, loudly, rather than carrying on invisibly.
     */
    public void withdrawn(String code) {
        Watched watched = handed.remove(code);
        if (watched != null) {
            watched.withdraw();
        }
    }

    /** The frame that asked, so the refusal can say where the face went to live. */
    private static String whereItWasTaken() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::toString)
                .filter(frame -> !frame.startsWith("cloud.jengu.dbo.fhir.common.WatchedVersions")
                        && !frame.startsWith("cloud.jengu.dbo.fhir.common.FhirVersions")
                        && !frame.startsWith("java."))
                .findFirst()
                .orElse("an unrecorded caller"));
    }

    /** A face, and the promise that it is still the one that was installed. */
    private static final class Watched implements FhirVersion {

        private final FhirVersion served;
        private final String takenAt;
        private volatile boolean withdrawn;

        Watched(FhirVersion served, String takenAt) {
            this.served = served;
            this.takenAt = takenAt;
        }

        boolean serves(FhirVersion version) {
            return served == version && !withdrawn;
        }

        void withdraw() {
            withdrawn = true;
        }

        private FhirVersion live() {
            if (withdrawn) {
                throw new Withdrawn(served.code(), takenAt);
            }
            return served;
        }

        @Override
        public String code() {
            return live().code();
        }

        @Override
        public String domain() {
            return live().domain();
        }

        @Override
        public String payloadVersion() {
            return live().payloadVersion();
        }

        @Override
        public cloud.jengu.dbo.core.face.DomainFace face() {
            return live().face();
        }

        @Override
        public ForTypes forTypes(List<FhirTypeConfig> types) {
            return live().forTypes(types);
        }
    }

    /** A face was used after the bundle providing it went away. */
    public static class Withdrawn extends IllegalStateException {
        public Withdrawn(String code, String takenAt) {
            super("the face serving '" + code + "' was withdrawn after it was taken at "
                    + takenAt + " — a tenant is being served by a bundle that is no longer "
                    + "installed. Reinstall it, or bring the tenant down.");
        }
    }
}
