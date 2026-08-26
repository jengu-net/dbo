package cloud.jengu.dbo.promise;

/** Fixture: a promise catalogue, one constant plain and one with a body. */
@Catalogue(namespace = "REQ-TEST")
enum TestPromises implements Promise {

    PLAIN_PROMISE("a plain promise"),

    // The trap fixture: a constant WITH A BODY is an anonymous subclass of
    // the enum, and getClass() on it does not carry @Catalogue.
    BODIED_PROMISE("overridden below") {
        @Override
        public String text() {
            return "a promise whose constant has a body";
        }
    },

    /** Assured by recorded review — no executable test exists. */
    REVIEWED_PROMISE("a promise a policy file keeps") {
        @Override
        public String assurance() {
            return "asserted by quarterly key-custody review";
        }
    },

    /** Declared, cited by nothing: intent, parked where it is visible. */
    QUIET_PROMISE("a promise nobody has proven yet");

    private final String text;

    TestPromises(String text) {
        this.text = text;
    }

    @Override
    public String text() {
        return text;
    }
}
