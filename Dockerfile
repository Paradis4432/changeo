FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

RUN groupadd --gid 10001 changeo \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin changeo \
    && install -d -m 0755 /app \
    && install -d -m 0700 -o 10001 -g 10001 /app/.runtime

WORKDIR /app
COPY --chmod=0444 target/changeo-0.1.0-SNAPSHOT.jar /app/changeo.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/changeo.jar"]
