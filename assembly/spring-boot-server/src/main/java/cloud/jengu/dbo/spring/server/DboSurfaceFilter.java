package cloud.jengu.dbo.spring.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Where a request becomes the store's, or carries on being the
 * application's.
 *
 * <p>Every request is offered to the surfaces. One that lands on a path a
 * tenant has a door at is answered there; everything else goes down the chain
 * untouched, which is what lets an application's own controllers and a
 * tenant's surfaces share one port without either knowing about the other.
 *
 * <p><b>Before the application's security, deliberately.</b> A tenant's doors
 * are guarded by that tenant's own authority: only bearer tokens verifying
 * against its keys pass, and a cross-tenant token is indistinguishable from
 * garbage. An application's security chain placed in front would refuse those
 * callers before the tenant ever saw them — and it would be a second answer
 * to who may read somebody's records, which is the one question this store
 * does not share.
 *
 * <p>An application that wants its OWN endpoints secured by the same tokens
 * its tenants issue gets that the other way round, from the tenant authority
 * as an authentication provider. That direction adds an answer; this one
 * would replace it.
 */
public final class DboSurfaceFilter extends OncePerRequestFilter {

    private final SpringHttpServer surfaces;

    public DboSurfaceFilter(SpringHttpServer surfaces) {
        this.surfaces = surfaces;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (surfaces.serve(request, response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Never skipped.
     *
     * <p>A tenant's paths appear and disappear as tenants come up and go
     * away, so there is no pattern to register against that stays true. The
     * cost of asking is one lookup in a sorted map, against the cost of a
     * tenant brought up after startup being unreachable.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
