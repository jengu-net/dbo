package cloud.jengu.dbo.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What INFO means here is a claim about this runtime's own events, and a
 * library that announces every connection it opens or every parser context it
 * builds contradicts it while appearing to obey it.
 */
class ForeignLoggersAreQuietTest {

    @Test
    @DisplayName("this runtime's own loggers speak at the configured level")
    void oursSpeakAtTheConfiguredLevel() {
        assertEquals(DboLogging.Level.INFO, DboLogging.levelFor("cloud.jengu.dbo.tenant"),
                "the default level stopped applying to this product's own loggers");
        assertTrue(DboLogging.enabled("cloud.jengu.dbo.tenant", DboLogging.Level.INFO));
    }

    @Test
    @DisplayName("a library's idea of news is not this runtime's, so it is warnings only")
    void foreignLoggersAreWarningsOnly() {
        // The three that were actually in the stream: the container, the FHIR
        // stack building a context per tenant, the pool naming every connection.
        for (String foreign : new String[] {
                "org.osgi.framework", "ca.uhn.fhir.context.FhirContext",
                "com.zaxxer.hikari.pool.HikariPool", "org.apache.karaf.features.core"}) {
            assertEquals(DboLogging.Level.WARN, DboLogging.levelFor(foreign), foreign);
            assertFalse(DboLogging.enabled(foreign, DboLogging.Level.INFO),
                    foreign + " is announcing routine work as though it were this store's");
            assertTrue(DboLogging.enabled(foreign, DboLogging.Level.WARN),
                    foreign + " has been silenced, not quietened — a warning must still arrive");
        }
    }

    @Test
    @DisplayName("a name that merely resembles ours is not ours")
    void aSimilarNameIsNotOurs() {
        assertEquals(DboLogging.Level.WARN, DboLogging.levelFor("cloud.jenguish.other"));
    }
}
