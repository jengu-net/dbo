package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DBO-CONT-EMBEDDED-IN-JVM — the PRODUCTION bundles boot in an
 * in-JVM Felix and serve a real FHIR flow over HTTP. The host shares only
 * Felix, the OSGi API and the JDK; everything DBO is reached reflectively
 * through bundle classloaders.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedContainerIT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Framework framework;
    static Map<String, Bundle> bundles = new HashMap<>();
    static AutoCloseable server;
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("EmbeddedContainerIT");

        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", FelixStorage.directory("dbo-embedded"));
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.start();
        BundleContext ctx = framework.getBundleContext();

        bundles.put("driver", ctx.installBundle("file:" + System.getProperty("pg.driver.jar")));
        // The logging arrangement, installed the way the distribution
        // installs it: the API as a bundle everything imports, and the
        // binding as a FRAGMENT of it. A fragment is never started — it
        // attaches to its host — so it stays out of the started set below.
        // SPI-Fly first: it is a FRAMEWORK EXTENSION, so it attaches to the
        // system bundle and must be there before anything requiring the
        // serviceloader extender tries to resolve.
        ctx.installBundle("file:" + System.getProperty("spifly.jar"));
        Bundle slf4j = ctx.installBundle("file:" + System.getProperty("slf4j.api.jar"));
        ctx.installBundle("file:" + System.getProperty("dbo.logging.jar")).start();
        bundles.put("slf4j", slf4j);
        for (String name : List.of("dbo.core", "dbo.fhir.common", "dbo.postgres", "dbo.terminology",
                // the HL7/HAPI engine, once, for both personalities after it
                "dbo.fhir.stack",
                // the shared facade the versions are served through
                "dbo.subscriptions", "dbo.fhir.element", "dbo.fhir.r4", "dbo.fhir.r5",
                "dbo.rest")) {
            String path = System.getProperty(name + ".jar");
            java.util.Objects.requireNonNull(path, name + ".jar system property missing");
            bundles.put(name, ctx.installBundle("file:" + path));
        }
        for (Bundle b : bundles.values()) {
            b.start();
        }
    }

    @AfterAll
    void down() throws Exception {
        if (server != null) {
            server.close();
        }
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20_000);
        }
    }

    /** Every production bundle resolves and starts ACTIVE. */
    @Test
    @Timeout(120)
    void allProductionBundlesActivate() {
        for (Map.Entry<String, Bundle> e : bundles.entrySet()) {
            assertEquals(Bundle.ACTIVE, e.getValue().getState(),
                    e.getKey() + " did not reach ACTIVE");
        }
    }

    /** Embedded stacks stay embedded: nested libs present, only DBO packages exported. */
    @Test
    void subscriptionJarEmbedsItsStack() throws Exception {
        String path = System.getProperty("dbo.subscriptions.jar");
        try (JarFile jar = new JarFile(path)) {
            List<String> libs = new ArrayList<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith(".jar")) {
                    libs.add(name);
                }
            }
            assertTrue(!libs.isEmpty(), path + " has no embedded libs");
            String exports = jar.getManifest().getMainAttributes().getValue("Export-Package");
            assertTrue(exports.startsWith("cloud.jengu.dbo."), path + " exports: " + exports);
            assertTrue(!exports.contains("ca.uhn") && !exports.contains("org.hl7")
                    && !exports.contains("dev.dbos"), path + " leaks: " + exports);
        }
    }

    /**
     * A personality carries no engine of its own. It exports its DBO package and
     * the resource DIRECTORIES of its own FHIR version — profiles and value sets
     * the validator reads back out — and not one package holding engine classes.
     * That is the difference between the packaging that duplicated 140MB per
     * personality and the one that does not.
     */
    @Test
    void personalitiesCarryValidationResourcesAndNoEngine() throws Exception {
        for (String prop : List.of("dbo.fhir.r4.jar", "dbo.fhir.r5.jar")) {
            String path = System.getProperty(prop);
            try (JarFile jar = new JarFile(path)) {
                Set<String> classPackages = new java.util.TreeSet<>();
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("lib/") && name.endsWith(".class")) {
                        classPackages.add(name.substring(0, name.lastIndexOf('/'))
                                .replace('/', '.'));
                    }
                }
                assertTrue(classPackages.isEmpty(),
                        path + " embeds engine classes: " + classPackages);

                String exports = jar.getManifest().getMainAttributes().getValue("Export-Package");
                assertTrue(exports.startsWith("cloud.jengu.dbo."), path + " exports: " + exports);
                assertTrue(!exports.contains("ca.uhn") && !exports.contains("dev.dbos"),
                        path + " leaks: " + exports);
                // only the resource tree of ITS OWN version
                String own = prop.contains("r4") ? "org.hl7.fhir.r4." : "org.hl7.fhir.r5.";
                for (String exported : exports.split(",")) {
                    if (exported.startsWith("org.hl7")) {
                        assertTrue(exported.startsWith(own),
                                path + " exports another version's package: " + exported);
                    }
                }
                // The two versions ship their definitions differently — R4 as
                // profile/value-set XML under model/, R5 as NPM tarballs under
                // packages/ — so the invariant is that SOMETHING of its own
                // version is exported, not a particular directory name.
                assertTrue(exports.contains(own),
                        path + " exports no validation resources of its own: " + exports);
            }
        }
    }

    /**
     * One exporter of the engine, and the classes it hands out are the same
     * classes on both sides of a bundle boundary. Private embedding gave each
     * personality its own {@code org.hl7.fhir.r4.model.Patient}; a resource
     * passed to a driver was an alien object of a same-named class.
     */
    @Test
    void oneBundleOwnsTheEngineAndItsClassesCrossBoundaries() throws Exception {
        // dbo-fhir-r5 speaks org.hl7.fhir.r4.model too — R4ToR5Converter takes an
        // R4 resource in — so the two personalities are a real cross-bundle
        // handoff of one model class, not two same-named ones
        Class<?> fromR4 = bundles.get("dbo.fhir.r4").loadClass("org.hl7.fhir.r4.model.Patient");
        Class<?> fromR5 = bundles.get("dbo.fhir.r5").loadClass("org.hl7.fhir.r4.model.Patient");
        Class<?> fromStack = bundles.get("dbo.fhir.stack")
                .loadClass("org.hl7.fhir.r4.model.Patient");

        assertSame(fromR4, fromR5, "personalities see different R4 model classes");
        assertSame(fromR4, fromStack, "the personality is not using the shared engine's class");
        assertTrue(String.valueOf(fromR4.getClassLoader()).contains("cloud.jengu.dbo.fhir.stack"),
                "the engine is not coming from the shared bundle: " + fromR4.getClassLoader());

        // The R5 model the R4 validator converts up to is NOT visible from the R4
        // personality: it imports the packages it names, and that one it does not.
        // The validator reaches it inside the shared bundle, which is the point —
        // the duplication was never in what a personality uses, it was in what
        // the engine needs.
        assertSame(bundles.get("dbo.fhir.stack").loadClass("org.hl7.fhir.r5.model.Patient"),
                bundles.get("dbo.fhir.r5").loadClass("org.hl7.fhir.r5.model.Patient"));
    }

    /** The payoff: a full FHIR flow served over HTTP from INSIDE the container. */
    @Test
    @Timeout(180)
    void theContainerServesARealFhirFlowOverHttp() throws Exception {
        // DataSource from the DRIVER BUNDLE's classes (host-loaded PG classes
        // would fail unwrap inside the container — the consistency trap)
        Class<?> dsClass = bundles.get("driver").loadClass("org.postgresql.ds.PGSimpleDataSource");
        Object ds = dsClass.getConstructor().newInstance();
        dsClass.getMethod("setUrl", String.class).invoke(ds, jdbcUrl);
        dsClass.getMethod("setUser", String.class).invoke(ds, postgres.getUsername());
        dsClass.getMethod("setPassword", String.class).invoke(ds, postgres.getPassword());

        Class<?> typeConfig = bundles.get("dbo.fhir.common")
                .loadClass("cloud.jengu.dbo.fhir.common.FhirTypeConfig");
        Object patientCfg = typeConfig.getMethod("identifier", String.class, String[].class)
                .invoke(null, "Patient", new String[] {EID});
        Object obsCfg = typeConfig.getMethod("internal", String.class).invoke(null, "Observation");

        Class<?> personalityClass = bundles.get("dbo.fhir.r4")
                .loadClass("cloud.jengu.dbo.fhir.r4.R4Personality");
        Object personality = personalityClass.getConstructor(List.class)
                .newInstance(List.of(patientCfg, obsCfg));
        Object registrations = personalityClass.getMethod("registrations").invoke(personality);

        Class<?> engineClass = bundles.get("dbo.postgres")
                .loadClass("cloud.jengu.dbo.postgres.PgObjectStore");
        Object engine = engineClass.getConstructor(javax.sql.DataSource.class, List.class)
                .newInstance(ds, registrations);

        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port + "/fhir";

        Class<?> storeClass = bundles.get("dbo.fhir.r4").loadClass("cloud.jengu.dbo.fhir.r4.R4Store");
        Constructor<?> storeCtor = storeClass.getConstructors()[0];
        Object store = storeCtor.newInstance(engine, personality, base);

        Class<?> serverClass = bundles.get("dbo.rest").loadClass("cloud.jengu.dbo.rest.FhirHttpServer");
        // pick the standalone 5-arg ctor by SHAPE, not by declaration order —
        // authenticated overloads came later
        Constructor<?> serverCtor = java.util.Arrays.stream(serverClass.getConstructors())
                .filter(c -> c.getParameterCount() == 5
                        && c.getParameterTypes()[2] == String.class
                        && c.getParameterTypes()[3] == int.class)
                .findFirst().orElseThrow();
        server = (AutoCloseable) serverCtor.newInstance(store, null, "127.0.0.1", port, "/fhir");

        // now: a completely ordinary FHIR client from the host
        HttpClient http = HttpClient.newHttpClient();
        String patient = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"33808080808"}],
                 "name":[{"family":"Embedded"}]}""".formatted(EID);
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(base + "/Patient"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(patient)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());

        HttpResponse<String> found = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/Patient?identifier=" + java.net.URLEncoder.encode(EID + "|33808080808", java.nio.charset.StandardCharsets.UTF_8))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, found.statusCode());
        assertTrue(found.body().contains("Embedded"),
                "the private-HAPI personality inside Felix must serve the search");

        // metadata proves the personality's HAPI runs in-container
        HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/metadata")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(metadata.body().contains("CapabilityStatement"));
    }

    /** R5 and subscriptions prove wiring: their classes load through the container. */
    /**
     * The fat bundles (embedded stacks behind a Bundle-ClassPath) hand-write
     * their Import-Package lists instead of letting bnd compute them, so a
     * newly referenced sibling package compiles, publishes, and then fails to
     * resolve AT RUNTIME as NoClassDefFoundError — with no build-time signal
     * at all (it has already happened once, in dbo-tenant → dbo-sync).
     * Until they move to computed imports,
     * this is the ratchet: every {@code cloud.jengu.dbo.*} package a bundle's
     * own classes reference must be its own export or on its import list.
     */
    @Test
    void handWrittenImportsCoverEveryCrossBundlePackageReferenced() throws Exception {
        for (String bundle : List.of("dbo.fhir.r4", "dbo.fhir.r5", "dbo.subscriptions",
                "dbo.tenant", "dbo.tenant.k8s")) {
            String path = System.getProperty(bundle + ".jar");
            java.util.Objects.requireNonNull(path, bundle + ".jar system property missing");
            Set<String> referenced = new java.util.TreeSet<>();
            String imports;
            String exports;
            try (JarFile jar = new JarFile(path)) {
                imports = jar.getManifest().getMainAttributes().getValue("Import-Package");
                exports = jar.getManifest().getMainAttributes().getValue("Export-Package");
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    // own classes only — the embedded lib/*.jar stacks are
                    // private and resolve through Bundle-ClassPath
                    if (!entry.getName().endsWith(".class")
                            || !entry.getName().startsWith("cloud/jengu/")) {
                        continue;
                    }
                    String bytes = new String(jar.getInputStream(entry).readAllBytes(),
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("cloud/jengu/dbo/([a-z0-9/]+)/[A-Z]").matcher(bytes);
                    while (m.find()) {
                        referenced.add("cloud.jengu.dbo." + m.group(1).replace('/', '.'));
                    }
                }
            }
            for (String pkg : referenced) {
                if (exports.contains(pkg + ";")) {
                    continue;
                }
                assertTrue(imports.contains(pkg + ";"),
                        bundle + " references " + pkg + " but neither exports nor imports it — "
                                + "add it to the Import-Package list in its build.gradle.kts");
            }
        }
    }

    @Test
    void r5AndSubscriptionsClassesResolveInContainer() throws Exception {
        bundles.get("dbo.fhir.r5").loadClass("cloud.jengu.dbo.fhir.r5.R5Personality");
        bundles.get("dbo.fhir.r5").loadClass("cloud.jengu.dbo.fhir.r5.R4ToR5Converter");
        bundles.get("dbo.subscriptions").loadClass("cloud.jengu.dbo.subscriptions.SubscriptionEngine");
        bundles.get("dbo.terminology").loadClass("cloud.jengu.dbo.terminology.TerminologyStore");
    }
}
