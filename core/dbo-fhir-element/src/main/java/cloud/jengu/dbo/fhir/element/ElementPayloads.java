package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.Payloads.Issue;
import cloud.jengu.dbo.core.face.ReadOnce;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.elementmodel.ParserBase;
import org.hl7.fhir.r5.formats.IParser;
import org.hl7.fhir.r5.utils.validation.ValidatorSession;
import org.hl7.fhir.r5.utils.xver.XVerExtensionManagerFactory;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidatorSettings;
import org.hl7.fhir.validation.instance.InstanceValidator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading, checking and writing a payload against a version's definitions.
 *
 * <p>The document is an {@link Element} — the shape the definitions describe,
 * not a generated class — which is what lets one implementation serve a version
 * that has no generated model. The engine holds a {@code Payloads<?>} and never
 * names it, so nothing above this bundle learns that.
 */
final class ElementPayloads implements Payloads<Element> {

    /** Reads of a payload, so a test can hold this to its word. */
    static final java.util.concurrent.atomic.AtomicLong READS =
            new java.util.concurrent.atomic.AtomicLong();

    private final java.util.function.Supplier<SimpleWorkerContext> context;
    private final Terms terms;
    private final Payloads<Element> reading;

    ElementPayloads(SimpleWorkerContext context) {
        this(context, null);
    }

    /**
     * Over a context resolved when first needed rather than handed over
     * built. The version's own payloads are constructed when the version is,
     * and a version is looked up for things that need no context at all — a
     * type's registrations, a definition's envelope — so building one there
     * was the whole of a version's memory paid for reading its name.
     */
    ElementPayloads(java.util.function.Supplier<SimpleWorkerContext> context) {
        this.terms = null;
        this.context = context;
        this.reading = new ReadOnce<>(new Parsing());
    }

    /**
     * The tenant form: {@code terms} answers for systems the definitions do
     * not carry. Two consequences, split by their nature:
     * value-set membership rides the validator through {@link TenantContext},
     * where binding strength decides severity — and system membership is
     * checked here, after the validator, because a code claiming a system the
     * tenant holds either exists in it or the document is wrong, regardless
     * of how strongly any binding felt about it.
     */
    ElementPayloads(SimpleWorkerContext context, Terms terms) {
        this.terms = terms;
        this.context = () -> context;
        // One read per request, the same way every face gets it: the engine's
        // envelope extraction asks this face about the bytes the write path
        // already read (REQ-DBO-VER-ONE-READ-PER-REQUEST).
        this.reading = new ReadOnce<>(new Parsing());
    }

    /**
     * What the parser did not recognise, carried on the document it parsed.
     *
     * <p>On the document rather than returned beside it, because the reading
     * seam is shared with every path that renders a stored payload and only
     * the write path may act on this. A reader that never asks is unaffected.
     */
    static final String UNRECOGNISED = "dbo.parse.unrecognised";

