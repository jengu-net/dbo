package cloud.jengu.dbo.runner.transport;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Executor;

/**
 * The tenant's own lanes. Given who is working and what they may reach, the
 * tenant builds the in-process lane it would have built anyway — so a
 * transport adds a wire and never a second implementation of the
 * participation protocol.
 */
@FunctionalInterface
public interface Lanes {
    Lane laneFor(String participant, Executor identity, Lane.Entitlement entitlement);
}
