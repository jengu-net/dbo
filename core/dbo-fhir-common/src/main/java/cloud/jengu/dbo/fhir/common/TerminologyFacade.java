package cloud.jengu.dbo.fhir.common;

import java.util.Optional;

/** The terminology operations the REST layer exposes when wired. */
public interface TerminologyFacade {

    Optional<String> lookup(String system, String code);

    String validateCode(String system, String code);

    Optional<String> expand(String valueSetUrl, String filter, int offset, int count);
}