    /**
     * An element the version does not define, as the parser named it.
     *
     * <p><b>Matched against the parser's own message bundle, not English.</b>
     * The parser leaves {@code messageId} null on these, so the text is the
     * only handle there is — and matching English would mean the rule quietly
     * stopped applying on a machine with a different default locale. So the
     * bundle is asked what it WOULD say, with a sentinel where the property
     * name goes, and the two halves of that become the prefix and suffix to
     * recognise. Same bundle, same locale, same string: the comparison cannot
     * drift away from what the parser produced, and the property name falls
     * out of the middle.
     *
     * <p><b>Only this family.</b> Turning the policy on makes everything else
     * the parser noticed visible too, and acting on all of it was measurably
     * wrong twice: it refused this store's OWN rendering — {@code id} and
     * {@code meta} are written onto every served document whether or not a
     * sibling model declares them — and it pre-empted the per-type choice of
     * who decides a write, by refusing at the parse a document that exists to
     * be judged by the toolchain or the database. Both are deliberate
     * behaviours that were here first. What the parser noticed and this does
     * not read stays exactly as invisible as it was.
     */
    private List<String> unrecognised(List<ValidationMessage> noticed) {
        String probe = context().formatMessage(
                org.hl7.fhir.utilities.i18n.I18nConstants.UNRECOGNISED_PROPERTY_, SENTINEL);
        int at = probe.indexOf(SENTINEL);
        if (at < 0) {
            // The bundle stopped interpolating the name. Reading nothing is
            // the safe answer: this seam refuses writes, and a rule that
            // cannot tell which property it is about must not guess.
            return List.of();
        }
        String head = probe.substring(0, at);
        String tail = probe.substring(at + SENTINEL.length());
        List<String> found = new ArrayList<>();
        for (ValidationMessage message : noticed) {
            if (message.getLevel() != ValidationMessage.IssueSeverity.ERROR
                    && message.getLevel() != ValidationMessage.IssueSeverity.FATAL) {
                continue;
            }
            String said = message.getMessage();
            if (said == null || !said.startsWith(head) || !said.endsWith(tail)
                    || said.length() < head.length() + tail.length()) {
                continue;
            }
            String property = said.substring(head.length(), said.length() - tail.length());
            // The slots this store writes itself. ElementAncestors#rendered
            // puts them on every served document whether or not the model
            // declares them; for a FHIR resource they are declared and never
            // come up, and for a sibling model they do. The author never
            // supplied them, so there is nothing there for anybody to fix —
            // and a converted form handed back for re-acceptance carries
            // them.
            if (OURS.contains(property)) {
                continue;
            }
            String where = message.getLocation();
            found.add((where == null || where.isBlank() ? document(message) : where)
                    + ": the element '" + property + "' is not defined here, and this store "
                    + "does not keep what it cannot read — FHIR carries what a resource does "
                    + "not define in 'extension'");
        }
        return List.copyOf(found);
    }

    /** Where a message with no location came from, so a refusal still points somewhere. */
    private static String document(ValidationMessage message) {
        return message.getLocation() == null ? "" : message.getLocation();
    }

    /** Stands in for the property name while the bundle is asked what it would say. */
    private static final String SENTINEL = "\u0001dbo\u0001";

    /** Written by this store onto every served document, declared by the model or not. */
    private static final java.util.Set<String> OURS = java.util.Set.of("id", "meta");

    private final class Parsing implements Payloads<Element> {

        @Override
        public Element read(String typeName, byte[] payload) {
            READS.incrementAndGet();
            try {
                // The same parser {@code Manager.parseSingle} builds, given
                // somewhere to report into. It hands the parser a null list,
                // and ParserBase#logError returns on the first line when the
                // list is null — so everything the parser noticed about a
                // document was discarded before anybody could act on it,
                // including an element FHIR does not define. The default
                // policy reports nothing in any case.
                //
                // Collected, never thrown: with EVERYTHING the parser adds to
                // the list and returns, so nothing that parsed before stops
                // parsing. What is done about a finding is the write path's
                // decision, not the reader's — a stored document that was
                // accepted under an older rule must stay readable.
                ParserBase parser = Manager.makeParser(context(), Manager.FhirFormat.JSON);
                parser.setupValidation(ParserBase.ValidationPolicy.EVERYTHING);
                List<ValidationMessage> noticed = new ArrayList<>();
                Element parsed = parser.parseSingle(new ByteArrayInputStream(payload), noticed);
                parsed.setUserData(UNRECOGNISED, unrecognised(noticed));
                return parsed;
            } catch (IOException | RuntimeException e) {
                // IOException included, and deliberately: the bytes are in
                // memory, so there is no I/O here to fail — a JSON syntax error
                // arrives as one because the parser reads through a stream.
                // Letting it travel as an I/O failure answers a malformed body
                // with 500, telling a caller the server broke when their
                // request did.
                throw new IllegalArgumentException("body is not parseable FHIR JSON: "
                        + e.getMessage(), e);
            }
        }

        @Override
        public String typeOf(Element document) {
            return document.fhirType();
        }

        @Override
        public List<String> validate(String typeName, Element document) {
            return refusals(check(typeName, document, null));
        }

