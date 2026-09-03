# dbo-fleet — the fleet reader.
# Built from the installDist output: bin/ launcher + lib/ full classpath.
# The same glibc temurin base as the operator and the serving image; the
# reader reaches nothing but HTTP and has no native dependencies.
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.description="DBO fleet reader"

RUN groupadd --system dbo && \
    useradd --system --gid dbo --create-home --home-dir /home/dbo \
            --shell /usr/sbin/nologin dbo

WORKDIR /app
COPY --chown=dbo:dbo build/install/dbo-fleet/ /app/

USER dbo

ENTRYPOINT ["/app/bin/dbo-fleet"]
