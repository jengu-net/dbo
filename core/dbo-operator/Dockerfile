# dbo-operator — the tenant provisioning operator.
# Built from the installDist output: bin/ launcher + lib/ full classpath.
# A glibc temurin base, matching the serving image; the operator itself has
# no native dependencies.
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.description="DBO tenant provisioning operator"

RUN groupadd --system dbo && \
    useradd --system --gid dbo --create-home --home-dir /home/dbo \
            --shell /usr/sbin/nologin dbo

WORKDIR /app
COPY --chown=dbo:dbo build/install/dbo-operator/ /app/

USER dbo

ENTRYPOINT ["/app/bin/dbo-operator"]
