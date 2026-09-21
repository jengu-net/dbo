package cloud.jengu.dbo.sample;

/**
 * What the store said.
 *
 * <p>Carried whole rather than unwrapped into a happy path, because half of
 * what a story asserts is a refusal — a write the membrane would not take, a
 * search parameter refused rather than ignored, a version that has moved. A
 * surface that threw on anything but 200 would make those scenes unwritable
 * and would be lying about what an integrator's code sees.
 *
 * @param status what the store answered
 * @param body   what it said, as it said it
 * @param at     the Location it named, when it made something
 * @param etag   the version it is at, when it said so
 */
public record Answer(int status, String body, String at, String etag) {

    /** The id the store assigned, read from where it said the record is. */
    public String id() {
        if (at == null) {
            throw new IllegalStateException("nothing was created: " + status + " " + body);
        }
        return at.substring(at.lastIndexOf('/') + 1);
    }

    /**
     * One field of what it said, for the answers that are a small object
     * rather than a resource.
     *
     * <p>Deliberately shallow and deliberately not a JSON library. This
     * module is example code an integrator reads, and a dependency added so
     * that a sample could pretty-print would be the sample teaching a habit
     * it does not need. A story that wants to take an answer apart properly
     * should be asserting about a record instead.
     */
    public String field(String name) {
        int at = body.indexOf("\"" + name + "\"");
        if (at < 0) {
            throw new IllegalStateException("no " + name + " in " + body);
        }
        int from = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(from, body.indexOf('"', from));
    }

    /** Whether the store did it. */
    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
