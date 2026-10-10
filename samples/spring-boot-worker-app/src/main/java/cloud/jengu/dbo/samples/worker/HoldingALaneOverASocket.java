package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.embedded.DboRegistrar;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.spring.worker.DboWorkerProperties;
import cloud.jengu.dbo.stream.StreamLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * This worker's lane into one tenant, held over the clinic's WebSocket.
 *
 * <p>The store's stream protocol, carried by {@link AskingOverASocket}: the
 * lane is the store's {@code StreamLane}, which signs every ask with the key
 * this worker enrolled and opens only what is sealed to it, and the socket
 * only moves the text. So the enrolment is the substrate profile's — the same
 * minted keys, handed over the same way — and what this profile needs beyond
 * it is where the socket is, instead of where the substrate is.
 *
 * <p>Put on the container's whiteboard as any lane is, so the runner holds it
 * beside the steps this application registered and heartbeats over it on
 * every cycle, and cannot tell it from a lane over HTTP or the substrate.
 */
@Component
@Profile("websocket")
public final class HoldingALaneOverASocket implements SmartLifecycle {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("dbo.sample.socket");

    private final EmbeddedRuntime container;
    private final DboWorkerProperties worker;
    private final Socket socket;
    private StreamLane lane;
    private DboRegistrar.Registration holding;

    HoldingALaneOverASocket(EmbeddedRuntime container, DboWorkerProperties worker,
            @Value("${sample.socket.url}") URI url,
            @Value("${sample.socket.tenant}") String tenant,
            @Value("${sample.socket.participant}") String participant,
            @Value("${sample.socket.provider}") String provider,
            @Value("${sample.socket.sealing-key}") String sealingKey,
            @Value("${sample.socket.signing-key}") String signingKey) {
        this.container = container;
        this.worker = worker;
        this.socket = new Socket(url, tenant, participant, provider, sealingKey, signingKey);
    }

    /** Where the socket is, whose lane it carries, and the private halves this worker minted. */
    private record Socket(URI url, String tenant, String participant, String provider,
            String sealingKey, String signingKey) {
    }

    // --8<-- [start:holding]
    /**
     * A lane into the tenant given, over the socket at {@code url}, for a
     * participant holding the private halves of the keys it enrolled with.
     */
    public static StreamLane over(URI url, String tenant, String participant,
            Executor identity, PrivateKey sealing, PrivateKey signing) {
        return StreamLane.holding(new AskingOverASocket(url), tenant, participant, identity,
                sealing, signing);
    }

    @Override
    public synchronized void start() {
        // The worker's own name on every run it closes, as over HTTP; the
        // participant is only whose key signs the asks.
        Executor identity = new Executor(worker.getIdentity().getName(),
                worker.getIdentity().getVersion(), socket.provider(), Scope.BASELINE);
        lane = over(socket.url(), socket.tenant(), socket.participant(), identity,
                privateKey("X25519", socket.sealingKey()),
                privateKey("Ed25519", socket.signingKey()));
        holding = container.registrar().register(Lane.class, lane,
                Map.of("dbo.tenant", socket.tenant(), "dbo.lane.carrier", "websocket"));
        LOG.info("holding a lane over the socket: tenant={} url={}", socket.tenant(),
                socket.url());
    }
    // --8<-- [end:holding]

    @Override
    public synchronized void stop() {
        // The lane off the whiteboard first, so the runner stops being offered
        // work before the socket under it closes.
        if (holding != null) {
            holding.close();
            holding = null;
        }
        if (lane != null) {
            lane.close();
            lane = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return holding != null;
    }

    /**
     * After the worker has registered its steps: a lane offered before them
     * would be polled for work this application cannot yet perform.
     */
    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }

    private static PrivateKey privateKey(String algorithm, String base64) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (RuntimeException | java.security.GeneralSecurityException unreadable) {
            // Never the value: it is a private key.
            throw new IllegalStateException("an enrolment key is not a base64 PKCS#8 "
                    + algorithm + " private key; mint them with the mintEnrolment task",
                    unreadable);
        }
    }
}