        /**
         * Everything the validator had to say, at every severity.
         *
         * <p>Only the errors used to survive, which threw away the half of the
         * answer this store is best placed to give: an <b>extensible</b>
         * binding violated is advice, a <b>preferred</b> one is a suggestion,
         * and a code from a system nothing here carries is <b>unresolvable</b>
         * rather than wrong — a different fact, with a different fix, in
         * somebody else's hands. Reporting all three as errors would be worse
         * than reporting none, because callers learn to ignore an outcome that
         * cries wolf; discarding them, which is what happened, taught callers
         * nothing at all.
         *
         * <p>The write path still refuses on errors alone. One evaluation, two
         * readings.
         */
        @Override
        public List<Issue> check(String typeName, Element document, String shapeReference) {
            List<ValidationMessage> messages = new ArrayList<>();
            // What the PARSER could not make sense of, said here so that
            // asking and writing give the same answer. The validator never
            // sees these: the parser drops what it cannot place before the
            // tree exists, so a document could be refused by a write while
            // $validate called it clean — and a writer who asks first is
            // exactly the writer this operation is for.
            List<Issue> unreadable = new ArrayList<>();
            if (document.getUserData(UNRECOGNISED) instanceof List<?> noticed) {
                for (Object one : noticed) {
                    unreadable.add(new Issue(Issue.ERROR, document.fhirType(),
                            String.valueOf(one)));
                }
            }
            // A profile the document CLAIMS and nothing here carries is an
            // answer, not a crash. HAPI resolves meta.profile itself and
            // throws an Error the request thread does not survive — the
            // caller gets no bytes at all, which is the one response that
            // tells them nothing.
            for (Element claimed : document.getChildrenByName("meta").stream()
                    .flatMap(meta -> meta.getChildrenByName("profile").stream()).toList()) {
                String url = claimed.primitiveValue();
                if (url != null && context().fetchResource(
                        org.hl7.fhir.r5.model.StructureDefinition.class, url) == null) {
                    List<Issue> claimed_ = new ArrayList<>(unreadable);
                    claimed_.add(new Issue(Issue.ERROR, document.fhirType(),
                            "the resource claims the profile '" + url + "', which this tenant "
                                    + "does not have — nothing was checked against it"));
                    return List.copyOf(claimed_);
                }
            }
            // The path a message is reported against: the document's own type,
            // since a caller reading "Patient.name[0]" can find it and a caller
            // reading "[0]" cannot.
            if (shapeReference == null || shapeReference.isBlank()) {
                InstanceValidator validator = borrowValidator();
                try {
                    validator.validate(null, messages, document.fhirType(), document);
                } finally {
                    returnValidator(validator);
                }
            } else {
                if (context().fetchResource(org.hl7.fhir.r5.model.StructureDefinition.class,
                        shapeReference) == null) {
                    // Checked here rather than left to the validator, which
                    // throws for a profile it cannot locate — an exception the
                    // caller sees as a broken server rather than as an answer
                    // about a shape nobody here carries.
                    return List.of(new Issue(Issue.ERROR, document.fhirType(),
                            "the shape '" + shapeReference + "' is not one this face carries, "
                                    + "so nothing was checked against it"));
                }
                InstanceValidator validator = borrowValidator();
                try {
                    validator.validate(null, messages, document.fhirType(), document,
                            shapeReference);
                } finally {
                    returnValidator(validator);
                }
            }
            List<Issue> issues = new ArrayList<>(unreadable);
            issues.addAll(messages.stream()
                    .map(m -> new Issue(severityOf(m),
                            m.getLocation() == null ? document.fhirType() : m.getLocation(),
                            m.getMessage()))
                    .toList());
            issues.addAll(heldSystemMembership(document, document.fhirType()));
            return issues;
        }

        /**
         * Every coding in the document whose system the tenant holds and the
         * definitions do not: the code exists in that system or the element
         * is in error. Strength-independent by design — binding strength
         * governs whether a coding must come from a VALUE SET, not whether a
         * code is real in the system it claims.
         */
        private List<Issue> heldSystemMembership(Element element, String path) {
            if (terms == null) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            if ("Coding".equals(element.fhirType())) {
                String system = element.getNamedChildValue("system");
                String code = element.getNamedChildValue("code");
                if (system != null && code != null
                        && context().fetchResource(
                                org.hl7.fhir.r5.model.CodeSystem.class, system) == null) {
                    terms.membership(system, code)
                            .filter(m -> !m.present())
                            .ifPresent(m -> issues.add(new Issue(Issue.ERROR, path,
                                    "Unknown code '" + code + "' in the code system '" + system
                                            + "' (answered from this tenant's terminology)")));
                }
            }
            for (Element child : element.getChildren()) {
                issues.addAll(heldSystemMembership(child,
                        path + "." + child.getName()));
            }
            return issues;
        }

