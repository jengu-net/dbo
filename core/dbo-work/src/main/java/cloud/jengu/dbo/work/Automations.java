package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The automation switches this store holds, and the one in force for a step.
 *
 * <p>Read by both the place that decides who <em>would</em> run a step and the
 * place where a run is actually claimed. That is the point of it being a
 * reader rather than a list each of them builds: a switch an operator can see
 * in the console and a switch that stops a claim have to be the same switch,
 * or the console is a second opinion.
 */
public final class Automations {

    private final ObjectStore store;

    public Automations(ObjectStore store) {
        this.store = store;
    }

    /** Declares one, replacing any switch already standing at that scope. */
    public void declare(Automation automation) {
        String key = AutomationModel.keyFor(
                automation.process(), automation.step(), automation.scope());
        byte[] payload = ("{\"key\":\"" + key + "\",\"process\":\"" + automation.process()
                + "\",\"step\":\"" + automation.step()
                + "\",\"scope\":\"" + automation.scope().wire()
                + "\",\"on\":\"" + automation.on() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        Optional<StoredObject> standing = store.getByIdentifier(AutomationModel.TYPE,
                List.of(AutomationModel.key(key))).stream().findFirst();
        if (standing.isPresent()) {
            store.put(PutRequest.update(AutomationModel.TYPE, standing.get().id(),
                    standing.get().versionId(), payload));
        } else {
            store.putIfAbsent(IdentityRef.identifier(AutomationModel.KEY_SYSTEM, key),
                    PutRequest.create(AutomationModel.TYPE, payload));
        }
    }

    /** Every switch declared for this step, at whatever scope. */
    public List<Automation> forStep(String process, String step) {
        return store.select(Criteria.of(AutomationModel.TYPE)
                        .eq("process", EnvelopeValue.of(process))
                        .eq("step", EnvelopeValue.of(step))).stream()
                .map(Automations::read)
                .toList();
    }

    /**
     * Whether a step is automated for somebody standing at {@code scope}.
     *
     * <p>The chain consulted is baseline and that scope, and no more, because
     * no more is known here: nothing in this store records which zone an
     * organisation belongs to, so a zone's switch cannot be said to reach an
     * organisation's executor without inventing the containment. A switch
     * therefore governs a claim when it is declared at baseline — where it
     * governs everything — or at the claimant's own scope.
     *
     * <p>Absent any switch it is <b>on</b>: manual is the baseline for a step
     * nobody automated, and this answers about the switch rather than about
     * whether anybody automated anything.
     */
    public boolean automated(String process, String step, Scope scope) {
        Automation found = null;
        for (Automation declared : forStep(process, step)) {
            if (!declared.scope().equals(Scope.BASELINE) && !declared.scope().equals(scope)) {
                continue;
            }
            if (found == null || declared.scope().at().asLocalAs(found.scope().at())) {
                found = declared;
            }
        }
        return found == null || found.on();
    }

    private static Automation read(StoredObject stored) {
        Object declared = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        return new Automation(Json.str(declared, "process"), Json.str(declared, "step"),
                Scope.of(Json.str(declared, "scope")),
                Boolean.parseBoolean(Json.str(declared, "on")));
    }
}
