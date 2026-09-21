package cloud.jengu.dbo.sample;

/**
 * What the jurisdiction's own software does when a vocabulary moves on.
 *
 * <p>The same verb as an admission and a different outcome, because the type
 * declares a different identity. A code system is identified by its canonical
 * url, so writing one twice replaces it rather than conflicting: definitions
 * arrive repeatedly, from packages and from upstream zones, and are expected
 * to move forward. A person is not, which is why admitting one twice is
 * refused.
 */
public final class Publishing {

    private final Surface zone;

    public Publishing(Surface zone) {
        this.zone = zone;
    }

    /** Publish a vocabulary at its canonical url, however many times. */
    public Answer publish(String canonical, String... codesAndDisplays) {
        StringBuilder concepts = new StringBuilder();
        for (int at = 0; at + 1 < codesAndDisplays.length; at += 2) {
            concepts.append(concepts.isEmpty() ? "" : ",")
                    .append("{\"code\":\"").append(codesAndDisplays[at])
                    .append("\",\"display\":\"").append(codesAndDisplays[at + 1]).append("\"}");
        }
        return zone.write("CodeSystem", """
                {"resourceType":"CodeSystem","url":"%s","version":"1",
                 "status":"active","content":"complete","concept":[%s]}"""
                .formatted(canonical, concepts));
    }
}
