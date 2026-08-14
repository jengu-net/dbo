# dbo-operator — the tenant provisioning operator (dbo#18).
# Built from the installDist output: bin/ launcher + lib/ full classpath.
# Same base as jengu-runtime (glibc temurin) for fleet consistency; the
# operator itself has no native deps.
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.source="https://github.com/jengu-net/dbo"
LABEL org.opencontainers.image.description="DBO tenant provisioning operator"

RUN groupadd --system dbo && \
    useradd --system --gid dbo --create-home --home-dir /home/dbo \
            --shell /usr/sbin/nologin dbo

WORKDIR /app
COPY --chown=dbo:dbo build/install/dbo-operator/ /app/

USER dbo

ENTRYPOINT ["/app/bin/dbo-operator"]
