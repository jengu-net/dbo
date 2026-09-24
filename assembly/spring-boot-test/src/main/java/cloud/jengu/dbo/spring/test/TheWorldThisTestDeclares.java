package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.sync.ConfigSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The tenants a test declares, held in memory rather than on a disk.
 *
 * <p>A directory is state on one node's disk, and a test that changed a
 * deployment by writing files would be testing a filesystem as much as a
 * store. {@code TenantRuntimeManager.declaredFrom} already says a directory is
 * one source among several and names this one as the next: <i>an application
 * whose tenants are declared where the rest of its configuration is</i>. So
 * this is not a test fixture pretending to be a filesystem; it is the seam a
 * host is expected to use, with a test holding the other end.
 *
 * <p><b>The directory is a bootstrap and nothing more.</b> What it holds is
 * read once, to start from a world somebody can open and read. Every change
 * after that is a call on this object, and the files are never written to.
 *
 * <p><b>Empty is a declaration, and it is the dangerous one.</b> The contract
 * this implements is explicit: a source that cannot be read THROWS and never
 * answers with an empty set, because empty and unreachable are the same
 * sentence to the sweep that decides what is missing — and it acts on the
 * first. A test that retracts its last tenant means it; a bug that answers
 * with nothing would retract the whole deployment, so there is no path here
 * that returns an empty set by accident rather than by instruction.
 */
public final class TheWorldThisTestDeclares implements ConfigSource {

    /**
     * What a tenant declaration is called.
     *
     * <p>Named by the runtime rather than chosen here. A source declaring
     * under a name nothing matches is not an error: every declaration is
     * simply ignored, the deployment comes up serving only what configuration
     * named directly, and nothing says why.
     */
    private static final String TENANT =
            cloud.jengu.dbo.tenant.TenantDeclarationModel.TYPE;

    private static final String SUFFIX = ".json";

    /** By file name, because that is the name a declaration is known by. */
    private final Map<String, byte[]> declared = new LinkedHashMap<>();

    private boolean readable = true;

    private TheWorldThisTestDeclares() {
    }

    /**
     * Seeded from the bootstrap directory, once.
     *
     * <p>Read here rather than watched, which is the whole point: after this
     * the files are a record of where the test started and nothing reads them
     * again.
     */
    static TheWorldThisTestDeclares seededFrom(Path directory) {
        TheWorldThisTestDeclares world = new TheWorldThisTestDeclares();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(SUFFIX))
                    .sorted().toList()) {
                world.declared.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the bootstrap world could not be read: " + directory,
                    unreadable);
        }
        return world;
    }

    /**
     * Declares a tenant, or redeclares one this test already declared.
     *
     * @param code the tenant's code, which names the declaration
     * @param spec the spec, as a deployment would have written it
     */
    public synchronized void declare(String code, String spec) {
        declared.put(code + SUFFIX, spec.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Withdraws a tenant, the way a deployment withdraws one: by no longer
     * declaring it.
     *
     * @return whether it was declared at all, so a test that meant to retract
     *         something and named it wrongly is told rather than left waiting
     *         for a retraction nobody was asked for
     */
    public synchronized boolean retract(String code) {
        return declared.remove(code + SUFFIX) != null;
    }

    /** What this test is declaring, by tenant code. */
    public synchronized List<String> declaring() {
        List<String> codes = new ArrayList<>();
        for (String name : declared.keySet()) {
            codes.add(name.substring(0, name.length() - SUFFIX.length()));
        }
        return List.copyOf(codes);
    }

    /**
     * Makes the next read fail, for a test about what an unreadable source
     * does.
     *
     * <p>Here because the contract's sharpest clause cannot be exercised any
     * other way in memory: a store that cannot be reached is a thing that
     * happens to deployments, and a source that answered it as "nobody is
     * declared" would retract every tenant on the node.
     */
    public synchronized void becomesUnreadable() {
        readable = false;
    }

    /** And readable again. */
    public synchronized void becomesReadable() {
        readable = true;
    }

    @Override
    public synchronized Fetch fetch() {
        if (!readable) {
            throw new IllegalStateException("this test's declarations cannot be read, which is "
                    + "not the same as this test declaring nobody");
        }
        List<ConfigApplication.Declared> declarations = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : declared.entrySet()) {
            declarations.add(new ConfigApplication.Declared(
                    TENANT, entry.getKey(), entry.getValue()));
        }
        // Complete: this is the whole of what the test declares, so what it
        // does not name is withdrawn — which is what makes retract() mean
        // something rather than merely stop mentioning a tenant.
        return new Fetch(declarations, ConfigSource.markerOf(declarations), true);
    }
}