        /** FHIR's severities, as an outcome spells them. */
        private static String severityOf(ValidationMessage message) {
            return switch (message.getLevel()) {
                case FATAL, ERROR -> Issue.ERROR;
                case WARNING -> "warning";
                default -> "information";
            };
        }

        /**
         * Against the profile a step declares.
         *
         * <p>The same validator and the same message shape — what changes is
         * which definition it is held to. A profile this face does not carry is
         * an issue rather than a pass: a shape nobody can resolve is not a
         * shape a document conformed to, and treating an unresolvable profile
         * as "nothing wrong" is how a step's precondition quietly stops being
         * one.
         */
        @Override
        public List<String> validate(String typeName, Element document, String shapeReference) {
            return refusals(check(typeName, document, shapeReference));
        }

        /** The refusing half: what a write is held to. */
        private static List<String> refusals(List<Issue> issues) {
            return issues.stream().filter(Issue::refuses)
                    .map(issue -> "ERROR " + issue.location() + ": " + issue.message())
                    .toList();
        }

        @Override
        public byte[] write(Element document) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                Manager.compose(context(), document, out, Manager.FhirFormat.JSON,
                        IParser.OutputStyle.NORMAL, null);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write a payload to memory", e);
            }
            return out.toByteArray();
        }
    }

    /**
     * Validators, lent one at a time.
     *
     * <p>Bounded so a burst of concurrent writes cannot retain validators for
     * a load level that has passed. Above the cap a validator is built, used
     * and dropped -- the old behaviour, which is the right thing to degrade to.
     *
     * <p><b>Softly held, because the cap is per tenant and the JVM is not.</b>
     * One appliance serving one tenant pays for a handful of validators; a
     * process holding fifty tenants pays fifty times that, and the pool would
     * be choosing to keep a cache while the work that needs the memory fails.
     * A soft reference inverts it -- the collector takes them back before it
     * takes an OutOfMemoryError, and the store degrades to building one per
     * write, which is exactly what it did before this existed. Found by the
     * test JVM, which runs many tenants at once and started dying under a
     * fixed heap where a single-tenant Pi never would have shown it.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<
            java.lang.ref.SoftReference<InstanceValidator>> lent =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger lentCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int VALIDATOR_CAP = Integer.getInteger(
            "dbo.validator.pool", Runtime.getRuntime().availableProcessors());

    /**
     * Borrow a validator, or build one if none is free.
     *
     * <p>Exclusive while borrowed, which is the invariant that matters: a
     * validator accumulates state about the instance it is checking, so two
     * concurrent writes sharing one is a defect that only appears under load.
     * A pool keeps that guarantee -- it hands each caller its own -- while a
     * thread-local would not, because this server answers every request on a
     * NEW virtual thread and a thread-local would therefore be built once,
     * used once and thrown away.
     *
     * <p><b>Reuse is safe because the library resets itself.</b>
     * {@code InstanceValidator} carries {@code fetchCache},
     * {@code resourceTracker} and {@code xhtmlElementMap} keyed by Element and
     * assigned only in its constructor; if they merely accumulated, a lent
     * validator would retain every tree it had ever seen. The validate
     * overload called here delegates to the one that calls
     * {@code clearInternalState}, which empties them on entry. Checked in the
     * bytecode rather than assumed, because the failure mode is a leak that a
     * short benchmark reports as a win.
     *
     * <p>Why it is worth doing: the constructor loads an OID table from CSV,
     * and under load that parse cost MORE than the validation it exists to
     * set up -- 79 samples against 52 in a 30-dump profile of the write path.
     */
    private InstanceValidator borrowValidator() {
        java.lang.ref.SoftReference<InstanceValidator> held;
        while ((held = lent.poll()) != null) {
            lentCount.decrementAndGet();
            InstanceValidator pooled = held.get();
            if (pooled != null) {
                return pooled;
            }
            // Collected under pressure; keep draining rather than rebuilding
            // on the first cleared slot.
        }
        return newValidator();
    }

    private void returnValidator(InstanceValidator validator) {
        if (lentCount.get() < VALIDATOR_CAP) {
            lentCount.incrementAndGet();
            lent.offer(new java.lang.ref.SoftReference<>(validator));
        }
    }

    private InstanceValidator newValidator() {
        InstanceValidator validator = new InstanceValidator(context(),
                new ElementHostServices(context()),
                XVerExtensionManagerFactory.createExtensionManager(context()),
                new ValidatorSession(), new ValidatorSettings());
        validator.setFetcher(new ElementFetcher(context()));
        // What a write is held to, and what it is not — see the policy.
        validator.setPolicyAdvisor(new ElementValidationPolicy());
        // An extension whose definition this face does not carry is allowed:
        // FHIR says a plain extension may be ignored by a reader that does not
        // know it, and a store that refused one would refuse every
        // implementer's own — including dbo's, which is how this was found.
        validator.setAnyExtensionsAllowed(true);
        // A code system a store does not hold cannot be checked, and refusing
        // what cannot be checked would refuse every private terminology. A code
        // that violates a binding this face CAN check is still refused.
        validator.setUnknownCodeSystemsCauseErrors(false);
        // Coded values are checked, and checked from what is held: the value
        // sets and code systems the face carries. Turning the checks off would
        // accept `gender: unicorn`, which is exactly the write a store must
        // refuse; reaching a terminology server instead would make a write fail
        // when somebody else's server does. The context is offline by
        // construction — see ElementVersion.
        return validator;
    }

    @Override
    public Element read(String typeName, byte[] payload) {
        return reading.read(typeName, payload);
    }

    /**
     * One lookup, one truth: the same {@code fetchResource} the validator
     * resolves a claimed profile through answers what version of it the
     * pack currently publishes. A claim the pack does not carry, or a
     * StructureDefinition without a version, yields no stamp — the store
     * stamps only shapes the pack publishes a version for.
     */
    @Override
    public java.util.List<String> writtenUnder(Element document) {
        java.util.List<String> stamps = new java.util.ArrayList<>();
        for (Element meta : document.getChildrenByName("meta")) {
            for (Element claimed : meta.getChildrenByName("profile")) {
                String url = claimed.primitiveValue();
                if (url == null || url.isBlank()) {
                    continue;
                }
                org.hl7.fhir.r5.model.StructureDefinition sd = context().fetchResource(
                        org.hl7.fhir.r5.model.StructureDefinition.class, url);
                if (sd != null && sd.getVersion() != null && !sd.getVersion().isBlank()) {
                    stamps.add(url + "|" + sd.getVersion());
                }
            }
        }
        return java.util.List.copyOf(stamps);
    }

    /** This view's definitions — the tenant's, where one was derived. */
    SimpleWorkerContext context() {
        return context.get();
    }

    /**
     * What this tenant's pack declares for {@code profile} today, or null
     * when it carries no such shape — which is an ordinary answer, not a
     * conflict: a stamp outlives the pack version that made it.
     */
    String declaredVersionOf(String profile) {
        org.hl7.fhir.r5.model.StructureDefinition sd = context().fetchResource(
                org.hl7.fhir.r5.model.StructureDefinition.class, profile);
        return sd == null || sd.getVersion() == null || sd.getVersion().isBlank()
                ? null : sd.getVersion();
    }

    @Override
    public String typeOf(Element document) {
        return reading.typeOf(document);
    }

    @Override
    public List<String> validate(String typeName, Element document) {
        return reading.validate(typeName, document);
    }

    /** Against a step's declared shape — the same validator, held to a named profile. */
    @Override
    public List<String> validate(String typeName, Element document, String shapeReference) {
        return reading.validate(typeName, document, shapeReference);
    }

    /** Everything, at every severity. */
    @Override
    public List<Issue> check(String typeName, Element document, String shapeReference) {
        return reading.check(typeName, document, shapeReference);
    }

    @Override
    public byte[] write(Element document) {
        return reading.write(document);
    }
}
