package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.work.WorkModel;

import java.util.ArrayList;
import java.util.List;

/** What a tenant's store knows beyond its personality. */
final class Registrations {

    private Registrations() {
    }

    /**
     * A store that can hold run records, which the tenant runtime gives every
     * tenant: subscription delivery records an exhausted attempt as a run, so a
     * store without the type would fail at the moment somebody needs the record
     * most.
     */
    static List<TypeRegistration> withRuns(List<TypeRegistration> registrations) {
        List<TypeRegistration> all = new ArrayList<>(registrations);
        all.addAll(WorkModel.registrations());
        return all;
    }
}
