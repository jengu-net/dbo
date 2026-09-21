package cloud.jengu.dbo.sample;

import java.net.URI;
import java.util.function.Supplier;

/**
 * Where the deployment is, and who is on it.
 *
 * <p>An integrator's code starts with one of these: a base address and the
 * tenants it may talk to. Everything else in this module is handed out from
 * here, so a story reads as a scene rather than as a list of URLs — the
 * hospital admits somebody, the laboratory performs an assay, and neither
 * sentence mentions a port.
 *
 * <p><b>It holds addresses and nothing else.</b> No connection is opened here
 * and no credential is taken; a part does that when somebody asks it to. So
 * constructing this cannot fail, which matters because the thing that usually
 * fails is a world that is not up yet, and a constructor that failed for that
 * reason would report it as the wrong thing entirely.
 *
 * <p><b>The parts stay separate.</b> This hands out what an integrator holds
 * — a surface here, a runner there — rather than being one object that does
 * everything. A deployment's hospital and its laboratory are two programs in
 * two buildings, and code that made them methods on one object would be
 * describing an arrangement nobody has.
 */
public final class TheWorld {

    private final URI base;

    /**
     * @param base where the deployment answers, for example
     *             {@code http://localhost:8090}
     */
    public TheWorld(URI base) {
        this.base = base;
    }

    /** The hospital: the tenant most of the guide's scenes happen in. */
    public Surface hospital() {
        return at("hogwarts");
    }

    /** The insurer, a release behind and reading the same people. */
    public Surface insurer() {
        return at("gringotts");
    }

    /** The clinic that keys nobody by a national number. */
    public Surface clinic() {
        return at("st-jerome");
    }

    /** The zone: what a jurisdiction publishes to the tenants under it. */
    public Surface zone() {
        return at("rl");
    }

    /**
     * Any tenant by its code, for a deployment whose cast is not this one.
     *
     * <p>The four above are conveniences that name the guide's world. This is
     * the general case, and the reason the four can be as short as they are.
     */
    public Surface at(String tenant) {
        return new Surface(base, tenant);
    }

    /**
     * The runner an integrator starts beside the ward: a lane to the hospital
     * and the step services it performs.
     *
     * <p>Handed out from here because the address is this object's business
     * and the runner's is the work. It is {@link AutoCloseable} and the caller
     * holds it — a runner this object kept would outlive the scene that
     * wanted it.
     */
    public Admissions admissions(Supplier<String> bearer) {
        return new Admissions(base.resolve("/t/hogwarts/"), "hogwarts", bearer);
    }

    /** Where this world answers. */
    public URI base() {
        return base;
    }
}
