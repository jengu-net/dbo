package cloud.jengu.dbo.runner.transport;

/** Where a bearer token becomes a reach. The tenant's authority implements it. */
@FunctionalInterface
public interface Grants {

    /** @param authorizationHeader the header as the caller sent it, or null */
    Access of(String authorizationHeader);
}
