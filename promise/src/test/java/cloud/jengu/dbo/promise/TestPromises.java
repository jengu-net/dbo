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
    };

    private final String text;

    TestPromises(String text) {
        this.text = text;
    }

    @Override
    public String text() {
        return text;
    }
}
