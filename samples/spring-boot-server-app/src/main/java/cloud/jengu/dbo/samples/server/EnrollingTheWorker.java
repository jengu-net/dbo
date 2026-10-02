package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.spring.server.DboTenantListener;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.worker.DboWorkerProperties;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The clinic's worker, made known to each tenant it performs for as that
 * tenant comes up.
 *
 * <p>A worker is somebody the tenant issued a credential to, and only a tenant
 * that exists can issue one. So this runs when a tenant is serving, and asks
 * that tenant's own authority — the same act, with the same rule, as any
 * operator registering a client.
 *
 * <p><b>Two kinds of credential, one per carrier.</b> A lane over HTTP signs in
 * with a client and a secret, and that client may act in work and in nothing
 * else: a token admitted at the step door is refused by the records door. A
 * lane over the deployment's own substrate carries no token at all; what the
 * tenant holds instead are the PUBLIC halves of the worker's keys, against
 * the participant name the worker enrolled under. The worker made the pair and
 * kept the private halves, and nothing here ever sees them.
 *
 * <p><b>Ensuring, not creating.</b> A tenant reaches this point at every start,
 * and ensuring says what the record should be — a client that already reads
 * so is left alone, and one whose secret or keys changed is brought up to date.
 */
@Component
@DboTenantListener(point = TenantPoint.SERVING)
@EnableConfigurationProperties(EnrollingTheWorker.Enrolment.class)
public final class EnrollingTheWorker implements TenantLifecycleListener {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.enrolment");

    private final DboTenants tenants;
    private final DboWorkerProperties lanes;
    private final Enrolment enrolment;

    EnrollingTheWorker(DboTenants tenants, DboWorkerProperties lanes, Enrolment enrolment) {
        this.tenants = tenants;
        this.lanes = lanes;
        this.enrolment = enrolment;
    }

    // --8<-- [start:reached]
    @Override
    public void reached(TenantPoint point, TenantFacts tenant) {
        String code = tenant.code();
        TenantAuthority authority = tenants.authority(code).orElse(null);
        if (authority == null) {
            return;
        }
        // The clients the worker's HTTP lanes sign in with: this application's
        // own lanes when the worker is embedded, and the same lanes a worker
        // in another JVM is configured with when it is not. A lane whose work
        // the substrate carries keeps its client when it names a base: that
        // is the credential this application asks the tenant for work with.
        for (DboWorkerProperties.Lane lane : lanes.getLanes()) {
            DboWorkerProperties.Token token = lane.getToken();
            if (!code.equals(lane.getTenant()) || lane.getBase() == null || token == null
                    || blank(token.getClientId()) || blank(token.getClientSecret())) {
                continue;
            }
            authority.ensureClient(token.getClientId(), token.getClientSecret(),
                    List.of(Scopes.WORK));
            LOG.info("worker enrolled: tenant={} client={}", code, token.getClientId());
        }
        // A worker beside the store, over its substrate, known by the public
        // halves of the keys it made.
        if (code.equals(enrolment.getTenant()) && !blank(enrolment.getParticipant())) {
            authority.ensureClient(enrolment.getParticipant(), enrolment.getSecret(),
                    List.of(Scopes.WORK),
                    ParticipantKey.parse(enrolment.getSealingKey()),
                    SigningKey.parse(enrolment.getSigningKey()));
            LOG.info("participant enrolled: tenant={} participant={}", code,
                    enrolment.getParticipant());
        }
    }
    // --8<-- [end:reached]

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * A worker that reaches a tenant over the deployment's substrate, as the
     * tenant is to know it: the name it enrolled under and the public halves
     * of its keys, each a JWK as the worker rendered it.
     */
    @ConfigurationProperties("clinic.enrolment")
    public static class Enrolment {

        private String tenant;
        private String participant;
        /** Never presented over the substrate; a client record needs one. */
        private String secret = "";
        private String sealingKey;
        private String signingKey;

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getParticipant() {
            return participant;
        }

        public void setParticipant(String participant) {
            this.participant = participant;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getSealingKey() {
            return sealingKey;
        }

        public void setSealingKey(String sealingKey) {
            this.sealingKey = sealingKey;
        }

        public String getSigningKey() {
            return signingKey;
        }

        public void setSigningKey(String signingKey) {
            this.signingKey = signingKey;
        }
    }
}
