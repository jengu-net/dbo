# dbo-server — the serving distribution (dbo#19): standard Felix launcher
# + the production bundle set. Same base as the fleet's other images.
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.source="https://github.com/jengu-net/dbo"
LABEL org.opencontainers.image.description="DBO multi-tenant FHIR server (Felix distribution)"

RUN groupadd --system dbo && \
    useradd --system --gid dbo --create-home --home-dir /home/dbo \
            --shell /usr/sbin/nologin dbo

WORKDIR /app
# --chown on COPY (never RUN chown -R): the Felix cache/ is written under
# /app at runtime, so the tree must belong to the runtime user up front.
COPY --chown=dbo:dbo build/install/dbo-server/ /app/

USER dbo

EXPOSE 8090

ENTRYPOINT ["/app/bin/dbo-server"]
