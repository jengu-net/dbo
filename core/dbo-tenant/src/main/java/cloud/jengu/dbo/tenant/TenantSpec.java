package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One tenant's declaration: code, FHIR version, configured types.
 * In production these specs come from configuration (git / operator-managed
 * mounts); the manager watches them as files.
 *
 * <p>{@code zone} names the jurisdiction this tenant is a member of;
 * {@code zoneRoot} says this tenant <b>is</b> one. They are two fields because
 * being a jurisdiction and being in one are not exclusive — a jurisdiction
 * holds ordinary records too — and because a zone that is only ever named by
 * its members is a zone no file records.
 */
public record TenantSpec(String code, String face, List<FhirTypeConfig> types,
        boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
        String zone, String broker, List<String> acceptedBrokers,
        List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
        String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
        boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines,
        Set<String> authorised, Set<String> exchanges, String partnerOf,
        Configuration configuration, WorkingKey workingKey, String kind) {

    /**
     * What wraps the tenant's working key, where it is not the deployment's
     * own key: a Transit key in the deployment's Vault, or in a Vault its
     * owner runs.
     *
     * <p>A key the owner holds is one the tenant cannot come up without: the
     * deployment never holds it, and revoking it leaves what the tenant keeps
     * unreadable, to the deployment's operator as to anybody.
     *
     * @param key       the Transit key
     * @param vault     the owner's Vault, or null for the deployment's
     * @param mount     where Transit is mounted there, or null for
     *                  {@code transit}
     * @param authMount where the owner's Vault takes this deployment's service
     *                  account token, or null for {@code jwt}
     * @param role      the role the owner's Vault grants this deployment
     */
    public record WorkingKey(String key, String vault, String mount, String authMount,
            String role) {

        public WorkingKey {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a working key held elsewhere names the key "
                        + "it is wrapped by, as workingKey.key");
            }
            if (vault != null && (role == null || role.isBlank())) {
                throw new IllegalArgumentException("a working key in its owner's Vault is "
                        + "reached by a role that Vault grants this deployment, as "
                        + "workingKey.auth.role");
            }
        }
    }

    /**
     * Where the tenant's owner keeps its configuration: a git repository on a
     * path this deployment can read, and the ref applied when the tenant comes
     * up and when it is asked to apply without naming one.
     *
     * <p>Read, never fetched: keeping the repository current is the
     * deployment's, and the store reaches no remote.
     *
     * @param git the repository's path, a bare repository or a clone
     * @param ref a branch, a tag or a commit
     * @param key the Transit key in the deployment's Vault that the
     *            repository's secrets are wrapped by, or null for
     *            {@code tenant-<code>}
     */
    public record Configuration(String git, String ref, String key) {

        public Configuration(String git, String ref) {
            this(git, ref, null);
        }

        public Configuration {
            if (git == null || git.isBlank()) {
                throw new IllegalArgumentException("a configuration names the repository it "
                        + "is read from, as configuration.git");
            }
            if (ref == null || ref.isBlank()) {
                ref = "HEAD";
            }
        }
    }

    /**
     * A step this tenant offers, and the documents a run of it is over.
     *
     * <p>Declared here rather than stored as a record because it is a
     * contract, not data: the same reason the types are here. A reader of the
     * file can see what the tenant may be asked to do and over what, without
     * querying it.
     *
     * <p>A slot names <b>a face type</b>, which is narrower than the engine's
     * own step declaration — there a slot is an opaque shape reference the
     * engine hands to a face. This is the narrower thing on purpose: what it
     * configures is a face surface, and the surface has to know which type a
     * slot admits in order to refuse the ones it does not.
     *
     * <p><b>What it writes is declared the same way, beside what it takes.</b>
     * A step's result may ask the tenant to hold records, and the tenant
     * holds only the types the step said it writes: reach is granted by
     * declaration and never assumed, on the way out as on the way in. A step
     * declaring none writes nothing, and a result carrying a record of
     * another type is refused by name.
     *
     * @param code   the step's own name, as a run of it will be addressed
     * @param slots  slot name to the type it takes, in declaration order
     * @param writes the types a result of this step may ask the tenant to
     *               write, each one this tenant holds
     * @param retry  which of its failures automation is given again, after how
     *               long and how many times, or null for none — a failure the
     *               step did not declare would pass goes to a person
     * @param automate when automation may take a task of it, decided over the
     *                 task's inputs as it is authored, or null for always
     * @param answer   what the application that asked for a run may collect
     *                 once its work is over, or null where it collects nothing
     *                 and reads the run's answer alone
     * @param starts   when it starts itself, or null where it starts only when
     *                 somebody asks
     * @param lasts    whether a run of it is work that lasts: opened for the
     *                 request in its one slot, held by nobody, moved and ended
     *                 by the runs that are its parts
     * @param awaits   for an exchange a zone declares, what the asker waits for
     *                 from the tenant whose data it is
     * @param secrets  the secrets of the tenant a run of it may reach, by name:
     *                 a step reaches what it declares and nothing else
     */
    /**
     * Whether a step's declared secrets reach a name: one of them is the
     * name, or a family whose prefix the name extends — connection.* reaches
     * connection.a.password, and neither connection nor connections.a.
     */
    public static boolean reaches(java.util.Set<String> declared, String name) {
        for (String secret : declared) {
            if (secret.equals(name)) {
                return true;
            }
            if (secret.endsWith(".*")) {
                String prefix = secret.substring(0, secret.length() - 1);
                if (name.startsWith(prefix) && name.length() > prefix.length()) {
                    return true;
                }
            }
        }
        return false;
    }

    public record Step(String code, java.util.Map<String, String> slots,
            java.util.Set<String> writes, cloud.jengu.dbo.core.process.RetryPolicy retry,
            cloud.jengu.dbo.core.process.AutomationCriterion automate, Answer answer,
            Starts starts, boolean lasts, Awaits awaits, java.util.Set<String> secrets) {

        /** A step that reaches no secret. */
        public Step(String code, java.util.Map<String, String> slots,
                java.util.Set<String> writes, cloud.jengu.dbo.core.process.RetryPolicy retry,
                cloud.jengu.dbo.core.process.AutomationCriterion automate, Answer answer,
                Starts starts, boolean lasts, Awaits awaits) {
            this(code, slots, writes, retry, automate, answer, starts, lasts, awaits,
                    java.util.Set.of());
        }

        /** A step whose asker, where it crosses, waits for the records shared. */
        public Step(String code, java.util.Map<String, String> slots,
                java.util.Set<String> writes, cloud.jengu.dbo.core.process.RetryPolicy retry,
                cloud.jengu.dbo.core.process.AutomationCriterion automate, Answer answer,
                Starts starts, boolean lasts) {
            this(code, slots, writes, retry, automate, answer, starts, lasts, Awaits.RECORDS);
        }

        /** What a crossing on an exchange waits for at the end it left. */
        public enum Awaits {
            /** The owner's List, sealed to the asker's reader: what it shares. */
            RECORDS,
            /** How the owner's run ended, and why: nothing is sealed, nothing read. */
            OUTCOME,
            /** Nothing: the work is the owner's once it is authored there. */
            NONE
        }

        /** A step whose runs are performed, not opened. */
        public Step(String code, java.util.Map<String, String> slots,
                java.util.Set<String> writes, cloud.jengu.dbo.core.process.RetryPolicy retry,
                cloud.jengu.dbo.core.process.AutomationCriterion automate, Answer answer,
                Starts starts) {
            this(code, slots, writes, retry, automate, answer, starts, false);
        }

        /** A step that starts only when somebody asks for it. */
        public Step(String code, java.util.Map<String, String> slots,
                java.util.Set<String> writes, cloud.jengu.dbo.core.process.RetryPolicy retry,
                cloud.jengu.dbo.core.process.AutomationCriterion automate, Answer answer) {
            this(code, slots, writes, retry, automate, answer, null);
        }

        /** A step whose asker collects nothing. */
        public Step(String code, java.util.Map<String, String> slots,
                java.util.Set<String> writes, cloud.jengu.dbo.core.process.RetryPolicy retry,
                cloud.jengu.dbo.core.process.AutomationCriterion automate) {
            this(code, slots, writes, retry, automate, null);
        }

        /** A step whose result writes nothing — it decides, counts, or answers. */
        public Step(String code, java.util.Map<String, String> slots) {
            this(code, slots, java.util.Set.of());
        }

        /** A step that retries nothing. */
        public Step(String code, java.util.Map<String, String> slots,
                java.util.Set<String> writes) {
            this(code, slots, writes, null, null);
        }

        public Step {
            writes = java.util.Set.copyOf(writes);
            secrets = secrets == null ? java.util.Set.of() : java.util.Set.copyOf(secrets);
            for (String secret : secrets) {
                // A FAMILY IS A TRAILING .* AND NOTHING ELSE: a prefix the
                // owner's names extend, never a pattern. A star anywhere else
                // would be a wildcard nobody can read the reach of.
                String named = secret.endsWith(".*")
                        ? secret.substring(0, secret.length() - 2) : secret;
                if (named.isEmpty() || named.contains("*")) {
                    throw new IllegalArgumentException(code + ": the secret '" + secret
                            + "' is a name, or a family of names declared as a prefix and a "
                            + "trailing .* (connection.*); a * anywhere else is not");
                }
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("a step declares a code");
            }
            // A step on a clock is the one that may take nothing: what it is
            // over is whatever it finds when the tick comes, and nobody is
            // there to hand it a record.
            if (slots.isEmpty() && (starts == null || starts.every() == null)) {
                throw new IllegalArgumentException(code + ": a step with no slots is over "
                        + "nothing, and a run of it would reach nothing — declare what it "
                        + "takes, or do not declare the step");
            }
            slots = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(slots));
            if (lasts) {
                // What it fulfils is what makes it this work, so there is one
                // thing it is for, named: a request held here, by reference.
                if (slots.size() != 1 || !cloud.jengu.dbo.core.process.SlotShape.of(
                        slots.values().iterator().next()).referred()) {
                    throw new IllegalArgumentException(code + ": lasts, and is opened for the "
                            + "one request it fulfils — declare one slot, as "
                            + "Reference(ServiceRequest); it takes " + slots);
                }
                if (!writes.isEmpty() || retry != null || automate != null) {
                    throw new IllegalArgumentException(code + ": lasts, and is performed by "
                            + "nobody — what it writes, retries and automates is its parts' "
                            + "to say, not its own");
                }
                if (starts != null && starts.every() != null) {
                    throw new IllegalArgumentException(code + ": lasts, and is opened for a "
                            + "request — a clock names none");
                }
            }
            if (starts != null && starts.every() != null && !slots.isEmpty()) {
                throw new IllegalArgumentException(code + ": starts on a calendar rule and "
                        + "takes " + slots.keySet() + ", and nothing fills a slot when a tick "
                        + "comes — a step on a clock takes nothing and finds what it is over");
            }
            if (starts != null && starts.every() == null) {
                // The record that started it goes into the one slot of its
                // type; two such slots would leave the run guessing which.
                long fits = slots.values().stream()
                        .filter(declared -> cloud.jengu.dbo.core.process.SlotShape.of(declared)
                                .type().equals(starts.type()))
                        .count();
                if (fits != 1) {
                    throw new IllegalArgumentException(code + ": starts on a " + starts.type()
                            + ", and " + (fits == 0 ? "takes no slot" : "takes " + fits
                                    + " slots") + " of that type — the record that starts it "
                            + "goes into exactly one");
                }
            }
            // READ HERE so a malformed form is refused where it was
            // written. Every other reader takes the shape apart again, and a
            // declaration that parses in one place and not another is a
            // tenant that comes up and then cannot start any of its work.
            slots.forEach((slot, declared) -> {
                try {
                    cloud.jengu.dbo.core.process.SlotShape.of(declared);
                } catch (IllegalArgumentException wrong) {
                    throw new IllegalArgumentException(code + ": slot '" + slot + "' — "
                            + wrong.getMessage(), wrong);
                }
            });
        }
    }

    /**
     * When a step starts itself, rather than waiting to be asked.
     *
     * <p>Exactly one occasion: a calendar rule, a change to a record matching
     * a search, or a moment a matching record names. Each run it starts is
     * keyed by the step and that occasion, so however many nodes notice the
     * same occasion there is one run of it.
     *
     * <p><b>The rule is the scheduler's own.</b> A calendar rule is read here
     * by the parser that will fire it, so a rule it would refuse never gets
     * past the declaration, and one firing more often than once a minute is
     * refused too: each tick is a run, kept as history, and a cadence below a
     * minute is a feed nobody can read.
     *
     * <p><b>Where it starts is said</b>, because a tenant served on a site
     * holds the same declaration as its origin and keys are unique per
     * database: unsaid, the cloud and every site would each start the same
     * occasion.
     *
     * @param every  a calendar rule, six fields from seconds, or null
     * @param change a search over one type, as {@code Type?param=value}, whose
     *               matching changes start it, or null
     * @param due    a search whose matching records start it at the moment
     *               they name, or null
     * @param at     the element of a {@code due} record naming its moment
     * @param after  how long after that moment it is due, zero for at it
     * @param missed what a tick missed while no node ran becomes on return;
     *               only for a calendar rule
     * @param keep   how long the runs it starts are kept
     * @param where  where it starts
     */
    public record Starts(String every, String change, String due, String at,
            java.time.Duration after, Missed missed, java.time.Duration keep, Where where) {

        /** What a tick missed while no node ran becomes. */
        public enum Missed {
            /** one run on return, for all the ticks missed, and it says how many */
            ONCE,
            /** nothing; the next tick comes when due */
            SKIP
        }

        /** Where a self-starting step starts. */
        public enum Where {
            /** where the tenant is served from its own database, and nowhere else */
            ORIGIN,
            /** on each site the tenant is served at, and not at its origin */
            PLACES,
            /** at its origin and on each of its sites, each its own occasion */
            EVERYWHERE
        }

        /** The type whose records start it, or null for a calendar rule. */
        public String type() {
            String search = change != null ? change : due;
            if (search == null) {
                return null;
            }
            int query = search.indexOf('?');
            return query < 0 ? search : search.substring(0, query);
        }
    }

    /**
     * Who the application that asked for a run is, once the run's work is
     * over, and for how long it may collect what the run was given and what
     * it produced.
     *
     * <p><b>An audience the tenant declared</b>, resolved here so the step
     * and the declaration it leans on are read together: a step naming an
     * audience nobody declared is configuration that disagrees with itself,
     * refused where it was written rather than answered as silence at the
     * first collection. Its types bound what is collectable and its mode is
     * what a collection reveals; the request cannot raise either.
     *
     * <p><b>The purpose is the step's.</b> An audience revealing a person
     * whole needs one, said here, and a collection reveals her only when the
     * request states the same code — the reason said when the step was
     * declared and again at the moment of reading. Either alone gets the
     * strict mode.
     *
     * @param audience the name under {@code disclosure.perAudience}
     * @param types    what the audience may be answered about
     * @param reveals  what a collection of a person reveals
     * @param collect  how long after the result is written the asker may
     *                 collect
     * @param purpose  the PurposeOfUse code the step reads a person for, or
     *                 null for none
     */
    public record Answer(String audience, Set<String> types,
            cloud.jengu.dbo.core.api.Disclosure.Mode reveals, java.time.Duration collect,
            String purpose) {

        public Answer {
            types = Set.copyOf(types);
        }

        /**
         * What a collection reveals, given the purpose its request stated:
         * the audience's mode where both keys agree, and never more than the
         * strict mode otherwise.
         */
        public cloud.jengu.dbo.core.api.Disclosure.Mode revealing(String stated) {
            if (reveals != cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE) {
                return reveals;
            }
            return purpose != null && purpose.equals(stated)
                    ? cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE
                    : cloud.jengu.dbo.core.api.Disclosure.Mode.OMIT;
        }
    }

    /**
     * A step the DEPLOYMENT performs, for every tenant that admits it.
     *
     * <p>Its own key rather than more properties on {@code steps}, because a
     * field meaning one thing in a tenant's declaration and another in the
     * management tenant's is how {@code mandatorySteps} came to mean two
     * things. It also makes the refusal easy to say: an ordinary tenant may
     * not declare one of these AT ALL, which is clearer against a key that has
     * no business in its file than against extra properties on a key that has.
     *
     * <p>Declared here and nowhere else, so that what a deployment does with
     * every tenant's data is one document rather than an audit — the register
     * a tenant reads, the queues that carry the work and the invariant that a
     * code belongs to one level are all derived from this one entry.
     *
     * <p><b>Slots are not checked against the declaring tenant's types</b>,
     * unlike a tenant's own step. The types a fleet step is over belong to the
     * tenants whose work it performs, and the management tenant holds none of
     * them; checking them here would refuse every real declaration.
     *
     * @param code      the step's own name, as a run of it is addressed
     * @param slots     slot name to the type it takes, in declaration order
     * @param opens     the slots it OPENS rather than carries — the register a
     *                  tenant reads is these and only these, because a step
     *                  that reads an envelope discloses nothing
     * @param required  whether a tenant may decline it. Admitted is the
     *                  ordinary case; required is an agreement signed by
     *                  joining, and is declared HERE rather than in a tenant's
     *                  file so the set is enumerable and readable before
     *                  anyone joins
     * @param posture   what happens to work whose processing a tenant has not
     *                  yet approved
     * @param substrate where this step's queue lives; absent means the
     *                  deployment's own. Several steps may name one
     */
    public record FleetStep(String code, java.util.Map<String, String> slots,
            Set<String> opens, boolean required, Posture posture, String substrate,
            Set<String> delegatesTo, boolean lasts, Starts starts) {

        /** Carried out by the provider itself, handed to no partner. */
        public FleetStep(String code, java.util.Map<String, String> slots,
                Set<String> opens, boolean required, Posture posture, String substrate) {
            this(code, slots, opens, required, posture, substrate, Set.of());
        }

        /** A step asked for, never starting itself, and performed rather than opened. */
        public FleetStep(String code, java.util.Map<String, String> slots,
                Set<String> opens, boolean required, Posture posture, String substrate,
                Set<String> delegatesTo) {
            this(code, slots, opens, required, posture, substrate, delegatesTo, false, null);
        }

        /**
         * This step as each tenant admitting it holds it, where it starts itself
         * there or lasts: the same rules a tenant's own such step is held to,
         * read where the deployment declared it.
         */
        public Step asStep() {
            return new Step(code, slots, Set.of(), null, null, null, starts, lasts);
        }

        /**
         * What a deployment does with work it has not been approved to process.
         *
         * <p>Stated once per step rather than once per deployment because a
         * brand-new row and a widened one are different acts, and a deployment
         * may halt for the first without stopping everything else.
         */
        public enum Posture {
            /** Processed under the agreement the tenant signed by joining. */
            APPLIED,
            /**
             * Processed, and the fact recorded as an incident that stands
             * until the row is approved. The default, and the cost is accepted
             * rather than argued away: a halting default would turn a register
             * nobody answered into an outage nobody caused.
             */
            PROCESSED_AND_NAMED,
            /**
             * Not processed at all until approved. Refusal is real here and
             * nowhere else in this design, because approval is known before
             * the payload is sealed — so declining to seal actually prevents
             * the processing rather than detecting it afterwards.
             */
            NOT_UNTIL_APPROVED;

            static Posture of(String wire, String code) {
                if (wire == null || wire.isBlank()) {
                    return PROCESSED_AND_NAMED;
                }
                for (Posture posture : values()) {
                    if (posture.name().equalsIgnoreCase(wire.replace('-', '_'))) {
                        return posture;
                    }
                }
                throw new IllegalArgumentException(code + ": '" + wire + "' is not a posture "
                        + "for unapproved processing. It is one of 'applied', "
                        + "'processed-and-named' or 'not-until-approved', or absent for "
                        + "'processed-and-named'");
            }
        }

        public FleetStep {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("a fleet step declares a code");
            }
            if (slots.isEmpty()) {
                throw new IllegalArgumentException(code + ": a step with no slots is over "
                        + "nothing, and a run of it would reach nothing — declare what it "
                        + "takes, or do not declare the step");
            }
            slots = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(slots));
            if (lasts || starts != null) {
                // Held to a tenant's own rules for such a step, here, so a
                // deployment declaring one that cannot hold is refused where it
                // was written rather than in every tenant that admits it.
                new Step(code, slots, Set.of(), null, null, null, starts, lasts);
            }
            // As above: refused where it was written.
            slots.forEach((slot, declared) -> {
                try {
                    cloud.jengu.dbo.core.process.SlotShape.of(declared);
                } catch (IllegalArgumentException wrong) {
                    throw new IllegalArgumentException(code + ": slot '" + slot + "' — "
                            + wrong.getMessage(), wrong);
                }
            });
            opens = Set.copyOf(opens);
            for (String opened : opens) {
                if (!slots.containsKey(opened)) {
                    // A slot it opens and does not take is a register row
                    // about nothing, and the register is the whole reason
                    // this is declared rather than discovered.
                    throw new IllegalArgumentException(code + ": it opens slot '" + opened
                            + "' and does not take it. What a step opens is a subset of what "
                            + "it is over; this takes " + new java.util.TreeSet<>(slots.keySet()));
                }
            }
            if (posture == null) {
                posture = Posture.PROCESSED_AND_NAMED;
            }
            delegatesTo = Set.copyOf(delegatesTo);
            if (!delegatesTo.isEmpty() && opens.isEmpty()) {
                // A partner is named so that a tenant knows who handles its
                // data, on the register row for what is opened. A step that
                // opens nothing has no row to name it on.
                throw new IllegalArgumentException(code + ": hands its work to "
                        + new java.util.TreeSet<>(delegatesTo) + " and opens nothing, so no "
                        + "tenant's register could say so. A partner is named on a step that "
                        + "opens what it handles");
            }
            for (String partner : delegatesTo) {
                if (!isCode(partner)) {
                    throw new IllegalArgumentException(code + ": a partner is a tenant, and '"
                            + partner + "' is not a tenant code");
                }
            }
        }

        /**
         * Which substrate this step's queue lives on.
         *
         * <p>Its own name when it named none, so "no placement stated" means
         * a substrate of its own rather than a shared default. A deployment
         * running everything in one application points several steps at one
         * name; a deployment scaling a step leaves it alone.
         */
        public String substrateName() {
            return substrate == null || substrate.isBlank() ? code : substrate;
        }

        /**
         * Whether this step reads anything a tenant's register must show.
         *
         * <p>A step that opens nothing is a router: it reads the envelope and
         * moves the work, and discloses nothing to anybody. That is why
         * requiring one is an operational act and requiring a processor is
         * not.
         */
        public boolean isProcessor() {
            return !opens.isEmpty();
        }
    }

    /**
     * The rule that an ordinary tenant declares no step the deployment
     * performs, applied by whoever knows which tenant is which.
     *
     * <p>It lives here, with the declaration, and is called from the sweep
     * that turns declarations into tenants — because the rule is about what a
     * file may say and the sweep is the only reader that knows whose file it
     * is. The parser cannot: a management descriptor and a tenant's are the
     * same document type, read by the same code, and which is which is a fact
     * about the deployment's configuration rather than about the text.
     *
     * <p><b>Refused rather than ignored.</b> A deployment-level step sitting
     * in a tenant's file reads as a thing being done, and the tenant would
     * have every reason to believe its data was being processed that way. It
     * is also half the invariant the rest of this design rests on — one code
     * belongs to one level — and a declaration is much the cheaper place to
     * see the contradiction than two schedulers reaching for one run.
     */
    public static void onlyTheDeploymentDeclaresFleetSteps(TenantSpec spec) {
        if (spec.fleetSteps().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("tenant '" + spec.code() + "' declares "
                + spec.fleetSteps().stream().map(FleetStep::code).sorted().toList()
                + " under 'fleetSteps', and a tenant may not: those are steps the DEPLOYMENT "
                + "performs for every tenant that admits them, declared in the management "
                + "tenant's own descriptor and nowhere else. A step this tenant offers itself "
                + "goes under 'steps'.");
    }

    /**
     * Without a configuration of the owner's own: what every tenant was while
     * only its declaration configured it.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines,
            Set<String> authorised, Set<String> exchanges, String partnerOf) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, declines, authorised, exchanges, partnerOf, null);
    }

    /** Of the kind its declaration makes it. */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines,
            Set<String> authorised, Set<String> exchanges, String partnerOf,
            Configuration configuration, WorkingKey workingKey) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, declines, authorised, exchanges, partnerOf,
                configuration, workingKey, null);
    }

    /** With its working key wrapped by the deployment's own key. */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines,
            Set<String> authorised, Set<String> exchanges, String partnerOf,
            Configuration configuration) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, declines, authorised, exchanges, partnerOf,
                configuration, null);
    }

    /**
     * Without a provider to be a partner of: what every tenant was while a
     * deployment carried out all of its processes itself.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines,
            Set<String> authorised, Set<String> exchanges) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, declines, authorised, exchanges, null);
    }

    /**
     * Without exchanges: what every zone was while a jurisdiction only said
     * what was true in it, and nothing about what its members may do for each
     * other.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines,
            Set<String> authorised) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, declines, authorised, Set.of(), null);
    }

    /**
     * Without an authorisation on record: what every tenant was before a
     * deployment could open anything of its data, and what one still is until
     * it has read the register once.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps, Set<String> declines) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, declines, Set.of());
    }

    /**
     * Without anything declined: what every tenant was while a deployment's
     * steps were admitted by saying nothing, which is still the default.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace, List<FleetStep> fleetSteps) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, fleetSteps, Set.of());
    }

    /**
     * Without steps the deployment performs for every tenant: what every
     * tenant was while the only steps that existed were the ones a tenant
     * offered itself.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot,
            boolean indexFace) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                indexFace, List.of());
    }

    /**
     * Without an index face: what every tenant was while a write could only be
     * judged against a loaded specification.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, zoneRoot,
                false);
    }

    /**
     * Without a declared zone: what every tenant was while a zone was made by
     * its members rather than by itself.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, false);
    }

    /**
     * Without offered steps: every tenant before one could be reached through
     * the work it declares rather than through its records directly.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, List.of());
    }

    /**
     * Without a face root: what every tenant was before a version's
     * definitions could be held as records.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, false);
    }

    /**
     * Without a partner: the shape every tenant had before one tenant could
     * manage others. {@code managedBy} names the partner tenant that may
     * follow this tenant's work — declared here, at creation, never inferred
     * from who happens to be looking.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, null);
    }

    /** Compatibility: the pre-mandatory-steps shape. */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, List.of());
    }

    /** Compatibility: the pre-SCIM shape, still what most specs declare. */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, null);
    }

    /**
     * The tenant's SCIM declaration (REQ-DBO-SCIM-DECLARED-PER-TENANT):
     * {@code system} is the identifier namespace externalId values are
     * claimed in. Absent the block, the endpoints do not exist.
     */
    public record Scim(String system) {
        public Scim {
            if (system == null || system.isBlank()) {
                throw new IllegalArgumentException(
                        "scim needs 'system' — the namespace externalId values live in");
            }
        }
    }

    /**
     * A declared content dependency (REQ-DBO-SYNC-SPEC-DECLARED):
     * {@code name} is the direct upstream tenant's code; only the declared
     * types stream. Declarations are configuration — the runtime wires the
     * stream at bring-up and removes it when the declaration disappears.
     */
    /**
     * @param face whether this dependency is the tenant's face chain — the
     *             root it takes its version's definitions from. At most one,
     *             and never the same chain as a jurisdiction: a zone says what
     *             is true here, a face says what a resource is, and a tenant
     *             chooses each on its own.
     */
    public record Dependency(String name, Set<String> types, boolean face) {

        public Dependency(String name, Set<String> types) {
            this(name, types, false);
        }
        public Dependency {
            if (name == null || !CODE.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid dependency name: " + name);
            }
            types = Set.copyOf(types);
            if (types.isEmpty()) {
                throw new IllegalArgumentException(
                        name + ": a dependency must declare at least one type");
            }
        }
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers, List.of());
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies) {
        this(code, face, types, pdi, policies, null, null, List.of(), List.of());
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types) {
        this(code, face, types, false, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types, boolean pdi) {
        this(code, face, types, pdi, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    // The platform's tenant-code contract: lowercase label, hyphens, no
    // fixed length cap on the platform side (story tenants carry
    // story+timestamp+nonce and reach ~70 chars).
    // 128 is generous headroom; databaseName() folds ANY length into a
    // Postgres-safe identifier. Underscores stay accepted for old specs.
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]{0,127}");
    // A process id: the two leading parts of a step id, StepId.processId().
    private static final Pattern PROCESS =
            Pattern.compile("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*");

    /**
     * Whether a string is a tenant code at all.
     *
     * <p>Said once, here, because a second opinion about what a code may look
     * like is a tenant that can be made and not unmade: the drop refused
     * every code with a hyphen in it — most of them — while the spec had
     * accepted them all along.
     */
    public static boolean isCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    public TenantSpec {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid tenant code: " + code);
        }
        // (databaseName() below derives a Postgres-safe name; the code
        // itself only has to be URL- and file-name-safe)
        // Which faces exist is not a spec's business and never was: a closed
        // check here rejected R6 by the name of the requirement asking for it,
        // and a face that is not a FHIR version at all could not be declared.
        // What a face has to be here is a name; whether anything serves it is
        // answered at bring-up by what is installed
        // (REQ-DBO-VER-CONCURRENT-VERSIONS, R6).
        if (face == null || face.isBlank()) {
            throw new IllegalArgumentException(code + ": face is required");
        }
        // WHAT KIND IT IS, said or made: a label something may select it by,
        // and one that has to agree with what the declaration makes it.
        kind = cloud.jengu.dbo.tenant.api.TenantKinds.resolve(code, kind, faceRoot);
        types = List.copyOf(types);
        if (types.isEmpty()) {
            throw new IllegalArgumentException(code + ": at least one type required");
        }
        // A ZONE IS NOT ITS OWN PARENT. Zones form a tree, and naming itself
        // is the shortest cycle in one: a jurisdiction bound by its own rules
        // is bound by nothing above it.
        if (zone != null && zone.equals(code)) {
            throw new IllegalArgumentException(code + ": names itself as its zone, and a "
                    + "zone's zone is the jurisdiction it belongs to");
        }
        exchanges = Set.copyOf(exchanges);
        if (partnerOf != null && (partnerOf.equals(code) || !CODE.matcher(partnerOf).matches())) {
            throw new IllegalArgumentException(code + ": is a partner of '" + partnerOf
                    + "', and a partner is a partner of the service provider, another tenant");
        }
        if (!exchanges.isEmpty() && !zoneRoot) {
            throw new IllegalArgumentException(code + ": declares exchanges "
                    + new java.util.TreeSet<>(exchanges) + " and is not a zone. What "
                    + "members may do for each other is a jurisdiction's to permit, said in "
                    + "a file that also says \"zoneRoot\": true");
        }
        for (String process : exchanges) {
            if (!PROCESS.matcher(process).matches()) {
                throw new IllegalArgumentException(code + ": an exchange is a process, "
                        + "<module>.<process>, and this one is: " + process);
            }
        }
        dependencies = List.copyOf(dependencies);
        for (Dependency dependency : dependencies) {
            if (dependency.name().equals(code)) {
                throw new IllegalArgumentException(code + ": cannot depend on itself");
            }
        }
        // Each entry must be a well-formed step id NOW, at parse: a typo
        // refused by name here beats one that silently never matches any
        // installed step and holds the tenant down with no visible cause.
        mandatorySteps = List.copyOf(mandatorySteps);
        for (String stepId : mandatorySteps) {
            try {
                cloud.jengu.dbo.core.process.StepId.of(stepId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        code + ": mandatory step — " + e.getMessage());
            }
        }
        // Scim declared without what scim is made of, refused here for the
        // reason a malformed step id is: what a spec can be wrong about on its
        // own, the spec answers for, and a file somebody has to change is
        // better named now than at a bring-up that got further.
        //
        // Two of the three things that door needs are the spec's own — the
        // vault it enumerates through, and the person types its mapping
        // writes. The third is an authority, which is how the deployment is
        // configured rather than anything this file says, so it is not asked
        // about here and cannot be.
        if (scim != null) {
            List<String> missing = new java.util.ArrayList<>();
            if (!pdi) {
                missing.add("pdi (the enumeration answering the user list is a vault method, "
                        + "and without a vault there is nothing to enumerate)");
            }
            Set<String> declared = new java.util.HashSet<>();
            for (FhirTypeConfig type : types) {
                declared.add(type.typeName());
            }
            if (!declared.contains("Person") || !declared.contains("Practitioner")) {
                missing.add("declared Person and Practitioner types (the mapping writes them, "
                        + "and a User is the person with a capacity beside it)");
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(code + ": scim is declared and this spec "
                        + "does not carry what it is made of — " + String.join("; ", missing));
            }
        }
    }

    /**
     * Parses the spec file format: {"code":..,"face":..,
     * "types":[{name,identity,systems?,handling?}],"dependencies":[{name,types}],
     * "mandatorySteps":["&lt;module&gt;.&lt;process&gt;.&lt;step&gt;"]}.
     *
     * <p>{@code mandatorySteps} is the catalogue's one consistency claim
     *: the steps whose absence is an incident rather than normal
     * elasticity. The tenant serves and its runs queue either way — the list
     * classifies ({@link StepIncidents}), it never gates — and every step not
     * listed is non-critical by construction.
     *
     * <p><b>{@code handling} is required</b>. A type that
     * has not said what kind of data it is stops the tenant coming up, named,
     * rather than being guessed at — the same rule
     * {@link cloud.jengu.dbo.core.api.TypeRegistration} applies to types
     * declared in code, now applied to types declared in a spec.
     *
     * <p>There is no default because a default here is a silent decision about
     * a hospital's data: guessing {@code operational} would let a tenant edit a
     * vocabulary it does not own, and guessing anything stricter would refuse
     * writes nobody could explain. An unknown value is refused for the same
     * reason a missing one is — a typo must not become a classification.
     */
    public static TenantSpec parse(String json) {
        Object root = Json.parse(json);
        String code = Json.str(root, "code");
        // The field selects a face, and a face need not be a FHIR version, so
        // the old name is refused rather than honoured. Accepting both would
        // be the kindness that survives a decade: two names for one field,
        // and a reader with no way to know which one this deployment obeys.
        if (Json.strOpt(root, "fhirVersion") != null) {
            throw new IllegalArgumentException(code + ": 'fhirVersion' selects a face and is "
                    + "named 'face'; a face need not be a FHIR version at all");
        }
        String face = Json.str(root, "face");
        List<FhirTypeConfig> types = Json.array(root, "types").stream().map(t -> {
            String name = Json.str(t, "name");
            String identity = Json.str(t, "identity");
            Set<String> systems = Set.copyOf(Json.strings(t, "systems"));
            FhirTypeConfig config = switch (identity) {
                case "identifier" -> new FhirTypeConfig(name, IdentityClass.IDENTIFIER, systems,
                        Handling.operational());
                case "canonical" -> FhirTypeConfig.canonical(name);
                case "internal" -> FhirTypeConfig.internal(name);
                default -> throw new IllegalArgumentException(
                        code + "/" + name + ": unknown identity class " + identity);
            };
            String handling = Json.strOpt(t, "handling");
            if (handling == null) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": no declared handling — say what kind of data this is (who may "
                        + "write it, whether it may change, whether it is kept, whether it "
                        + "may leave). There is no default: guessing would be a silent "
                        + "decision about somebody's data");
            }
            String extractor = Json.strOpt(t, "extractor");
            if (extractor != null && !"database".equals(extractor)
                    && !"in-process".equals(extractor)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown extractor " + extractor + " — say 'database' to have the "
                        + "envelope computed where the bytes are, or leave it out");
            }
            FhirTypeConfig placed = "database".equals(extractor)
                    ? config.inTheDatabase() : config;
            String definition = Json.strOpt(t, "definition");
            if (definition != null && !"none".equals(definition)
                    && !"by-the-face".equals(definition)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown definition " + definition + " — say 'none' for a type "
                        + "this face has no definition for, or leave it out");
            }
            if ("none".equals(definition)) {
                placed = placed.withoutADefinition();
            }
            String unknown = Json.strOpt(t, "unknown");
            if (unknown != null && !"refused".equals(unknown) && !"kept".equals(unknown)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown unknown " + unknown + " — say 'kept' to hold an element "
                        + "this type's definition does not declare, or leave it out and it "
                        + "is refused");
            }
            if ("kept".equals(unknown)) {
                placed = placed.keepingWhatItCannotRead();
            }
            String verdict = Json.strOpt(t, "verdict");
            if (verdict != null && !"database".equals(verdict)
                    && !"toolchain".equals(verdict)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown verdict " + verdict + " — say 'database' to have this "
                        + "store's own checks decide a write of this type, or leave it out");
            }
            if ("database".equals(verdict)) {
                placed = placed.decidedByTheDatabase();
            }
            return placed.handledAs(switch (handling) {
                case "operational" -> Handling.operational();
                case "projected-config" -> Handling.projectedConfig();
                case "replicated" -> Handling.replicated();
                case "mirrored" -> Handling.mirrored();
                case "store-authored" -> Handling.storeAuthored();
                case "audit" -> Handling.audit();
                case "ephemeral" -> Handling.ephemeral();
                default -> throw new IllegalArgumentException(
                        code + "/" + name + ": unknown handling " + handling);
            });
        }).toList();
        List<Dependency> dependencies = Json.array(root, "dependencies").stream()
                .map(d -> new Dependency(Json.str(d, "name"),
                        Set.copyOf(Json.strings(d, "types")), Json.bool(d, "face")))
                .toList();
        if (dependencies.stream().filter(Dependency::face).count() > 1) {
            throw new IllegalArgumentException(code + ": two dependencies are declared as the "
                    + "face chain, and a tenant is one version — name one");
        }
        Object scimNode = Json.objOpt(root, "scim");
        Scim scim = scimNode == null ? null : new Scim(Json.str(scimNode, "system"));
        boolean pdi = Json.bool(root, "pdi");
        if (scim != null && !pdi) {
            // A staff directory is identifying data by definition; serving it
            // from a store that keeps identity in the clear would be a quiet
            // decision about everybody in it.
            throw new IllegalArgumentException(code + ": scim requires pdi");
        }
        if (scim != null) {
            // What identifies a person is declared ONCE, on the type. This
            // only says which of Person's systems the directory speaks — a
            // selector, not a second declaration.
            //
            // They used to be two. `scim.system` named a namespace nowhere
            // else mentioned, the membrane read only the type's own systems,
            // and the directory's uniqueness quietly went missing: a second
            // User claiming one employee's externalId was created rather than
            // refused, and lookup by it answered nothing. A rule that has to
            // be remembered in two vocabularies is one that gets remembered in
            // one of them.
            // A tenant declaring no Person at all is left to bring-up, which
            // already records that scim is declared and unservable. This is
            // about the declaration disagreeing with itself, not about the one
            // that is missing.
            java.util.Optional<cloud.jengu.dbo.fhir.common.FhirTypeConfig> person = types.stream()
                    .filter(t -> "Person".equals(t.typeName()))
                    .findFirst();
            if (person.isPresent() && !person.get().identitySystems().contains(scim.system())) {
                throw new IllegalArgumentException(code + ": scim speaks '" + scim.system()
                        + "' and Person is not declared identified by it. Declare Person "
                        + "with \"identity\":\"identifier\" and that system among its "
                        + "\"systems\" — what identifies somebody is said once, on the type, "
                        + "and scim names which of those the directory uses");
            }
        }
        // The steps this tenant offers, and what each is over. A slot naming a
        // type the tenant does not hold is refused here rather than at the
        // first run of it: the spec is where a declaration disagreeing with
        // itself is cheapest to find.
        List<Step> steps = new java.util.ArrayList<>();
        // Read before the steps, because a step names an audience and is
        // refused where it names one this tenant never declared.
        cloud.jengu.dbo.policy.TenantPolicies policies =
                cloud.jengu.dbo.policy.TenantPolicies.parse(root);
        {
            java.util.Set<String> held = types.stream()
                    .map(cloud.jengu.dbo.fhir.common.FhirTypeConfig::typeName)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> seen = new java.util.HashSet<>();
            // A ZONE'S EXCHANGES ARE OVER ITS MEMBERS' DATA. A step a zone
            // declares within a process it permits is where its members ask
            // each other for work, so what it takes is a type the members
            // hold, which the jurisdiction itself has no reason to.
            boolean zone = Json.bool(root, "zoneRoot");
            java.util.Set<String> exchanged = exchangesOf(root);
            for (Object step : Json.array(root, "steps")) {
                String stepCode = Json.str(step, "code");
                // A step's name is the engine's, so it is checked against the
                // engine's rule here rather than at the first run of it: the
                // spec is read once and the run is attempted under load.
                cloud.jengu.dbo.core.process.StepId.of(stepCode);
                if (!seen.add(stepCode)) {
                    throw new IllegalArgumentException(code + ": step '" + stepCode
                            + "' is declared twice, and a run addressed by that name could "
                            + "not say which was meant");
                }
                java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
                if (Json.objOpt(step, "slots") instanceof java.util.Map<?, ?> named) {
                    named.forEach((slot, type) -> slots.put(String.valueOf(slot),
                            String.valueOf(type)));
                }
                for (java.util.Map.Entry<String, String> slot : slots.entrySet()) {
                    // THE TYPE, not the declared form. A slot may be written
                    // Reference(Organization) or Organization[], and what has
                    // to be a type this tenant holds is what is inside either
                    // — checking the whole string would refuse every slot that
                    // said anything beyond a bare type.
                    cloud.jengu.dbo.core.process.SlotShape shape =
                            cloud.jengu.dbo.core.process.SlotShape.of(slot.getValue());
                    if (!held.contains(shape.type()) && !(zone
                            && exchanged.contains(ZoneTree.processOf(stepCode)))) {
                        throw new IllegalArgumentException(code + ": step '" + stepCode
                                + "' takes '" + shape.type() + "' in slot '" + slot.getKey()
                                + "', and this tenant does not declare that type. It holds: "
                                + new java.util.TreeSet<>(held));
                    }
                }
                java.util.Set<String> writes = new java.util.LinkedHashSet<>();
                for (Object type : Json.objOpt(step, "writes") instanceof List<?> named
                        ? named : List.of()) {
                    // A type this tenant holds, checked where it was written:
                    // a step declared to write what the tenant cannot hold
                    // would come up and have every result refused.
                    if (!held.contains(String.valueOf(type)) && !(zone
                            && exchanged.contains(ZoneTree.processOf(stepCode)))) {
                        throw new IllegalArgumentException(code + ": step '" + stepCode
                                + "' writes '" + type + "', and this tenant does not declare "
                                + "that type. It holds: " + new java.util.TreeSet<>(held));
                    }
                    writes.add(String.valueOf(type));
                }
                // Which failures pass is the step's to say, and it is read
                // here so a policy that cannot hold is refused where it was
                // written rather than at the first failure.
                Object retryNode = Json.objOpt(step, "retry");
                cloud.jengu.dbo.core.process.RetryPolicy retry = null;
                if (retryNode != null) {
                    String attempts = Json.strOpt(retryNode, "attempts");
                    try {
                        retry = new cloud.jengu.dbo.core.process.RetryPolicy(
                                Json.strings(retryNode, "on"), Json.strOpt(retryNode, "after"),
                                attempts == null ? 1
                                        : new java.math.BigDecimal(attempts).intValueExact());
                    } catch (IllegalArgumentException | ArithmeticException wrong) {
                        throw new IllegalArgumentException(code + ": step '" + stepCode
                                + "' — " + wrong.getMessage(), wrong);
                    }
                }
                // When automation may take it, compiled here: a condition the
                // store cannot evaluate, or one that would have to unseal a
                // person to decide, is refused where it was written.
                Object automateNode = Json.objOpt(step, "automate");
                cloud.jengu.dbo.core.process.AutomationCriterion automate = null;
                if (automateNode != null) {
                    cloud.jengu.dbo.pdi.PdiSpec persons = cloud.jengu.dbo.pdi.PdiSpec.fhir();
                    try {
                        automate = cloud.jengu.dbo.core.process.AutomationCriterion.compile(
                                Json.strOpt(automateNode, "when"), slots,
                                (type, element) -> persons.identifyingElements(type)
                                        .contains(element));
                    } catch (IllegalArgumentException wrong) {
                        throw new IllegalArgumentException(code + ": step '" + stepCode
                                + "' — " + wrong.getMessage(), wrong);
                    }
                }
                Object startsNode = Json.objOpt(step, "starts");
                Starts starts;
                try {
                    starts = starts(startsNode, held);
                } catch (IllegalArgumentException wrong) {
                    throw new IllegalArgumentException(code + ": step '" + stepCode
                            + "' — " + wrong.getMessage(), wrong);
                }
                String awaited = Json.strOpt(step, "awaits");
                Step.Awaits awaits = Step.Awaits.RECORDS;
                if (awaited != null) {
                    try {
                        awaits = named(Step.Awaits.class, "awaits", awaited);
                    } catch (IllegalArgumentException wrong) {
                        throw new IllegalArgumentException(code + ": step '" + stepCode
                                + "' — " + wrong.getMessage(), wrong);
                    }
                }
                steps.add(new Step(stepCode, slots, writes, retry, automate,
                        answer(code, stepCode, step, policies), starts, Json.bool(step, "lasts"),
                        awaits, Set.copyOf(Json.strings(step, "secrets"))));
            }
        }
        // The steps the DEPLOYMENT performs, which only the management
        // tenant's declaration may carry. Parsed for every spec and refused
        // for the others where a declaration becomes a tenant, rather than
        // here: this is the one reader that cannot know which tenant manages
        // the deployment, and a refusal that cannot name the rule it is
        // enforcing is worse than the one that can.
        List<FleetStep> fleetSteps = new java.util.ArrayList<>();
        {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object one : Json.array(root, "fleetSteps")) {
                String stepCode = Json.str(one, "code");
                cloud.jengu.dbo.core.process.StepId.of(stepCode);
                if (!seen.add(stepCode)) {
                    throw new IllegalArgumentException(code + ": fleet step '" + stepCode
                            + "' is declared twice, and a run addressed by that name could "
                            + "not say which was meant");
                }
                java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
                if (Json.objOpt(one, "slots") instanceof java.util.Map<?, ?> named) {
                    named.forEach((slot, type) -> slots.put(String.valueOf(slot),
                            String.valueOf(type)));
                }
                // When it starts itself in each tenant admitting it, read by the
                // rules a tenant's own step is — except the types, which are the
                // tenants', not the deployment's.
                Object fleetStartsNode = Json.objOpt(one, "starts");
                Starts fleetStarts;
                try {
                    fleetStarts = starts(fleetStartsNode, null);
                } catch (IllegalArgumentException wrong) {
                    throw new IllegalArgumentException(code + ": fleet step '" + stepCode
                            + "' — " + wrong.getMessage(), wrong);
                }
                fleetSteps.add(new FleetStep(stepCode, slots,
                        Set.copyOf(Json.strings(one, "opens")),
                        Json.bool(one, "required"),
                        FleetStep.Posture.of(Json.strOpt(one, "posture"), stepCode),
                        Json.strOpt(one, "substrate"),
                        Set.copyOf(Json.strings(one, "delegatesTo")),
                        Json.bool(one, "lasts"), fleetStarts));
            }
        }
        return new TenantSpec(code, face, types, pdi, policies,
                Json.strOpt(root, "zone"), Json.strOpt(root, "broker"),
                Json.strings(root, "acceptedBrokers"), dependencies, scim,
                Json.strings(root, "mandatorySteps"), Json.strOpt(root, "managedBy"),
                // A face root holds its version's definitions as records —
                // the one place the carried packages are ever read.
                Json.bool(root, "faceRoot"), List.copyOf(steps),
                // A zone says it is one. Members name it, and naming is not
                // appointing: see the check at bring-up.
                Json.bool(root, "zoneRoot"),
                // Judged from the definition index rather than from a loaded
                // specification. Declared and not discovered, because it
                // decides what every write of this tenant is checked against.
                Json.bool(root, "indexFace"),
                List.copyOf(fleetSteps),
                // ADMITTED BY SAYING NOTHING, which is the ordinary case: a
                // deployment's steps are what it does for every tenant that
                // joined, and a tenant listing each one it accepts would turn
                // an agreement into a per-step click. What a tenant writes down
                // is the exception — and a step the deployment REQUIRES cannot
                // be among them, which is refused where the levels meet.
                Set.copyOf(Json.strings(root, "declines")),
                // WHICH ROWS THIS TENANT AUTHORISED, each by its own digest.
                //
                // All at once AND per row, which sound opposed and are not: a
                // tenant reads one register and writes down every row of it in
                // one act, so it is never left unsure whether it has finished —
                // and because the rows are individually named, the store can
                // still say which single one is new or widened. A deployment may
                // then halt for that row without stopping everything else, which
                // is what decision six asks for and what one digest over the
                // whole register could not give.
                //
                // Empty means it has read none, which is a real state and a
                // different answer from having read a different one.
                Set.copyOf(Json.strings(root, "authorised")),
                // WHAT THIS ZONE PERMITS ITS MEMBERS TO DO FOR EACH OTHER, by
                // process. Permitted by declaration and never assumed: a zone
                // that lists none permits none, and a zone below another may
                // list only what the one above it does.
                exchangesOf(root),
                // A PARTNER SAYS WHOSE IT IS. The service provider hands part
                // of its work to partners, and a tenant is one because its own
                // file says so — the same rule as a zone — never because the
                // provider named it.
                Json.strOpt(root, "partnerOf"),
                // WHERE ITS OWNER KEEPS ITS CONFIGURATION, if anywhere. The
                // declaration says where to read; what is read there is the
                // owner's.
                configurationOf(root),
                // WHAT WRAPS ITS WORKING KEY, where it is not the deployment's.
                workingKeyOf(root),
                // WHAT KIND IT IS, where it says: a label, deciding nothing here.
                Json.strOpt(root, "kind"));
    }

    private static WorkingKey workingKeyOf(Object root) {
        Object wrapping = Json.objOpt(root, "workingKey");
        if (!(wrapping instanceof java.util.Map<?, ?>)) {
            return null;
        }
        Object reaching = Json.objOpt(wrapping, "auth");
        return new WorkingKey(Json.strOpt(wrapping, "key"), Json.strOpt(wrapping, "vault"),
                Json.strOpt(wrapping, "mount"),
                reaching == null ? null : Json.strOpt(reaching, "mount"),
                reaching == null ? null : Json.strOpt(reaching, "role"));
    }

    private static Configuration configurationOf(Object root) {
        Object configured = Json.objOpt(root, "configuration");
        if (!(configured instanceof java.util.Map<?, ?>)) {
            return null;
        }
        return new Configuration(Json.strOpt(configured, "git"),
                Json.strOpt(configured, "ref"), Json.strOpt(configured, "key"));
    }

    private static Set<String> exchangesOf(Object root) {
        Set<String> once = new java.util.HashSet<>();
        for (String process : Json.strings(root, "exchanges")) {
            if (!once.add(process)) {
                throw new IllegalArgumentException("exchanges names " + process + " twice");
            }
        }
        return once;
    }

    /**
     * What a step's asker collects, read where the step is declared, or null
     * where it declares no answer.
     *
     * <p>Every inconsistency is refused here, because each is a tenant that
     * comes up and then answers its askers wrongly: an audience nobody
     * declared, a window without an audience or an audience without a window,
     * a duration that is not one, a purpose that is not a code, and an
     * audience revealing a person whole on a step that says no reason for it.
     */
    /**
     * When a step starts itself, read whole here: one occasion and not two,
     * a rule the scheduler can fire, a search over a type this tenant holds,
     * a moment and a lag only where a moment is what starts it, a missed-tick
     * policy only where there are ticks, and a retention and a place always,
     * because neither has a default anybody chose.
     */
    private static Starts starts(Object startsNode, java.util.Set<String> held) {
        if (startsNode == null) {
            return null;
        }
        String every = Json.strOpt(startsNode, "every");
        String change = Json.strOpt(startsNode, "change");
        String due = Json.strOpt(startsNode, "due");
        long occasions = java.util.stream.Stream.of(every, change, due)
                .filter(java.util.Objects::nonNull).count();
        if (occasions != 1) {
            throw new IllegalArgumentException("starts on " + (occasions == 0 ? "nothing"
                    : occasions + " occasions") + " — declare exactly one of 'every', "
                    + "'change' or 'due'");
        }
        if (every != null) {
            cloud.jengu.dbo.stream.StepClock.refuse(every);
        }
        String search = change != null ? change : due;
        if (search != null) {
            String type = search.contains("?") ? search.substring(0, search.indexOf('?'))
                    : search;
            if (held != null && !held.contains(type)) {
                throw new IllegalArgumentException("starts on a change to '" + type
                        + "', and this tenant does not declare that type. It holds: "
                        + new java.util.TreeSet<>(held));
            }
        }
        String at = Json.strOpt(startsNode, "at");
        String after = Json.strOpt(startsNode, "after");
        if (due == null && (at != null || after != null)) {
            throw new IllegalArgumentException("names a moment ('at', 'after') and is not "
                    + "started by one — only a 'due' step is");
        }
        if (due != null && at == null) {
            throw new IllegalArgumentException("is due at a moment its record names, and "
                    + "does not say which element names it — declare 'at'");
        }
        String missed = Json.strOpt(startsNode, "missed");
        if (every == null && missed != null) {
            throw new IllegalArgumentException("says what a missed tick becomes, and has no "
                    + "ticks — 'missed' is only for a step started by 'every'");
        }
        if (every != null && missed == null) {
            throw new IllegalArgumentException("is started by a calendar rule and does not "
                    + "say what a tick missed while no node ran becomes — declare 'missed' "
                    + "as 'once' or 'skip'");
        }
        String keep = Json.strOpt(startsNode, "keep");
        if (keep == null) {
            throw new IllegalArgumentException("starts itself and does not say how long the "
                    + "runs it starts are kept — declare 'keep', as P30D");
        }
        String where = Json.strOpt(startsNode, "where");
        if (where == null) {
            throw new IllegalArgumentException("starts itself and does not say where — "
                    + "declare 'where' as 'origin', 'places' or 'everywhere'");
        }
        java.time.Duration kept = duration("keep", keep);
        if (kept.isNegative() || kept.isZero()) {
            throw new IllegalArgumentException("keeps the runs it starts for " + keep
                    + ", and a run kept for no time is never seen");
        }
        java.time.Duration lag = after == null ? java.time.Duration.ZERO
                : duration("after", after);
        if (lag.isNegative()) {
            throw new IllegalArgumentException("is due " + after + " after its moment, and "
                    + "a step is not started before what starts it");
        }
        return new Starts(every, change, due, at, lag,
                missed == null ? null : named(Starts.Missed.class, "missed", missed), kept,
                named(Starts.Where.class, "where", where));
    }

    private static java.time.Duration duration(String field, String value) {
        try {
            return java.time.Duration.parse(value);
        } catch (java.time.format.DateTimeParseException wrong) {
            throw new IllegalArgumentException("'" + field + "' is '" + value + "', which is "
                    + "not an ISO-8601 duration such as P30D", wrong);
        }
    }

    private static <E extends Enum<E>> E named(Class<E> kind, String field, String value) {
        for (E one : kind.getEnumConstants()) {
            if (one.name().equalsIgnoreCase(value)) {
                return one;
            }
        }
        throw new IllegalArgumentException("'" + field + "' is '" + value + "', and is one of "
                + java.util.Arrays.stream(kind.getEnumConstants())
                        .map(one -> one.name().toLowerCase(java.util.Locale.ROOT)).toList());
    }

    private static Answer answer(String code, String stepCode, Object step,
            cloud.jengu.dbo.policy.TenantPolicies policies) {
        String answers = Json.strOpt(step, "answers");
        String collect = Json.strOpt(step, "collect");
        String purpose = Json.strOpt(step, "purpose");
        if (purpose != null && !cloud.jengu.dbo.core.api.Disclosure.statable(purpose)) {
            throw new IllegalArgumentException(code + ": step '" + stepCode + "' states the "
                    + "purpose '" + purpose + "', which is not a PurposeOfUse code");
        }
        if (answers == null && collect == null) {
            return null;
        }
        if (answers == null || collect == null) {
            throw new IllegalArgumentException(code + ": step '" + stepCode + "' declares "
                    + (answers == null ? "'collect' and no 'answers' — a window onto nothing "
                            + "anybody may see" : "'answers' and no 'collect' — an audience "
                            + "with no time in which to collect")
                    + "; declare both, or neither for an asker that reads the run's answer "
                    + "alone");
        }
        cloud.jengu.dbo.policy.TenantPolicies.Audience audience = policies.audience(answers);
        if (audience == null) {
            throw new IllegalArgumentException(code + ": step '" + stepCode + "' answers '"
                    + answers + "', and this tenant declares no such audience under "
                    + "disclosure.perAudience");
        }
        java.time.Duration window;
        try {
            window = java.time.Duration.parse(collect);
        } catch (java.time.format.DateTimeParseException wrong) {
            throw new IllegalArgumentException(code + ": step '" + stepCode + "' collects for '"
                    + collect + "', which is not an ISO-8601 duration such as PT15M", wrong);
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException(code + ": step '" + stepCode + "' collects for "
                    + collect + ", and a window that is already shut is no window");
        }
        if (audience.reveals() == cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE
                && purpose == null) {
            throw new IllegalArgumentException(code + ": step '" + stepCode + "' answers '"
                    + answers + "', which reveals a person whole, and states no purpose: "
                    + "declare the PurposeOfUse it reads her for, as \"purpose\": \"TREAT\"");
        }
        return new Answer(answers, audience.types(), audience.reveals(), window, purpose);
    }

    /**
     * The Postgres database name for a tenant code: {@code tenant_<code>}
     * with hyphens folded to underscores, and — when that would exceed
     * Postgres' 63-byte identifier limit (which TRUNCATES silently, so two
     * long codes sharing a prefix would collide) — truncated with a
     * deterministic hash suffix. Pure function of the code: every
     * provisioner and every restart derives the same name.
     */
    public static String databaseName(String code) {
        return databaseName(null, code);
    }

    /**
     * The same, for a node that names its databases as its own: {@code
     * <namespace>_tenant_<code>}. Two nodes serving one tenant code on one
     * server — a face root on each, a tenant's own place on each of its sites
     * — otherwise make, open and drop one database between them.
     *
     * @param namespace the node's, or null for the names every node has always
     *                  used
     */
    public static String databaseName(String namespace, String code) {
        return named(namespace, "tenant_" + code.replace('-', '_'), code);
    }

    public String databaseName() {
        return databaseName(code);
    }

    /**
     * The Postgres database name for a step's substrate.
     *
     * <p>Derived the same way a tenant's is and prefixed differently on
     * purpose: {@code step_} beside {@code tenant_} means a person reading
     * `\l` can tell which databases hold somebody's records and which hold a
     * queue, and it makes the two namespaces unable to collide — a step named
     * after a tenant is not a tenant's database.
     *
     * <p><b>These are not tenants.</b> What is created is a database the
     * runtime owns, carrying a durable-layer bootstrap and nothing else: no
     * face, no zone, no personal-data isolation, no store schema, no
     * authority. Nothing here derives a tenant's anything from it.
     */
    public static String substrateDatabaseName(String substrate) {
        return substrateDatabaseName(null, substrate);
    }

    /** The same, for a node that names its databases as its own. */
    public static String substrateDatabaseName(String namespace, String substrate) {
        return named(namespace, "step_" + substrate.replace('-', '_').replace('.', '_'),
                substrate);
    }

    /** The most a namespace may be, so a name keeps room for what it is of. */
    public static final int MOST_IN_A_NAMESPACE = 24;

    /**
     * Whether a namespace is one a node may name its databases under: a code's
     * characters, and short enough to leave room for what each database is.
     */
    public static boolean isNamespace(String namespace) {
        return isCode(namespace) && namespace.length() <= MOST_IN_A_NAMESPACE;
    }

    /**
     * One derivation for every database a node makes, opens and drops.
     *
     * <p>Folded past 63 characters with a digest of everything that names it,
     * the namespace kept at the front: {@code \l} still says whose a database
     * is. Without a namespace, exactly the names there have always been.
     */
    private static String named(String namespace, String what, String of) {
        if (namespace != null && !isNamespace(namespace)) {
            throw new IllegalArgumentException("not a namespace a node may name its databases "
                    + "under: " + namespace);
        }
        String name = namespace == null ? what : namespace.replace('-', '_') + "_" + what;
        if (name.length() <= 63) {
            return name;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((namespace == null ? of : namespace + "/" + of)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hash.append(String.format("%02x", digest[i]));
            }
            return name.substring(0, 50) + "_" + hash;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
