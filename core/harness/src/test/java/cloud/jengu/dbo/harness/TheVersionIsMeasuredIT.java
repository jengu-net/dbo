package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.core.api.feed.FeedSelection;
import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.fhir.common.FaceDefinitions;
import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.element.DefinitionEnvelopeProbe;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.index.BoundCodes;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.fhir.validate.ElementChecks;
import cloud.jengu.dbo.fhir.validate.IndexPayloads;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-VERSION-MEASURED, walked on the harness's shared runtime.
 *
 * <p>Before a FHIR version's database answers are trusted, somebody releasing
 * the store asks whether the libraries that answer in the process and the SQL
 * that answers in the database agree with each other, and with the
 * specification the release carries, over the whole of that version. The
 * journey goes from where the definitions live, through the index read off
 * their rows, to the two checkers held against each other and against the
 * toolchain, and ends with what a checker in the process rests on, counted.
 *
 * <p><b>Why this is not walked in Rowling Land.</b> What it measures is a
 * release rather than a deployment. Every leg puts the face's own in-process
 * libraries — the definition index, the element checks, the envelope probe,
 * the carried packages — beside a test and reads a tenant's database
 * directly, and the definitions feed and the domain refusal are reached
 * without a door. A deployment serves none of those, so a story walked
 * through a deployment's doors cannot reach them; the shared runtime holds
 * the tenants the measurement needs and exposes their libraries and
 * databases.
 *
 * <p><b>Two tenants, both shared.</b> A face root, because it holds a whole
 * version's definitions as rows and every comparison over the version is a
 * question about what those rows say. A profiled tenant, because fixed and
 * pattern values live in a tenant's own profiles rather than in what HL7
 * publishes, and because selection by canonical needs profiles of its own to
 * select between. What this class writes is under canonicals and consumer
 * names of its own, so what it reads is what it wrote.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheVersionIsMeasuredIT {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /** What {@link SharedTenants.Shape#R4_FACE_ROOT} declares it operates on. */
    private static final Set<String> FACE_ROOT_DECLARED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Patient", "Observation"));

    /** A dependent of a realistic shape: the types a small clinic operates on. */
    private static final List<String> CLINIC_DECLARED = List.of(
            "Patient", "Observation", "Organization", "Practitioner");

    /** The types the face writes expressions for and the cut compiles too. */
    private static final Set<String> BOTH = new TreeSet<>(Set.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"));

    /** A profile that pins values, and one that slices, on the profiled tenant. */
    private static final String PINNED = "https://ee.ee/StructureDefinition/IndeksIkPatsient";
    private static final String SLICED = "https://ee.ee/StructureDefinition/IndeksViilutatud";
    private static final String SYSTEM = "https://ee.ee/ik";
    private static final String MARITAL =
            "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus";

    /** Two profiles to select between, on the profiled tenant. */
    private static final String KEPT = "https://ee.ee/StructureDefinition/ValitudProfiil";
    private static final String DROPPED = "https://ee.ee/StructureDefinition/ValimataProfiil";

    private static final ObjectMapper JSON = new ObjectMapper();

    static SharedTenants.Tenant faceRoot;
    static SharedTenants.Tenant profiled;

    static DefinitionStore definitions;
    static List<DefinitionRows.Parameter> compiled;
    static IndexPayloads indexFace;
    static DefinitionIndex profileIndex;
    static Set<String> declaredClosure;

    @BeforeAll
    void up() throws Exception {
        // Shared. A face root holds the version's whole definition set, which
        // is the most expensive thing this suite builds, and every question
        // here is about what the version says rather than about the tenant
        // holding it.
        faceRoot = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        profiled = SharedTenants.of(SharedTenants.Shape.R4_PROFILED);

        definitions = new DefinitionStore(faceRootSource());
        compiled = DefinitionRows.parametersFor(faceRootSource(), BOTH);
        declaredClosure = DefinitionRows.closureOf(faceRootSource(), declaredSeeds());
        DefinitionIndex contractIndex = DefinitionRows.over(faceRootSource(), declaredClosure);
        indexFace = new IndexPayloads(contractIndex, BoundCodes.over(faceRootSource(),
                contractIndex));

        // A profile of this story's own, under a canonical of its own: the
        // shape is shared and a second class writing to the same canonical
        // would be two classes editing one record.
        profiled.store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"IndeksIkPatsient","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.identifier.system","path":"Patient.identifier.system",
                    "fixedUri":"%s"},
                   {"id":"Patient.maritalStatus","path":"Patient.maritalStatus",
                    "patternCodeableConcept":{"coding":[{"system":"%s","code":"M"}]}}]}}"""
                .formatted(PINNED, SYSTEM, MARITAL));
        // And one that SLICES: an identifier under this tenant's own system,
        // required exactly once, beside whatever else a patient carries.
        profiled.store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"IndeksViilutatud","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.identifier","path":"Patient.identifier",
                    "slicing":{"discriminator":[{"type":"value","path":"system"}],
                     "rules":"open"}},
                   {"id":"Patient.identifier:ik","path":"Patient.identifier","sliceName":"ik",
                    "min":1,"max":"1","type":[{"code":"Identifier"}]},
                   {"id":"Patient.identifier:ik.system","path":"Patient.identifier.system",
                    "min":1,"max":"1","fixedUri":"%s"}]}}"""
                .formatted(SLICED, SYSTEM));
        profiled.store().shapesChanged();
        profileIndex = DefinitionRows.over(profiledSource(),
                DefinitionRows.closureOf(profiledSource(),
                        new LinkedHashSet<>(Set.of(PINNED, SLICED))));

        // Two profiles to select between, and a record that carries no
        // canonical at all.
        for (String url : List.of(KEPT, DROPPED)) {
            profiled.store().create("""
                    {"resourceType":"StructureDefinition",
                     "url":"%s","name":"%s","status":"active","kind":"resource",
                     "abstract":false,"type":"Patient",
                     "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                     "derivation":"constraint",
                     "differential":{"element":[
                       {"id":"Patient.name","path":"Patient.name","min":1,"max":"1"}]}}"""
                    .formatted(url, url.substring(url.lastIndexOf('/') + 1)));
        }
        profiled.store().create("{\"resourceType\":\"Patient\",\"gender\":\"female\"}");
    }

    // ── what a face gave a tenant is a thing of its own ──
    //
    // A face is cut once per release and handed to every tenant that comes up
    // on it, so the definitions have to be separable from what happened at
    // the tenant: their own schema, their own feed, their own cursor. The
    // cursor is what an image is cut at, and it can only be cut there if
    // records are not moving past it. Every count is over the face root's own
    // two schemas, and every drain is under a consumer name of this story's
    // own.

    @Test
    @Order(1)
    @Timeout(600)
    @DisplayName("a profile lands in the definitions schema and a patient does not")
    @Proving(DboPromises.VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN)
    void aProfileLandsInTheDefinitionsSchema() throws Exception {
        writeAProfileAndAPatient();

        // A root holds the whole of what its version publishes, and the one
        // profile written above. All of it is in the one schema.
        assertTrue(rows(Domains.tables(Domains.DEFINITIONS) + "_data",
                        "type = 'StructureDefinition' AND NOT deleted") > 100,
                "the version's own profiles are not in the schema a face is cut from");
        assertEquals(1, rows(Domains.tables(Domains.DEFINITIONS) + "_data",
                        "type = 'StructureDefinition' AND NOT deleted"
                        + " AND envelope::text LIKE '%urn:test:profile:one%'"),
                "the profile this tenant authored is not there with them");
        assertEquals(0, rows("state.r4_data", "type = 'StructureDefinition'"),
                "a profile is still among the tenant's records, so a dump of the definitions "
                        + "schema would be short of what the face gave");
        // At least one, not exactly one: the root is shared, and what this
        // asserts is that a patient stays among the records rather than how
        // many patients this tenant happens to hold.
        assertTrue(rows("state.r4_data", "type = 'Patient' AND NOT deleted") >= 1,
                "the patient did not stay among the records");
        assertEquals(0, rows(Domains.tables(Domains.DEFINITIONS) + "_data", "type = 'Patient'"),
                "a patient is in the schema cut into an image and handed to every other "
                        + "tenant on this face");
    }

    @Test
    @Order(2)
    @Timeout(600)
    @DisplayName("each feed carries its own, and neither carries the other's")
    @Proving(DboPromises.FEED_DEFINITIONS_MOVE_ON_A_FEED_OF_THEIR_OWN)
    void eachFeedCarriesItsOwn() throws Exception {
        writeAProfileAndAPatient();
        List<String> onDefinitions = typesOn(drain(faceRoot.definitionsFeed(),
                "definitions-feed-test.definitions"));
        List<String> onRecords = typesOn(drain(faceRoot.feed(),
                "definitions-feed-test.records"));

        assertTrue(onDefinitions.contains("StructureDefinition"),
                "the definitions feed did not carry the profile: " + onDefinitions);
        assertFalse(onDefinitions.contains("Patient"),
                "a subscriber taking a face reads the root's patients on the way to the next "
                        + "profile: " + onDefinitions);
        assertTrue(onRecords.contains("Patient"),
                "the record feed did not carry the patient: " + onRecords);
        assertFalse(onRecords.contains("StructureDefinition"),
                "a reader of what happened at this tenant is handed its face as well: "
                        + onRecords);
    }

    @Test
    @Order(3)
    @DisplayName("a type registered into the wrong domain is refused, naming it and why")
    @Proving(DboPromises.TEN_A_TYPE_DECLARES_ITS_DOMAIN)
    void aMisplacedTypeIsRefused() {
        IllegalArgumentException definitionAmongRecords = assertThrows(
                IllegalArgumentException.class,
                () -> FaceDefinitions.refuseIfMisplaced(
                        List.of(registration("StructureDefinition", "r4")), "r4"));
        assertTrue(definitionAmongRecords.getMessage().contains("StructureDefinition"),
                definitionAmongRecords.getMessage());
        assertTrue(definitionAmongRecords.getMessage().contains("image"),
                "the refusal says what would go wrong rather than only that something did: "
                        + definitionAmongRecords.getMessage());

        IllegalArgumentException recordAmongDefinitions = assertThrows(
                IllegalArgumentException.class,
                () -> FaceDefinitions.refuseIfMisplaced(
                        List.of(registration("Patient", Domains.DEFINITIONS)), "r4"));
        assertTrue(recordAmongDefinitions.getMessage().contains("Patient"),
                recordAmongDefinitions.getMessage());

        // And the arrangement a tenant actually has is accepted.
        FaceDefinitions.refuseIfMisplaced(
                List.of(registration("StructureDefinition", Domains.DEFINITIONS),
                        registration("Patient", "r4")), "r4");
    }

    // ── and what a dependent needs from it is named, by the one that holds it ──
    //
    // What a tenant needs from a face is the closure of the types it
    // declared, so the dependency is derived rather than written, and it is
    // computed at the upstream because a tenant cannot compute the closure of
    // definitions it does not hold. A filter is a set of names computed once,
    // never a predicate that travels: the upstream compares a type to a list
    // of type names and a canonical to a list of canonicals, and executes
    // nothing on anybody's behalf. The manifest closes over grains — a code
    // system together with the value sets that draw on it — because half a
    // grain is a stream that breaks on arrival. What is measured here is the
    // derivation and the selection; the sync path streams by type.

    @Test
    @Order(4)
    @DisplayName("the manifest a dependent needs is computed from the types it declared, and is "
            + "a fraction of what the face holds")
    void theManifestIsDerivedFromTheDeclaration() {
        DefinitionRows.Manifest manifest = DefinitionRows.manifestFor(faceRootSource(),
                clinicSeeds(), CLINIC_DECLARED);

        int structuresHeld = scalar(
                "SELECT count(DISTINCT canonical) FROM definitions.definition_element");
        int valueSetsHeld = scalar("SELECT count(*) FROM definitions.term_valueset");
        int systemsHeld = scalar("SELECT count(*) FROM definitions.term_system");

        System.out.printf("%n=== what %d declared types need from %s ===%n"
                + "structures   %5d of %5d held%n"
                + "value sets   %5d of %5d held%n"
                + "code systems %5d of %5d held%n"
                + "the manifest is %d names against %d the face holds%n",
                CLINIC_DECLARED.size(), faceRoot.code(),
                manifest.structures().size(), structuresHeld,
                manifest.valueSets().size(), valueSetsHeld,
                manifest.codeSystems().size(), systemsHeld,
                manifest.size(), structuresHeld + valueSetsHeld + systemsHeld);

        // A derivation that named everything would be no derivation.
        assertTrue(manifest.structures().size() * 4 < structuresHeld,
                "the derived manifest narrowed nothing: " + manifest.structures().size()
                        + " of " + structuresHeld);
        // And one that named nothing would be a dependent that gets no face.
        assertTrue(manifest.structures().contains(PREFIX + "Patient"),
                "a declared type is not in its own manifest");
        assertTrue(!manifest.valueSets().isEmpty(),
                "a manifest with no terminology cannot answer a required binding");
    }

    @Test
    @Order(5)
    @DisplayName("the manifest names whole grains: every value set in it brings the systems it "
            + "is built from, and every system brings the value sets that draw on it")
    void theManifestClosesOverGrains() {
        DefinitionRows.Manifest manifest = DefinitionRows.manifestFor(faceRootSource(),
                clinicSeeds(), CLINIC_DECLARED);

        // Half a grain is what this must not be able to name. Asked of the
        // rows rather than of the code that built it: every system any named
        // value set is built from is named, and every value set drawing on any
        // named system is named.
        Set<String> systemsBehind = new TreeSet<>(query("""
                SELECT DISTINCT part ->> 'system'
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> 'includes', '[]'::jsonb)) AS part
                 WHERE vs.url = ANY(?) AND part ->> 'system' IS NOT NULL""",
                manifest.valueSets()));
        systemsBehind.removeAll(manifest.codeSystems());
        assertEquals(Set.of(), systemsBehind,
                "a value set in the manifest is built from a system the manifest does not name, "
                        + "which is half a grain and a stream that breaks on arrival");

        Set<String> setsDrawing = new TreeSet<>(query("""
                SELECT DISTINCT vs.url
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> 'includes', '[]'::jsonb)) AS part
                 WHERE part ->> 'system' = ANY(?)""", manifest.codeSystems()));
        setsDrawing.removeAll(manifest.valueSets());
        assertEquals(Set.of(), setsDrawing,
                "a system in the manifest is drawn on by a value set the manifest does not name");
    }

    @Test
    @Order(6)
    @DisplayName("a consumer that names canonicals is sent those and not the others, and the "
            + "records that carry no canonical are unaffected")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void theUpstreamSendsWhatWasNamed() {
        // Definitions move on a feed of their own, so the canonicals are
        // asked of that one and the records of the other.
        List<String> allUrls = canonicalsIn(drain(profiled.definitionsFeed(),
                "selects-everything", FeedSelection.EVERYTHING));
        assertTrue(allUrls.contains(KEPT) && allUrls.contains(DROPPED),
                "the unnarrowed feed did not carry both profiles, so there is nothing to "
                        + "narrow: " + allUrls);

        List<String> namedUrls = canonicalsIn(drain(profiled.definitionsFeed(),
                "selects-by-name", new FeedSelection(Set.of("StructureDefinition"), Set.of(KEPT))));
        assertTrue(namedUrls.contains(KEPT), "the named canonical was not sent: " + namedUrls);
        assertTrue(!namedUrls.contains(DROPPED),
                "a canonical nobody named was sent anyway, so the upstream is not selecting: "
                        + namedUrls);

        // A RECORD CARRIES NO CANONICAL AND MUST STILL ARRIVE. Asked with a
        // manifest that names only definitions: a derived dependency that
        // stopped delivering a tenant's patients the moment it named a
        // profile would be worse than no narrowing at all.
        List<FeedItem> records = drain(profiled.feed(), "selects-records",
                new FeedSelection(Set.of("Patient"), Set.of(KEPT)));
        assertTrue(records.stream().anyMatch(item -> "Patient".equals(item.typeName())),
                "a record carrying no canonical was withheld by a list of definition urls");

        // And a type nobody named is not sent: the upstream leaves it out
        // rather than the dependent discarding it after the fact.
        List<FeedItem> onlyPatients = drain(profiled.feed(), "selects-one-type",
                FeedSelection.ofTypes(Set.of("Patient")));
        assertEquals(List.of(), onlyPatients.stream()
                        .map(FeedItem::typeName).filter(one -> !"Patient".equals(one))
                        .distinct().toList(),
                "a type nobody named was sent");
        assertTrue(!onlyPatients.isEmpty(), "naming one type sent nothing at all");
    }

    // ── the index is read from the rows, and the rows say what the packages say ──
    //
    // The index is built from definitions.definition_element because the rows
    // are what arrived — a tenant's own profiles, what a face image carried,
    // narrowed to what the tenant declared — and a package can supply none of
    // that. What has to be shown is that the answer did not move with the
    // source, in both directions, because an index that held nothing would
    // agree with anything. One set of typed rules is then fed from two front
    // ends — expressions written out in the face for definition types, and
    // the parameters the cut compiled for everything else — and the two are
    // held to the same envelope, because a front end that quietly indexed
    // less would lose keys, and a search by a lost key finds nothing while
    // looking exactly like an answer.

    @Test
    @Order(7)
    @DisplayName("the closure of what a tenant declared is walked over the rows, and is a "
            + "fraction of what the version holds")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void theClosureIsWalkedOverTheRows() {
        Set<String> closure = DefinitionRows.closureOf(faceRootSource(), declaredSeeds());

        // Every seed reaches itself, and the kernel a resource is made of is
        // reached by all of them. A closure that had only the seeds in it
        // would be a walk that did not walk.
        for (String seed : declaredSeeds()) {
            assertTrue(closure.contains(seed), "a declared type is not in its own closure: " + seed);
        }
        assertTrue(closure.contains(PREFIX + "HumanName"),
                "Patient.name is a HumanName and the closure did not reach it");
        assertTrue(closure.contains(PREFIX + "CodeableConcept"),
                "the closure did not reach the kernel every resource type is made of");

        // Reference is followed for the Reference type itself and not through
        // it: Observation.subject may point at a Device, and a tenant that
        // does not declare Device does not hold its definitions.
        assertTrue(closure.contains(PREFIX + "Reference"),
                "the closure did not reach Reference, which every resource carries");
        assertTrue(!closure.contains(PREFIX + "Device"),
                "the closure followed a reference target into a type nobody declared");

        // The narrowing, which is the whole reason for a closure. A face root
        // holds the version, so what it could hold and what a tenant needs
        // are both countable here and the ratio is the claim.
        int held = canonicalsWithRows();
        System.out.printf("%n=== the closure of %d declared types, over the rows ===%n"
                + "reached %d structures of the %d this tenant holds rows for (%.1f%%)%n",
                declaredSeeds().size(), closure.size(), held, 100.0 * closure.size() / held);
        assertTrue(closure.size() * 5 < held,
                "the closure narrowed nothing: " + closure.size() + " of " + held);
    }

    @Test
    @Order(8)
    @DisplayName("element for element, an index built from the rows says what one built from "
            + "the packages says")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void theProjectionSaysWhatItWasProjectedFrom() throws Exception {
        Set<String> closure = DefinitionRows.closureOf(faceRootSource(), declaredSeeds());
        DefinitionIndex rows = DefinitionRows.over(faceRootSource(), closure);
        Map<String, List<Element>> packages = fromThePackages(closure);

        List<String> divergences = new ArrayList<>();
        int compared = 0;
        int elements = 0;
        for (String canonical : closure) {
            List<Element> published = packages.get(canonical);
            if (published == null || !rows.holds(canonical)) {
                continue;
            }
            compared++;
            List<Integer> held = rows.elementsOf(canonical);
            elements += held.size();
            if (held.size() != published.size()) {
                divergences.add(canonical + ": " + held.size() + " elements in the rows, "
                        + published.size() + " in the package");
                continue;
            }
            for (int i = 0; i < held.size(); i++) {
                Element theirs = published.get(i);
                Element ours = new Element(rows.pathOf(held.get(i)), rows.minOf(held.get(i)),
                        rows.maxOf(held.get(i)), rows.typesOf(held.get(i)));
                if (!ours.equals(theirs)) {
                    divergences.add(canonical + ": " + ours + " against " + theirs);
                }
            }
        }

        // Every string the index can be asked for, before interning.
        int occurrences = 0;
        for (int element = 0; element < rows.elements(); element++) {
            occurrences += 1 + rows.typesOf(element).size()
                    + 3 * rows.invariantsOf(element).size()
                    + (rows.bindingValueSetOf(element) == null ? 0 : 1);
        }

        System.out.printf("%n=== the rows against the packages, over %s's closure ===%n"
                + "structures in both %d of %d in the closure%n"
                + "elements compared    %d%n"
                + "the index holds      %d elements, %d structures, %d distinct words "
                + "for %d strings%n"
                + "divergences          %d%n",
                faceRoot.code(), compared, closure.size(), elements,
                rows.elements(), rows.structures(), rows.words(), occurrences,
                divergences.size());
        divergences.stream().limit(10).forEach(one -> System.out.println("  " + one));

        // An index that held nothing would agree with anything, and an
        // overlap of four structures would prove nothing about the rest. What
        // is asserted is not a threshold somebody chose: every structure the
        // closure reaches is held on both sides, so the comparison covers the
        // closure rather than whatever part of it happened to be in both.
        assertEquals(closure.size(), compared,
                "a structure in the closure was missing from one of the two sides");
        assertTrue(elements > 500, "too few elements compared to mean anything: " + elements);
        // The dictionary is the form, so it is counted rather than trusted:
        // every string the index can be asked for against the number it
        // actually holds. A path is nearly unique and pays nothing; ele-1 is
        // inherited onto almost every element there is and pays for the rest.
        assertTrue(rows.words() * 2 < occurrences,
                "the dictionary is not sharing anything: " + rows.words() + " words for "
                        + occurrences + " strings the index answers with");
        assertEquals(List.of(), divergences,
                "the projection says something its source does not");
    }

    @Test
    @Order(9)
    @DisplayName("over every definition the face carries, the compiled parameters index what "
            + "the written-out expressions index")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void bothSetsOfParametersIndexTheSame() {
        assertTrue(compiled.size() > 40,
                "too few compiled parameters to mean anything: " + compiled.size());

        List<String> divergences = new ArrayList<>();
        List<String> declined = new ArrayList<>();
        int compared = 0;
        int keys = 0;
        for (FaceRootPackages.Definition document : FaceRootPackages.definitionsFor("r4", BOTH)) {
            if (compared >= 300) {
                break;
            }
            compared++;
            Envelope written = DefinitionEnvelopeProbe.fromTheFace(
                    document.typeName(), document.document());
            Envelope fromRows = DefinitionEnvelopeProbe.fromTheRows(
                    compiled, document.typeName(), document.document(), declined);
            keys += written.paths().size();
            String difference = differing(written, fromRows);
            if (difference != null) {
                divergences.add(document.typeName() + " " + document.url() + ": " + difference);
            }
        }

        System.out.printf("%n=== one envelope from either set of parameters ===%n"
                + "documents %d, keys built by the written-out expressions %d%n"
                + "parameters the compiled reader declined: %s%n"
                + "divergences %d%n", compared, keys, declined, divergences.size());
        divergences.stream().limit(8).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 100, "too few documents compared: " + compared);
        assertTrue(keys > 500, "almost no keys were built, so agreeing means nothing: " + keys);
        assertEquals(List.of(), divergences,
                "the two sets of parameters index a document differently, which is a key "
                        + "present on one side and a search that finds nothing on the other");
    }

    // ── the database's answer, measured against the toolchain's over the version ──
    //
    // The advisory tally on a tenant's own writes says whether the two
    // checkers agree about that tenant's traffic. This says whether they
    // agree about the specification: every conformance resource the version
    // ships is a real document of a real type, deep, sliced, bound and
    // referenced, and it is the corpus this store already carries. The
    // instance examples ship in a package of their own that the store does
    // not carry. The number recorded is what the two disagree about, per
    // resource type, in config/divergence-baseline.txt: it may fall and may
    // not rise, because the whole case for the database answering at all is
    // that it answers the same. A value that is not the kind of thing its
    // element declares is one of the things the database has to find for
    // that to hold.

    @Test
    @Order(10)
    @DisplayName("over everything the version publishes, the two disagree about no more than "
            + "what was recorded")
    @Proving(DboPromises.VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION)
    void theyDisagreeAboutNoMoreThanWasRecorded() throws Exception {
        Map<String, int[]> perType = new TreeMap<>();
        // Written to a file rather than printed. A test's standard output goes
        // nowhere by default and the build forwards none of it, so a naming
        // that printed would be a naming nobody reads.
        StringBuilder named = new StringBuilder();
        int compared = 0;
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            String canonical = definitions.theTypeItself(ofType.getKey()).orElse(null);
            if (canonical == null) {
                continue; // the root holds no definition of this type to judge by
            }
            int[] tally = perType.computeIfAbsent(ofType.getKey(), ignored -> new int[3]);
            for (FaceRootPackages.Definition document : ofType.getValue()) {
                boolean toolchainFound = theToolchainRefuses(document.document());
                boolean databaseFound = definitions.issuesUnder(document.document(), canonical)
                        .orElse(0) > 0;
                tally[0]++;
                compared++;
                if (toolchainFound != databaseFound) {
                    tally[toolchainFound ? 1 : 2]++;
                    // What the tally cannot say: WHICH finding. A count of
                    // documents orders the work and never names it, so the
                    // findings are printable on request — they are read one
                    // by one and the reading is what the baseline's own preamble
                    // is made of.
                    if (Boolean.getBoolean("dbo.divergence.name")) {
                        named.append(ofType.getKey()).append("  ")
                                .append(toolchainFound ? "onlyTheToolchain" : "onlyTheDatabase")
                                .append(System.lineSeparator())
                                .append(saidBy(document.document(), toolchainFound, canonical))
                                .append(System.lineSeparator())
                                .append(System.lineSeparator());
                    }
                }
            }
        }
        assertTrue(compared > 200, "only " + compared + " documents were compared");
        if (Boolean.getBoolean("dbo.divergence.name")) {
            Path where = Path.of("build", "divergence-findings.txt");
            Files.createDirectories(where.getParent());
            Files.writeString(where, named.toString());
            System.out.println("divergence findings written: " + where.toAbsolutePath());
        }

        String observed = asLines(perType);
        Path baseline = BASELINE.toAbsolutePath().normalize();
        if (!Files.exists(baseline) || Boolean.getBoolean("dbo.divergence.record")) {
            Files.writeString(baseline, PREAMBLE + observed);
            System.out.println("divergence baseline recorded: " + baseline);
            return;
        }
        assertNoWorseThan(Files.readString(baseline), observed);
    }

    @Test
    @Order(11)
    @DisplayName("a date that is not a date is found, and a well-formed one is not")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aDateIsADate() {
        assertTrue(issues("{\"resourceType\":\"Patient\",\"birthDate\":\"not-a-date\"}") > 0,
                "a birthDate of 'not-a-date' was accepted, which is the divergence this "
                        + "exists to close");
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"birthDate\":\"1980-04-01\"}"),
                "a well-formed date was refused, which is worse than the gap it replaced");
        // The specification allows a year and a year-month, so neither is wrong.
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"birthDate\":\"1980\"}"));
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"birthDate\":\"1980-04\"}"));
    }

    @Test
    @Order(12)
    @DisplayName("a string where a boolean belongs is found, and a choice element keeps its "
            + "freedom")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aBooleanIsABoolean() {
        assertTrue(issues("{\"resourceType\":\"Patient\",\"deceasedBoolean\":\"yes\"}") > 0,
                "'yes' is not a boolean and is not a dateTime either, so nothing this "
                        + "element declares admits it");
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"deceasedBoolean\":true}"));
        // The other arm of the same choice: a dateTime is equally correct here,
        // and a check that judged against boolean alone would refuse it.
        assertEquals(0, issues(
                "{\"resourceType\":\"Patient\",\"deceasedDateTime\":\"2020-01-01T00:00:00Z\"}"));
    }

    @Test
    @Order(13)
    @DisplayName("what was already correct stays correct")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void nothingWellFormedBecomesAFinding() {
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Tamm\"}]}"));
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"active\":true,"
                + "\"gender\":\"female\",\"birthDate\":\"1980-04-01\","
                + "\"telecom\":[{\"system\":\"phone\",\"value\":\"+372\"}]}"));
        // A complex type is not a primitive this understands, and a check that
        // did not understand it must not refuse it.
        assertEquals(0, issues("{\"resourceType\":\"Patient\","
                + "\"managingOrganization\":{\"reference\":\"Organization/x\"}}"));
    }

    @Test
    @Order(14)
    @DisplayName("the whole corpus a face carries is still judged as it was")
    @Proving(DboPromises.VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION)
    void theCarriedCorpusIsUnmoved() {
        int looked = 0;
        int refused = 0;
        Map<String, String> canonicalOf = new java.util.HashMap<>();
        for (FaceRootPackages.Definition document : FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "SearchParameter", "ValueSet",
                        "CodeSystem"))) {
            String canonical = canonicalOf.computeIfAbsent(document.typeName(),
                    type -> definitions.theTypeItself(type).orElse(null));
            if (canonical == null) {
                continue;
            }
            if (looked++ >= 120) {
                break;
            }
            if (definitions.issuesUnder(document.document(), canonical).orElse(0) > 0) {
                refused++;
            }
        }
        assertTrue(looked > 50, "only " + looked + " documents were looked at");
        // The specification's own documents are well formed. A primitive check
        // that refused them would be refusing the corpus this store is built
        // from, which is how a rule written slightly wrong announces itself.
        assertTrue(refused * 10 < looked,
                refused + " of " + looked + " carried documents are now refused, so the new "
                        + "check is refusing the specification's own resources");
    }

    // ── a third answerer, reading the index over the same rows ──
    //
    // The index checker answers in the process from the rows the database
    // answers from, so the two are held to the same elements: first over the
    // corpus, where agreement is mostly agreement about silence, then over
    // documents that are actually wrong, where something has to be said.
    // Fixed and pattern values are almost absent from what HL7 publishes and
    // live in a tenant's own profiles, so that half is asked of the profiled
    // tenant, over profiles the database's own side is proven against in the
    // same form — two answerers over one fixture.

    @Test
    @Order(15)
    @DisplayName("over everything the version publishes, neither the index nor the database "
            + "faults anything, and the walk went far enough for that to mean something")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theThirdAnswererNamesWhatTheDatabaseNames() {
        List<String> divergences = new ArrayList<>();
        int compared = 0;
        int descents = 0;
        int deepest = 0;
        int spoken = 0;
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            String canonical = definitions.theTypeItself(ofType.getKey()).orElse(null);
            if (canonical == null || !versionIndex().holds(canonical)) {
                continue;
            }
            for (FaceRootPackages.Definition document : ofType.getValue()) {
                ElementChecks.Checked checked =
                        ElementChecks.over(versionIndex(), canonical, document.document());
                Set<String> ours = new TreeSet<>();
                for (Finding one : checked.findings()) {
                    // Cardinality alone: the walk answers five kinds of thing,
                    // and this half is compared against dbo.cardinality.
                    if ("cardinality".equals(one.key())) {
                        ours.add(withoutIndices(one.path()));
                    }
                }
                Set<String> theirs = cardinalityPaths(document.document(), canonical);
                compared++;
                descents += checked.descents();
                deepest = Math.max(deepest, checked.deepest());
                if (!ours.isEmpty() || !theirs.isEmpty()) {
                    spoken++;
                }
                if (!ours.equals(theirs)) {
                    divergences.add(ofType.getKey() + " " + document.url()
                            + ": the index says " + ours + ", the database says " + theirs);
                }
            }
        }

        System.out.printf("%n=== the index checker against dbo.cardinality, over r4 ===%n"
                + "documents compared %d, of which either answerer spoke about %d%n"
                + "the walk descended into %d nodes, deepest path %d segments%n"
                + "divergences %d%n", compared, spoken, descents, deepest, divergences.size());
        divergences.stream().limit(10).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 200, "only " + compared + " documents were compared");
        // WHAT THIS HALF IS, said rather than implied: both answerers are
        // silent about every one of these documents, so the agreement is an
        // agreement about silence. That is worth asserting — it is the claim
        // that neither faults the specification's own resources — and it is
        // not the claim that they say the same thing when something is wrong.
        // The legs after this are where that is asked.
        assertEquals(0, spoken,
                "an answerer faulted the specification's own conformance resources, which is "
                        + "either a real defect in these documents or a false positive");
        // And a clean corpus is also what a checker that never descended
        // reports, so the walk has to have gone somewhere before its silence
        // means anything at all.
        assertTrue(descents > 20_000,
                "the walk barely descended, so agreement means nothing: " + descents);
        assertTrue(deepest >= 4, "the walk never went deep: " + deepest);
        assertEquals(List.of(), divergences,
                "the two answerers over the same rows do not name the same elements");
    }

    @Test
    @Order(16)
    @DisplayName("over everything the version publishes, the rules the index runs and the rules "
            + "the database runs fault the same documents")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theRulesAgreeOverTheVersion() {
        // An invariant is compiled when the definition arrives; both sides run
        // the same compiled text, one in Postgres and one here. Two thirds of
        // them are a grammar this reader implements and the rest it declines
        // — so what is compared is the rules BOTH ran, and how many were
        // declined is reported rather than hidden, because a reader that
        // declined everything would agree perfectly.
        List<String> divergences = new ArrayList<>();
        int compared = 0;
        int spoken = 0;
        int foundHere = 0;
        int foundThere = 0;
        Set<String> agreedOn = new TreeSet<>();
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            String canonical = definitions.theTypeItself(ofType.getKey()).orElse(null);
            if (canonical == null || !versionIndex().holds(canonical)) {
                continue;
            }
            for (FaceRootPackages.Definition document : ofType.getValue()) {
                Set<String> ours = new TreeSet<>();
                for (Finding one : ElementChecks.over(versionIndex(), canonical,
                        document.document()).findings()) {
                    if (RULE_KEYS.matcher(one.key() == null ? "" : one.key()).matches()) {
                        ours.add(one.key());
                    }
                }
                Set<String> theirs = ruleKeys(document.document(), canonical);
                compared++;
                foundHere += ours.size();
                foundThere += theirs.size();
                agreedOn.addAll(ours);
                if (!ours.isEmpty() || !theirs.isEmpty()) {
                    spoken++;
                }
                // The reader answers a subset, so what it reports must be a
                // subset of what the database reports. A rule it faults that
                // the database does not is the failure that matters: the two
                // ran the same compiled text and disagreed.
                Set<String> onlyOurs = new TreeSet<>(ours);
                onlyOurs.removeAll(theirs);
                if (!onlyOurs.isEmpty()) {
                    divergences.add(ofType.getKey() + " " + document.url()
                            + ": the index faults " + onlyOurs + " and the database does not");
                }
            }
        }
        // Every one of these documents breaks a rule, and the reason is the
        // fixture rather than the corpus: a definition is read with its
        // narrative removed, so dom-6 fails on all of them. That is what makes
        // this comparison worth running on the corpus at all — the cardinality
        // half is an agreement about silence, and this one is not.
        System.out.printf("%n=== the rules, index against database, over r4 ===%n"
                + "documents compared %d, either answerer spoke about %d%n"
                + "rule findings: %d from the index, %d from the database, over %d keys%n"
                + "divergences where the index faults what the database does not: %d%n",
                compared, spoken, foundHere, foundThere, agreedOn.size(),
                divergences.size());
        System.out.println("  the index reported: " + agreedOn);
        divergences.stream().limit(10).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 200, "only " + compared + " documents were compared");
        // A reader that declined every rule would be a perfect subset of the
        // database and prove nothing, so what it DID run is asserted too.
        assertTrue(foundHere > 100,
                "the index ran the rules and faulted almost nothing, so agreeing with the "
                        + "database about a subset means nothing: " + foundHere);
        assertEquals(List.of(), divergences,
                "the two ran the same compiled rule and disagreed");
    }

    @Test
    @Order(17)
    @DisplayName("a required binding is decided in the process, against a few hundred codes, "
            + "and the database says the same")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aRequiredBindingIsDecidedFromTheCodesHeld() {
        String patient = "http://hl7.org/fhir/StructureDefinition/Patient";
        System.out.printf("%n=== the codes behind %s's required bindings ===%n"
                + "%d value sets answerable, %d codes held, %d declined as too large%n",
                faceRoot.code(), codes().valueSets(), codes().codes(), codes().declined().size());

        // Patient.gender is bound to administrative-gender at required
        // strength, which is four codes.
        bothBind(patient, "{\"resourceType\":\"Patient\",\"gender\":\"female\"}", Set.of());
        bothBind(patient, "{\"resourceType\":\"Patient\",\"gender\":\"kass\"}",
                Set.of("Patient.gender"));

        // A CodeableConcept is satisfied by ANY of its codings, so one good
        // coding beside one bad one is not a refusal.
        bothBind(patient,
                "{\"resourceType\":\"Patient\",\"maritalStatus\":{\"coding\":["
                        + "{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus\","
                        + "\"code\":\"M\"}]}}",
                Set.of());

        // And a code from a system the value set is not built from: knowable
        // without holding anything, and both know it.
        bothBind(patient,
                "{\"resourceType\":\"Patient\",\"maritalStatus\":{\"coding\":["
                        + "{\"system\":\"https://ee.ee/oma\",\"code\":\"X\"}]}}",
                Set.of());

        assertTrue(codes().codes() > 100,
                "too few codes held for this to have decided anything: " + codes().codes());
    }

    @Test
    @Order(18)
    @DisplayName("and on documents that are actually wrong, at every depth, they name the same "
            + "elements")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theyAgreeAboutWhatIsWrong() {
        // The corpus is correct, so agreeing about it is half a proof: two
        // answerers that both say nothing agree perfectly. These are the
        // documents where something has to be said.
        String patient = "http://hl7.org/fhir/StructureDefinition/Patient";
        String observation = "http://hl7.org/fhir/StructureDefinition/Observation";

        // A required element absent. Observation.status and Observation.code
        // are both 1..1.
        bothSay(observation, "{\"resourceType\":\"Observation\"}",
                Set.of("Observation.status", "Observation.code"));

        // An element allowed once, sent twice — the case the toolchain drops
        // in silence and both of these report.
        bothSay(patient, "{\"resourceType\":\"Patient\",\"gender\":[\"female\",\"male\"]}",
                Set.of("Patient.gender"));

        // Unbounded, so many is correct and neither may speak.
        bothSay(patient,
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"a\"},"
                        + "{\"family\":\"b\"}]}",
                Set.of());

        // DEPTH, inside a backbone: one contact holding two names is wrong.
        bothSay(patient,
                "{\"resourceType\":\"Patient\",\"contact\":[{\"name\":[{\"family\":\"a\"},"
                        + "{\"family\":\"b\"}]}]}",
                Set.of("Patient.contact.name"));

        // And per parent: two contacts holding one name each is correct. An
        // answerer counting across the document gets this one wrong.
        bothSay(patient,
                "{\"resourceType\":\"Patient\",\"contact\":[{\"name\":{\"family\":\"a\"}},"
                        + "{\"name\":{\"family\":\"b\"}}]}",
                Set.of());

        // INSIDE A DATATYPE THEY DIVERGE, and the index is the one that
        // reaches further. HumanName.family is 0..1 and is stated in
        // HumanName's own structure; Patient's snapshot names Patient.name as
        // a HumanName and stops. The database walks one profile's rows, so it
        // has nothing to say here and says nothing — which is the promise it
        // makes rather than a defect. The index holds the closure, so it
        // enters HumanName and speaks.
        //
        // This is the whole reason a third answerer is worth having and not
        // only cheaper: it answers where one profile's rows stop.
        String insideAType =
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":[\"a\",\"b\"]}]}";
        assertEquals(Set.of("Patient.name.family"), pathsFromTheIndex(patient, insideAType),
                "the index checker did not enter the datatype's own structure");
        assertEquals(Set.of(), cardinalityPaths(insideAType.getBytes(StandardCharsets.UTF_8),
                        patient),
                "the database spoke inside a datatype no profile constrains, which is a change "
                        + "in how far the rows reach and not a change to this comparison");
    }

    @Test
    @Order(19)
    @DisplayName("the index carries what a profile pins, which a version's own definitions "
            + "almost never state")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theIndexCarriesWhatTheProfilePinned() {
        assertTrue(profileIndex.holds(PINNED), "the profile's own rows are not in the index");

        String fixed = null;
        String pattern = null;
        for (int element : profileIndex.elementsOf(PINNED)) {
            if ("Patient.identifier.system".equals(profileIndex.pathOf(element))) {
                fixed = profileIndex.fixedOf(element);
            }
            if ("Patient.maritalStatus".equals(profileIndex.pathOf(element))) {
                pattern = profileIndex.patternOf(element);
            }
        }
        assertTrue(fixed != null && fixed.contains(SYSTEM),
                "the fixed value did not reach the index: " + fixed);
        assertTrue(pattern != null && pattern.contains(MARITAL),
                "the pattern did not reach the index: " + pattern);

        // And the base definitions it was measured over state none, which is
        // why this could not be proven over what HL7 publishes.
        int pinnedInTheBase = 0;
        for (String canonical : profileIndex.held()) {
            if (canonical.equals(PINNED)) {
                continue;
            }
            for (int element : profileIndex.elementsOf(canonical)) {
                if (profileIndex.fixedOf(element) != null
                        || profileIndex.patternOf(element) != null) {
                    pinnedInTheBase++;
                }
            }
        }
        System.out.printf("%n=== what is pinned, over %s's closure of one profile ===%n"
                + "the profile pins 2; the %d base structures under it pin %d%n",
                profiled.code(), profileIndex.structures() - 1, pinnedInTheBase);
    }

    @Test
    @Order(20)
    @DisplayName("on what an element must equal and must contain, the index and the database "
            + "name the same elements")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void bothAnswerWhatIsPinned() {
        // Exactly what the profile pins, carrying more beside it. A pattern is
        // containment, so the text beside the coding is allowed, and neither
        // answerer may speak.
        bothSayPinned("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://ee.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":"%s","code":"M"}],"text":"Abielus"}}"""
                .formatted(MARITAL), Set.of());

        // The wrong system, which is equality and is refused.
        bothSayPinned("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://vale.ee/ik","value":"1"}]}""",
                Set.of("Patient.identifier.system"));

        // A marital status the profile does not state.
        bothSayPinned("""
                {"resourceType":"Patient",
                 "maritalStatus":{"coding":[{"system":"%s","code":"U"}]}}"""
                .formatted(MARITAL), Set.of("Patient.maritalStatus"));

        // Both at once, in one document, because a walk that stopped at the
        // first finding would pass the two above and fail nobody.
        bothSayPinned("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://vale.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":"%s","code":"U"}]}}"""
                .formatted(MARITAL),
                Set.of("Patient.identifier.system", "Patient.maritalStatus"));

        // AND PER OCCURRENCE. Two identifiers, one right and one wrong: the
        // pinned value is checked where it occurs, not once for the element.
        bothSayPinned("""
                {"resourceType":"Patient","identifier":[
                   {"system":"https://ee.ee/ik","value":"1"},
                   {"system":"https://vale.ee/ik","value":"2"}]}""",
                Set.of("Patient.identifier.system"));
    }

    @Test
    @Order(21)
    @DisplayName("a slice is counted as the members it claims, not as every member of the "
            + "element it slices")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aSliceIsCountedAsWhatItClaims() {
        // The slice requires exactly one identifier under this tenant's own
        // system. An identifier under a different one does not satisfy it,
        // and an answerer that counted every identifier would think it did.
        bothCount("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://vale.ee/ik","value":"1"}]}""",
                Set.of("Patient.identifier"));

        // The other direction, which is the one that refuses a correct
        // document: two identifiers neither of which the slice claims. An
        // answerer counting every member sees two where the slice allows one.
        bothCount("""
                {"resourceType":"Patient","identifier":[
                   {"system":"https://ee.ee/ik","value":"1"},
                   {"system":"https://muu.ee/ik","value":"2"}]}""",
                Set.of());

        // And what the slice is actually for: exactly one of its own.
        bothCount("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://ee.ee/ik","value":"1"}]}""",
                Set.of());
    }

    // ── the whole payload contract, answered from the index ──
    //
    // IndexPayloads is the same Payloads contract the element face
    // implements, over the definition index instead of a populated worker
    // context, and it is asked the whole contract — reading, the type,
    // writing back, the checks and the shape stamp. The round trip is the
    // part a checker never needs: a face that gives the document back must
    // keep a literal distinct from a string, because writing a number as a
    // string corrupts every document it touches. Nothing selects this face
    // for a tenant; what is settled is that the contract can be met without
    // the element model.

    @Test
    @Order(22)
    @DisplayName("a document read and written back is the document that arrived, over "
            + "everything the version publishes")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void whatIsReadIsWhatIsWritten() {
        int compared = 0;
        List<String> altered = new ArrayList<>();
        for (FaceRootPackages.Definition document : FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"))) {
            if (compared >= 400) {
                break;
            }
            compared++;
            IndexPayloads.Document read = indexFace.read(document.typeName(), document.document());
            byte[] written = indexFace.write(read);
            // Read again rather than compared byte for byte: what has to
            // survive is the document, not the whitespace somebody's composer
            // happened to emit. A value that changed type, a repeat that lost
            // an entry or a decimal that lost a digit all fail this.
            if (!indexFace.read(document.typeName(), written).tree().equals(read.tree())) {
                altered.add(document.typeName() + " " + document.url());
            }
        }
        System.out.printf("%n=== read and written back, over r4 ===%n"
                + "documents %d, altered %d%n", compared, altered.size());
        altered.stream().limit(5).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 200, "too few documents to mean anything: " + compared);
        assertEquals(List.of(), altered, "a document did not survive being read and written");
    }

    @Test
    @Order(23)
    @DisplayName("a literal is not a string: what arrived unquoted is written unquoted, and a "
            + "decimal keeps the precision its author gave it")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aLiteralIsNotAString() {
        // The failure this guards is silent and total: a face that wrote
        // numbers as strings would corrupt every document it stored, and a
        // tree-to-tree comparison above would not catch it on its own because
        // both sides would be equally wrong.
        String written = """
                {"resourceType":"Observation","status":"final","valueQuantity":{"value":1.500},\
                "issued":"2020-01-01T00:00:00Z","_status":{"id":"x"},\
                "component":[{"valueBoolean":true},{"valueInteger":0}]}""";
        IndexPayloads.Document read = indexFace.read("Observation",
                written.getBytes(StandardCharsets.UTF_8));
        String back = new String(indexFace.write(read), StandardCharsets.UTF_8);

        assertTrue(back.contains("\"value\":1.500"),
                "a decimal lost the precision its author wrote, which FHIR makes "
                        + "significant: " + back);
        assertTrue(back.contains("\"valueBoolean\":true") && !back.contains("\"true\""),
                "a boolean came back as a string: " + back);
        assertTrue(back.contains("\"valueInteger\":0") && !back.contains("\"0\""),
                "a number came back as a string: " + back);
        assertTrue(back.contains("\"status\":\"final\""),
                "a string came back unquoted: " + back);
        assertTrue(back.contains("\"_status\""),
                "a primitive's own extension did not survive: " + back);
    }

    @Test
    @Order(24)
    @DisplayName("the type, the shape stamp and a body that is not FHIR JSON are answered the "
            + "way the contract says")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theRestOfTheContract() {
        IndexPayloads.Document patient = indexFace.read("Patient",
                "{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("Patient", indexFace.typeOf(patient), "the document does not say what it is");

        // A malformed body is the caller's fault, not the server's.
        assertThrows(IllegalArgumentException.class,
                () -> indexFace.read("Patient", "not json".getBytes(StandardCharsets.UTF_8)),
                "a body that is not FHIR JSON was accepted");

        // A stamp names the version that did the judging. The base Patient is
        // a shape this tenant holds, so a document claiming it is stamped;
        // one claiming a profile nobody holds is not, because a stamp would
        // be naming rules that never ran.
        IndexPayloads.Document claimed = indexFace.read("Patient", ("""
                {"resourceType":"Patient","meta":{"profile":["%sPatient",\
                "https://ee.ee/StructureDefinition/EiOle"]}}""".formatted(PREFIX))
                .getBytes(StandardCharsets.UTF_8));
        List<String> stamps = indexFace.writtenUnder(claimed);
        System.out.println("stamps: " + stamps);
        assertEquals(1, stamps.size(),
                "a profile nobody holds was stamped, or one everybody holds was not: " + stamps);
        assertTrue(stamps.get(0).startsWith(PREFIX + "Patient|"),
                "the stamp does not name the shape and its version: " + stamps);
    }

    @Test
    @Order(25)
    @DisplayName("what it refuses and what it accepts is what the database says about the same "
            + "document")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void itRefusesWhatTheDatabaseRefuses() {
        // Clean.
        assertEquals(List.of(), indexFace.validate("Patient",
                indexFace.read("Patient", "{\"resourceType\":\"Patient\",\"gender\":\"female\"}"
                        .getBytes(StandardCharsets.UTF_8))),
                "a correct document was refused");

        // A required element absent, an element sent twice where one is
        // allowed, and a code outside a required binding: one of each of the
        // three kinds this face answers.
        assertTrue(!indexFace.validate("Observation", indexFace.read("Observation",
                        "{\"resourceType\":\"Observation\"}".getBytes(StandardCharsets.UTF_8)))
                        .isEmpty(),
                "a document missing a required element was accepted");
        assertTrue(!indexFace.validate("Patient", indexFace.read("Patient",
                        "{\"resourceType\":\"Patient\",\"gender\":[\"female\",\"male\"]}"
                                .getBytes(StandardCharsets.UTF_8))).isEmpty(),
                "an element allowed once and sent twice was accepted — which is the defect "
                        + "this item found the toolchain committing in silence");
        assertTrue(!indexFace.validate("Patient", indexFace.read("Patient",
                        "{\"resourceType\":\"Patient\",\"gender\":\"kass\"}"
                                .getBytes(StandardCharsets.UTF_8))).isEmpty(),
                "a code outside a required binding was accepted");

        // And a type this tenant holds no shape for is not a document that is
        // wrong: unresolvable is not invalid.
        assertEquals(List.of(), indexFace.validate("Appointment", indexFace.read("Appointment",
                        "{\"resourceType\":\"Appointment\"}".getBytes(StandardCharsets.UTF_8))),
                "a shape the tenant does not hold was treated as a refusal");
    }

    // ── and what a checker in the process rests on, counted rather than assumed ──
    //
    // Cardinality, fixed and pattern are answered from the index because
    // everything they need is a column. A required binding needs codes,
    // which are not definitions, and a slice or an invariant needs a
    // predicate evaluated, which the database gets from Postgres's jsonpath
    // and the process has to evaluate itself. Each premise the in-process
    // checkers rest on is asserted here — everything a required binding names
    // is content the tenant holds, every slice predicate is the one form,
    // every invariant construct is one somebody costed — so a version that
    // changed one fails by name instead of moving a checker from answering to
    // guessing.

    @Test
    @Order(26)
    @DisplayName("what a required binding would cost to answer in heap, counted rather than "
            + "guessed at")
    void whatARequiredBindingWouldCost() throws Exception {
        int required = countOverClosure("""
                SELECT count(*) FROM definitions.definition_element
                 WHERE canonical = ANY(?) AND binding_strength = 'required'
                   AND binding_valueset IS NOT NULL""");
        Set<String> valueSets = strings("""
                SELECT DISTINCT binding_valueset FROM definitions.definition_element
                 WHERE canonical = ANY(?) AND binding_strength = 'required'
                   AND binding_valueset IS NOT NULL""");

        // A value set is held as its COMPOSE and not as an expansion, which is
        // the whole finding: the index holds which value set an element is
        // bound to, and nothing holds what is in it.
        Map<String, Integer> byShape = new TreeMap<>();
        int held = 0;
        long codes = 0;
        int onlyWithoutTheVersion = 0;
        for (String url : valueSets) {
            String compose = compose(url);
            if (compose == null && url.indexOf('|') > 0) {
                // A binding names a canonical WITH its version —
                // ...|4.0.1 — and the terminology is keyed by url. Asked as
                // written, every one of these reads as content the tenant does
                // not hold, which would be the wrong conclusion entirely: it
                // holds them, under the name without the version.
                compose = compose(url.substring(0, url.indexOf('|')));
                if (compose != null) {
                    onlyWithoutTheVersion++;
                }
            }
            if (compose == null) {
                byShape.merge("not held by this tenant", 1, Integer::sum);
                continue;
            }
            held++;
            // The store keeps a compose under its own names — includes and
            // excludes — rather than FHIR's singular ones. Read with the wrong
            // ones, every value set reads as a shape nobody recognises, which
            // is the same silent wrong answer the |version above would give.
            JsonNode json = JSON.readTree(compose);
            boolean filters = false;
            boolean nested = false;
            boolean enumerated = false;
            boolean wholeSystem = false;
            for (JsonNode include : json.path("includes")) {
                filters |= include.has("filter");
                nested |= include.has("valueSet");
                enumerated |= include.has("concept");
                wholeSystem |= include.has("system") && !include.has("concept")
                        && !include.has("filter");
            }
            if (!json.path("excludes").isEmpty()) {
                byShape.merge("has an exclude", 1, Integer::sum);
            }
            if (filters) {
                byShape.merge("a filter, which is an expansion", 1, Integer::sum);
            } else if (nested) {
                byShape.merge("another value set, which is an expansion", 1, Integer::sum);
            } else if (enumerated) {
                byShape.merge("codes written out", 1, Integer::sum);
            } else if (wholeSystem) {
                byShape.merge("a whole code system", 1, Integer::sum);
                codes += countOver("""
                        SELECT count(*) FROM definitions.term_concept WHERE system = ANY(?)""",
                        systemsOf(json));
            } else {
                byShape.merge("something else", 1, Integer::sum);
            }
        }

        // What the tenant holds AT ALL, because "none of the 43" means one
        // thing if the terminology tables are full and another if they are
        // empty, and the second would be a fact about this tenant rather than
        // about what a binding check needs.
        int allValueSets = scalar("SELECT count(*) FROM definitions.term_valueset");
        int allConcepts = scalar("SELECT count(*) FROM definitions.term_concept");

        System.out.printf("%n=== what a required binding would need, over %s's closure ===%n"
                + "elements with a required binding %d, naming %d distinct value sets%n"
                + "of those, held by this tenant    %d%n"
                + "codes behind the whole-system ones %d%n"
                + "this tenant holds %d value sets and %d concepts in all%n"
                + "found only after dropping a |version from the binding %d%n",
                faceRoot.code(), required, valueSets.size(), held, codes,
                allValueSets, allConcepts, onlyWithoutTheVersion);
        byShape.forEach((shape, n) -> System.out.printf("  %-44s %d%n", shape, n));

        assertTrue(required > 0, "a version with no required bindings is not a version");
        assertTrue(valueSets.size() > 10,
                "too few value sets for the shape count to say anything: " + valueSets.size());
        // THE ANSWER, locked rather than printed: everything a required
        // binding in this closure names is content the tenant already holds.
        // If that stops being true, whatever answers a binding in heap is
        // answering about content that is not there, and this says so here
        // rather than in a wrong verdict on somebody's write.
        assertEquals(valueSets.size(), held,
                "a required binding names a value set this tenant does not hold");
        // And the codes behind them are the cost. Six hundred is the finding:
        // a required-binding check over a tenant's closure is not an
        // expansion problem, it is a few hundred strings beside the index.
        assertTrue(codes > 0 && codes < 100_000,
                "the codes behind the required bindings are not what was measured: " + codes);
    }

    @Test
    @Order(27)
    @DisplayName("what a slice would cost to answer in heap: the shapes the located steps "
            + "actually take")
    void whatASliceWouldCost() {
        // A row's steps are jsonpaths relative to its parent instance: one
        // normally, several for a choice, one with a predicate for a slice.
        // The database hands them to Postgres. Anything in heap evaluates
        // them itself, so what matters is how many shapes there are.
        int plain = 0;
        int choice = 0;
        int sliced = 0;
        int unlocatable = 0;
        Map<String, Integer> predicates = new TreeMap<>();
        List<String> beyondEquality = new ArrayList<>();
        // Over everything the tenant holds rows for and not only the closure:
        // a slice is something a PROFILE writes, and the closure of six
        // declared types is base definitions almost entirely. Counting only
        // there would answer that there is no slicing in FHIR.
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT steps FROM definitions.definition_element")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Array array = rs.getArray(1);
                    Object[] steps = array == null ? new Object[0] : (Object[]) array.getArray();
                    if (steps.length == 0) {
                        unlocatable++;
                        continue;
                    }
                    boolean predicate = false;
                    for (Object step : steps) {
                        if (step != null && String.valueOf(step).contains("?")) {
                            predicate = true;
                            String one = String.valueOf(step);
                            predicates.merge(shapeOf(one), 1, Integer::sum);
                            // What an evaluator would have to understand, read
                            // off the predicate itself rather than off the
                            // elided shape: anything but equality joined by
                            // and is a second form to write.
                            String test = one.substring(one.indexOf('?'));
                            if (test.contains("like_regex") || test.contains("||")
                                    || test.contains("exists") || test.contains(">")
                                    || test.contains("<") || test.contains("!=")
                                    || test.contains("starts with")) {
                                beyondEquality.add(one);
                            }
                        }
                    }
                    if (predicate) {
                        sliced++;
                    } else if (steps.length > 1) {
                        choice++;
                    } else {
                        plain++;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the located steps failed", e);
        }

        int all = plain + choice + sliced + unlocatable;
        System.out.printf("%n=== what a slice would need, over everything %s holds ===%n"
                + "one plain step   %5d  (%4.1f%%)%n"
                + "several, a choice %4d  (%4.1f%%)%n"
                + "with a predicate  %4d  (%4.1f%%)%n"
                + "no step at all    %4d  (%4.1f%%)%n", faceRoot.code(),
                plain, 100.0 * plain / all, choice, 100.0 * choice / all,
                sliced, 100.0 * sliced / all, unlocatable, 100.0 * unlocatable / all);
        predicates.forEach((shape, n) -> System.out.printf("  %-40s %d%n", shape, n));

        assertTrue(all > 500, "too few rows for the shape count to say anything: " + all);
        assertTrue(sliced > 0, "no predicate at all, so the shapes say nothing");
        // THE ANSWER, locked: every predicate in the whole of what this tenant
        // holds is equality, optionally conjoined. Not a comparison, not a
        // regex, not an existence test. So what a slice needs in heap is an
        // evaluator for one form over a path of a few segments, and the
        // moment a sixth shape appears this fails and says which.
        assertEquals(List.of(), beyondEquality,
                "a predicate appeared that is not equality, so a checker in heap needs more "
                        + "than the one form: " + beyondEquality);
    }

    @Test
    @Order(28)
    @DisplayName("what an invariant would cost to answer in heap: the constructs the compiled "
            + "paths actually use")
    void whatAnInvariantWouldCost() {
        // An invariant is compiled when the definition arrives, into a
        // jsonpath the database executes with jsonb_path_match. Anything in
        // heap executes it itself, so what matters is the grammar those paths
        // actually reach for — which is a much wider one than a slice's
        // predicate, and the reason this is counted before anything is built.
        Map<String, Integer> uses = new TreeMap<>();
        int all = 0;
        int existsOnly = 0;
        int longest = 0;
        Map<String, Integer> byKey = new TreeMap<>();
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT key, path FROM definitions.definition_invariant
                      WHERE canonical = ANY(?) AND path IS NOT NULL""")) {
            ps.setArray(1, c.createArrayOf("text", declaredClosure.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String path = rs.getString(2);
                    if (!byKey.containsKey(key)) {
                        byKey.put(key, 0);
                    }
                    byKey.merge(key, 1, Integer::sum);
                    all++;
                    longest = Math.max(longest, path.length());
                    // Navigation and the boolean connectives are the cheap
                    // half: a path, exists, not, and, or. Everything else
                    // compares, matches or filters, and each is a piece of
                    // evaluator on its own.
                    boolean navigationOnly = true;
                    for (String[] construct : new String[][] {
                            {"exists(", "exists"}, {"!", "negation"}, {"&&", "and"},
                            {"||", "or"}, {"? (", "a filter"}, {"like_regex", "like_regex"},
                            {"starts with", "starts with"}, {"==", "equality"},
                            {"!=", "inequality"}, {">", "greater"}, {"<", "less"},
                            {".type()", "type()"}}) {
                        if (path.contains(construct[0])) {
                            uses.merge(construct[1], 1, Integer::sum);
                            if (!NAVIGATION.contains(construct[1])) {
                                navigationOnly = false;
                            }
                        }
                    }
                    if (navigationOnly) {
                        existsOnly++;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the compiled invariants failed", e);
        }

        System.out.printf("%n=== what an invariant would need, over %s's closure ===%n"
                + "compiled paths %d, over %d distinct rule keys, longest %d characters%n"
                + "using only navigation and the boolean connectives: %d (%4.1f%%)%n",
                faceRoot.code(), all, byKey.size(), longest,
                existsOnly, 100.0 * existsOnly / all);
        int compiledPaths = all;
        uses.forEach((construct, n) -> System.out.printf("  %-18s %5d  (%4.1f%%)%n",
                construct, n, 100.0 * n / compiledPaths));
        byKey.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> System.out.printf("  rule %-10s %5d rows%n", e.getKey(),
                        e.getValue()));

        assertTrue(all > 50, "too few compiled invariants to say anything: " + all);
        // THE ANSWER, locked: two thirds of them need only what a walk over a
        // document already does, and the third that needs more needs one of
        // these five things and not something nobody has seen. A construct
        // outside this set is a piece of evaluator that does not exist, and
        // an answerer meeting one must stay silent rather than guess — so it
        // fails here, by name, rather than in a verdict.
        List<String> unknown = uses.keySet().stream()
                .filter(construct -> !NAVIGATION.contains(construct)
                        && !Set.of("a filter", "like_regex", "starts with", "equality",
                                "inequality", "greater", "less").contains(construct))
                .toList();
        assertEquals(List.of(), unknown,
                "a construct appeared that nothing has costed: " + unknown);
    }

    // ── helpers: the tenants ──

    private static PGSimpleDataSource faceRootSource() {
        return sourceOf(faceRoot);
    }

    private static PGSimpleDataSource profiledSource() {
        return sourceOf(profiled);
    }

    private static PGSimpleDataSource sourceOf(SharedTenants.Tenant tenant) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }

    /** The structures the face root declares it operates on, as canonicals. */
    private static Set<String> declaredSeeds() {
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : FACE_ROOT_DECLARED) {
            seeds.add(PREFIX + type);
        }
        return seeds;
    }

    /** The structures the clinic dependent declares, as canonicals. */
    private static Set<String> clinicSeeds() {
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : CLINIC_DECLARED) {
            seeds.add(PREFIX + type);
        }
        return seeds;
    }

    /** A count over the whole of a face-root table, which takes no canonical. */
    private static int scalar(String sql) {
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("counting what the face holds failed", e);
        }
    }

    // ── helpers: where the definitions live ──

    private boolean written;

    private void writeAProfileAndAPatient() {
        if (written) {
            return; // one write, whichever leg runs first
        }
        var store = faceRoot.store();
        store.create("""
                {"resourceType":"StructureDefinition","url":"urn:test:profile:one",
                 "name":"OnlyAName","status":"draft","kind":"resource","abstract":false,
                 "type":"Patient","baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[{"id":"Patient","path":"Patient"}]}}""");
        store.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:test:mrn","value":"1"}]}""");
        written = true;
    }

    private static TypeRegistration registration(String typeName, String domain) {
        return new TypeRegistration(typeName, domain, IdentityClass.INTERNAL,
                Set.of(), Handling.operational(),
                (type, payload) -> new Envelope(), List.of());
    }

    private static List<FeedItem> drain(ChangeFeed feed, String consumer) {
        List<FeedItem> all = new ArrayList<>();
        FeedChunk<FeedItem> chunk;
        while (!(chunk = feed.readFor(consumer, 200)).items().isEmpty()) {
            all.addAll(chunk.items());
            feed.ack(consumer, chunk.nextCursor());
        }
        return all;
    }

    private static List<FeedItem> drain(ChangeFeed feed, String consumer, FeedSelection wanted) {
        List<FeedItem> all = new ArrayList<>();
        FeedChunk<FeedItem> chunk;
        while (!(chunk = feed.readFor(consumer, 200, wanted)).items().isEmpty()) {
            all.addAll(chunk.items());
            feed.ack(consumer, chunk.nextCursor());
        }
        return all;
    }

    private static List<String> typesOn(List<FeedItem> items) {
        return items.stream().map(FeedItem::typeName).distinct().toList();
    }

    /** The canonicals the chunk carries, read off the payloads it delivered. */
    private static List<String> canonicalsIn(List<FeedItem> items) {
        List<String> urls = new ArrayList<>();
        java.util.regex.Pattern url = java.util.regex.Pattern.compile(
                "\"url\"\\s*:\\s*\"([^\"]+)\"");
        for (FeedItem item : items) {
            if (item.payload() == null) {
                continue;
            }
            java.util.regex.Matcher found = url.matcher(
                    new String(item.payload(), StandardCharsets.UTF_8));
            if (found.find()) {
                urls.add(found.group(1));
            }
        }
        return urls;
    }

    /** Rows of a face-root table matching a condition. */
    private static long rows(String qualifiedTable, String where) throws Exception {
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM " + qualifiedTable + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** What the face root's rows answer to a query over a set of names. */
    private static Set<String> query(String sql, Set<String> over) {
        Set<String> out = new TreeSet<>();
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", over.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) {
                        out.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the grain failed", e);
        }
        return out;
    }

    // ── helpers: the index against the packages, and the two front ends ──

    /** How many structures the face root holds any rows for at all. */
    private static int canonicalsWithRows() {
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(DISTINCT canonical) FROM definitions.definition_element")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("counting what the tenant holds failed", e);
        }
    }

    /** One element, reduced to what both sides can be asked for. */
    private record Element(String path, int min, int max, List<String> types) {
    }

    /**
     * The same structures read out of the packages, which is what the index
     * built from the rows has to agree with.
     *
     * <p>No toolchain: a token walk over the snapshot, because a comparison
     * that needed a worker context to state one of its sides would be
     * measuring the context.
     */
    private static Map<String, List<Element>> fromThePackages(Set<String> wanted)
            throws Exception {
        Map<String, List<Element>> out = new LinkedHashMap<>();
        for (FaceRootPackages.Definition one
                : FaceRootPackages.definitionsFor("r4", Set.of("StructureDefinition"))) {
            if (one.url() == null || !wanted.contains(one.url())) {
                continue;
            }
            JsonNode snapshot = JSON.readTree(one.document()).path("snapshot").path("element");
            if (!snapshot.isArray() || snapshot.isEmpty()) {
                continue;
            }
            List<Element> elements = new ArrayList<>();
            for (JsonNode element : snapshot) {
                List<String> types = new ArrayList<>();
                for (JsonNode type : element.path("type")) {
                    if (type.hasNonNull("code")) {
                        types.add(type.get("code").asText());
                    }
                }
                String max = element.path("max").asText("");
                elements.add(new Element(element.path("path").asText(),
                        element.path("min").asInt(0),
                        "*".equals(max) ? DefinitionIndex.UNBOUNDED : safe(max),
                        types));
            }
            out.put(one.url(), elements);
        }
        return out;
    }

    private static int safe(String max) {
        try {
            return Integer.parseInt(max);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** What the two envelopes disagree about, or null. */
    private static String differing(Envelope written, Envelope fromRows) {
        Map<String, List<String>> left = shown(written);
        Map<String, List<String>> right = shown(fromRows);
        if (!left.keySet().equals(right.keySet())) {
            Set<String> missing = new TreeSet<>(left.keySet());
            missing.removeAll(right.keySet());
            Set<String> extra = new TreeSet<>(right.keySet());
            extra.removeAll(left.keySet());
            return "the compiled set misses " + missing + " and adds " + extra;
        }
        for (String key : left.keySet()) {
            if (!left.get(key).equals(right.get(key))) {
                return key + ": written " + left.get(key) + ", compiled " + right.get(key);
            }
        }
        return null;
    }

    /** An envelope as comparable text, values sorted because order is not the claim. */
    private static Map<String, List<String>> shown(Envelope envelope) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Map.Entry<String, List<EnvelopeValue>> entry : envelope.paths().entrySet()) {
            List<String> values = new ArrayList<>();
            for (EnvelopeValue value : entry.getValue()) {
                values.add(String.valueOf(value));
            }
            Collections.sort(values);
            out.put(entry.getKey(), values);
        }
        return out;
    }

    // ── helpers: the database against the toolchain ──

    /**
     * Per type, so the slow ones do not decide how long this takes and the
     * rare ones are not crowded out. In filename order, so the same documents
     * are compared every run and the baseline means something.
     */
    private static final int PER_TYPE = 40;

    private static final Path BASELINE = Path.of("..", "..", "config", "divergence-baseline.txt");

    /** What the database finds wrong with a patient, judged by the base Patient. */
    private static long issues(String patient) {
        return definitions.issuesUnder(patient.getBytes(StandardCharsets.UTF_8),
                        PREFIX + "Patient")
                .orElseThrow(() -> new AssertionError("this root holds no Patient to judge by"));
    }

    /**
     * Every type's divergence is compared to its own recorded number, so a
     * type that got worse is named rather than hidden inside a total that
     * something else improved.
     */
    private static void assertNoWorseThan(String recorded, String observed) {
        Map<String, int[]> was = parse(recorded);
        Map<String, int[]> now = parse(observed);
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : now.entrySet()) {
            int[] before = was.get(entry.getKey());
            if (before == null) {
                continue; // a type nobody had compared before
            }
            int[] after = entry.getValue();
            if (after[1] > before[1] || after[2] > before[2]) {
                worse.add(entry.getKey() + ": was onlyTheToolchain=" + before[1]
                        + " onlyTheDatabase=" + before[2] + ", now onlyTheToolchain=" + after[1]
                        + " onlyTheDatabase=" + after[2]);
            }
        }
        assertEquals(List.of(), worse,
                "the two answers agree about less of the version than they did. Re-record "
                        + "with -Ddbo.divergence.record=true only when the change is meant.\n"
                        + observed);
    }

    /** What the version publishes, by type, bounded and in a fixed order. */
    private static Map<String, List<FaceRootPackages.Definition>> corpus() {
        Map<String, List<FaceRootPackages.Definition>> byType = new LinkedHashMap<>();
        for (FaceRootPackages.Definition definition : FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
                        "ConceptMap", "OperationDefinition", "CapabilityStatement",
                        "CompartmentDefinition", "NamingSystem", "StructureMap"))) {
            List<FaceRootPackages.Definition> held =
                    byType.computeIfAbsent(definition.typeName(), ignored -> new ArrayList<>());
            if (held.size() < PER_TYPE) {
                held.add(definition);
            }
        }
        return byType;
    }

    /** The errors the side that found something reported, for reading. */
    private static String saidBy(byte[] document, boolean toolchain, String canonical) {
        try {
            if (!toolchain) {
                return "the database found "
                        + definitions.issuesUnder(document, canonical).orElse(0);
            }
            // The error and fatal issues alone, and not the outcome whole: an
            // outcome leads with warnings, so the first fifteen hundred
            // characters of one can be entirely the part that decides
            // nothing, and a reader of a truncated one concludes there were
            // no errors.
            String outcome = faceRoot.store()
                    .validationOutcome(new String(document, StandardCharsets.UTF_8));
            StringBuilder deciding = new StringBuilder();
            java.util.regex.Matcher issue = java.util.regex.Pattern
                    .compile("\\{\"severity\":\"(error|fatal)\".*?\\}(?=,\\{\"severity\"|\\]\\})")
                    .matcher(outcome);
            while (issue.find()) {
                deciding.append("    ").append(issue.group()).append(System.lineSeparator());
            }
            return deciding.isEmpty()
                    ? "no error or fatal issue, so what counted it is the refusal itself"
                    : deciding.toString();
        } catch (RuntimeException refused) {
            return "refused: " + refused.getMessage();
        }
    }

    private static boolean theToolchainRefuses(byte[] document) {
        try {
            String outcome = faceRoot.store()
                    .validationOutcome(new String(document, StandardCharsets.UTF_8));
            return outcome.contains("\"severity\":\"error\"")
                    || outcome.contains("\"severity\":\"fatal\"");
        } catch (RuntimeException refused) {
            // A refusal is a finding said louder; what is compared is whether
            // it found anything.
            return true;
        }
    }

    private static final String PREAMBLE = """
            # What the toolchain and the database disagree about, over everything the
            # version publishes. One line per resource type:
            #
            #   <type> compared=<n> onlyTheToolchain=<n> onlyTheDatabase=<n>
            #
            # GENERATED. Re-record with:
            #     ./gradlew :core:harness:test \\
            #         --tests '*TheVersionIsMeasuredIT.theyDisagreeAboutNoMoreThanWasRecorded' \\
            #         -Ddbo.divergence.record=true
            #
            # It may fall and may not rise. The database is measured beside the
            # toolchain and acts on nothing, and the whole case for it answering at
            # all is that it answers the same.
            #
            # What the remaining FIVE are, read one by one — and read by the findings that
            # actually decided each one. An outcome carries everything the face has to say,
            # warnings included; only error and fatal decide this count, and the first
            # sentence in an outcome is usually neither.
            #
            # RULES THE VALIDATOR CARRIES IN ITS OWN CODE, which no definition states: a
            # canonical url must be absolute, a uuid must be lowercase, an identifier under
            # urn:ietf:rfc:3986 must be a full uri. Three rules over six documents, and the
            # absolute-url one accounted for eighteen findings by itself. Nothing compiled
            # FROM the definitions can produce these, and compiling invariants did not move
            # them by one.
            #
            # TWO OF THE THREE ARE WRITTEN NOW, in dbo.admits beside the primitive forms:
            # a uuid is lowercase and a canonical carries a scheme. That closed every
            # CapabilityStatement — five documents, and the count with them. What a
            # definition cannot state, somebody states once.
            #
            # A STRUCTUREMAP CHECKED AS A PROGRAM rather than as a document: a source or
            # target context must be one the map declared, and a target path must exist on
            # the type it targets. Both maps this version publishes diverge this way. A
            # checker built from StructureDefinitions has nothing to say about either,
            # because neither is a statement about the shape of a StructureMap.
            #
            # ONE IS THE TOOLCHAIN FAILING, not this store lacking. Constraint cid-0 cannot
            # be evaluated at all — the validator holds that invariant and reports that the
            # name in its own expression is not valid for any of the possible types. It is
            # a divergence and it is counted, but the side that said nothing is not the
            # side that was wrong.
            #
            # ONE IS CONTENT: codes under http://snomed.info/sct that this tenant's
            # terminology does not hold. No rule of any kind answers that. It closes by
            # carrying the content and would not have moved however many invariants were
            # compiled.
            #
            # So the ceiling that "the specification is data" runs into was the first group
            # and only it, and two thirds of that group is now written down. What is left
            # is a program checker, a defect in the toolchain, and a gap in what is loaded
            # — none of which a checker built from StructureDefinitions was ever going to
            # answer.
            #
            # Which of the five is which is printable rather than remembered:
            #     ./gradlew :core:harness:test \\
            #         --tests '*TheVersionIsMeasuredIT.theyDisagreeAboutNoMoreThanWasRecorded' \\
            #         -Ddbo.divergence.name=true
            #     cat core/harness/build/divergence-findings.txt
            """;

    private static String asLines(Map<String, int[]> perType) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, int[]> entry : perType.entrySet()) {
            int[] tally = entry.getValue();
            out.append(entry.getKey()).append(" compared=").append(tally[0])
                    .append(" onlyTheToolchain=").append(tally[1])
                    .append(" onlyTheDatabase=").append(tally[2]).append('\n');
        }
        return out.toString();
    }

    private static Map<String, int[]> parse(String lines) {
        Map<String, int[]> out = new TreeMap<>();
        for (String line : lines.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            out.put(parts[0], new int[] {
                    number(parts[1]), number(parts[2]), number(parts[3])});
        }
        return out;
    }

    private static int number(String part) {
        return Integer.parseInt(part.substring(part.indexOf('=') + 1));
    }

    // ── helpers: the third answerer ──

    /** The types the corpus holds, plus the two ordinary ones the wrong documents use. */
    private static final Set<String> INDEXED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem", "ConceptMap",
            "OperationDefinition", "CapabilityStatement", "CompartmentDefinition",
            "NamingSystem", "StructureMap", "Patient", "Observation"));

    private static DefinitionIndex versionIndex;

    /** Built once: it is the same rows for every document put through it. */
    private static synchronized DefinitionIndex versionIndex() {
        if (versionIndex == null) {
            Set<String> seeds = new LinkedHashSet<>();
            for (String type : INDEXED) {
                seeds.add(PREFIX + type);
            }
            versionIndex = DefinitionRows.over(faceRootSource(),
                    DefinitionRows.closureOf(faceRootSource(), seeds));
        }
        return versionIndex;
    }

    private static BoundCodes codes;

    /** The codes behind the required bindings, read once beside the index. */
    private static synchronized BoundCodes codes() {
        if (codes == null) {
            codes = BoundCodes.over(faceRootSource(), versionIndex());
        }
        return codes;
    }

    /** A rule key, which is how an invariant finding is named apart from the other checks. */
    private static final java.util.regex.Pattern RULE_KEYS =
            java.util.regex.Pattern.compile("[a-z][a-z0-9]*-[0-9]+");

    /** The rule keys dbo.invariant_issues reports. */
    private static Set<String> ruleKeys(byte[] document, String canonical) {
        Set<String> keys = new TreeSet<>();
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key FROM dbo.invariant_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(document, StandardCharsets.UTF_8));
            ps.setString(2, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about the rules failed", e);
        }
        return keys;
    }

    /** Both answerers on the binding check alone. */
    private static void bothBind(String canonical, String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = new TreeSet<>();
        for (Finding one : ElementChecks.over(versionIndex(), codes(), canonical, bytes)
                .findings()) {
            if ("binding".equals(one.key())) {
                ours.add(withoutIndices(one.path()));
            }
        }
        Set<String> theirs = new TreeSet<>();
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.binding_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(bytes, StandardCharsets.UTF_8));
            ps.setString(2, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    theirs.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about a binding failed", e);
        }
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), theirs, "the database: " + document);
    }

    /**
     * Both answerers on cardinality, over one document of the face root,
     * against what should be said.
     *
     * <p>The expectation is stated as well as the agreement, because two
     * answerers can agree by both being silent and that is exactly what the
     * corpus half cannot rule out.
     */
    private static void bothSay(String canonical, String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = pathsFromTheIndex(canonical, document);
        Set<String> theirs = cardinalityPaths(bytes, canonical);
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), theirs, "the database: " + document);
    }

    /** What the index checker faults, as the elements rather than the occurrences. */
    private static Set<String> pathsFromTheIndex(String canonical, String document) {
        Set<String> paths = new TreeSet<>();
        for (Finding one : ElementChecks.over(versionIndex(), canonical,
                document.getBytes(StandardCharsets.UTF_8)).findings()) {
            if ("cardinality".equals(one.key())) {
                paths.add(withoutIndices(one.path()));
            }
        }
        return paths;
    }

    /** What dbo.cardinality faults on the face root, as the definition paths it names. */
    private static Set<String> cardinalityPaths(byte[] document, String canonical) {
        Set<String> paths = new TreeSet<>();
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.cardinality_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(document, StandardCharsets.UTF_8));
            ps.setString(2, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about cardinality failed", e);
        }
        return paths;
    }

    /**
     * An instance path reduced to the definition path it is an instance of.
     *
     * <p>The database names the element — {@code Patient.contact.name} — and
     * the index checker names the occurrence — {@code Patient.contact[0].name}
     * — because a caller fixing a document needs to know which contact. What
     * is compared is which ELEMENT each faulted, so the occurrence is dropped
     * on this side rather than added on the other.
     */
    private static String withoutIndices(String path) {
        return path.replaceAll("\\[\\d+\\]", "");
    }

    /** Both answerers on cardinality alone, over the profiled tenant's sliced profile. */
    private static void bothCount(String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = new TreeSet<>();
        for (Finding one : ElementChecks.over(profileIndex, SLICED, bytes).findings()) {
            if ("cardinality".equals(one.key())) {
                ours.add(one.path().replaceAll("\\[\\d+\\]", ""));
            }
        }
        Set<String> theirs = new TreeSet<>();
        try (Connection c = profiledSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.cardinality_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(bytes, StandardCharsets.UTF_8));
            ps.setString(2, SLICED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    theirs.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about a slice failed", e);
        }
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), theirs, "the database: " + document);
    }

    /**
     * Both answerers on fixed and pattern values, over the profiled tenant's
     * pinning profile, against what should be said.
     *
     * <p>The expectation is stated as well as the agreement: two answerers
     * can agree by both being silent, and three of these five documents are
     * ones where silence would be wrong.
     */
    private static void bothSayPinned(String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = new TreeSet<>();
        for (Finding one : ElementChecks.over(profileIndex, PINNED, bytes).findings()) {
            if ("fixed".equals(one.key()) || "pattern".equals(one.key())) {
                ours.add(one.path().replaceAll("\\[\\d+\\]", ""));
            }
        }
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), valuePaths(bytes), "the database: " + document);
    }

    /** What dbo.value_issues faults, as the definition paths it names. */
    private static Set<String> valuePaths(byte[] document) {
        Set<String> paths = new TreeSet<>();
        try (Connection c = profiledSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.value_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(document, StandardCharsets.UTF_8));
            ps.setString(2, PINNED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about pinned values failed", e);
        }
        return paths;
    }

    // ── helpers: what a checker rests on ──

    /** What a walk over a document tree already does, without comparing anything. */
    private static final Set<String> NAVIGATION =
            Set.of("exists", "negation", "and", "or");

    /** A predicate reduced to its form, so a hundred slices are a handful of shapes. */
    private static String shapeOf(String step) {
        return step.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("^[^?]*\\?", "? ");
    }

    private static Set<String> systemsOf(JsonNode compose) {
        Set<String> systems = new LinkedHashSet<>();
        for (JsonNode include : compose.path("includes")) {
            if (include.hasNonNull("system")) {
                systems.add(include.get("system").asText());
            }
        }
        return systems;
    }

    private static String compose(String url) {
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT compose::text FROM definitions.term_valueset WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading a value set's compose failed", e);
        }
    }

    /** A count over the face root's declared closure. */
    private static int countOverClosure(String sql) {
        return countOver(sql, declaredClosure);
    }

    private static int countOver(String sql, Set<String> over) {
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", over.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("counting failed", e);
        }
    }

    private static Set<String> strings(String sql) {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = faceRootSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", declaredClosure.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading failed", e);
        }
        return out;
    }
}
