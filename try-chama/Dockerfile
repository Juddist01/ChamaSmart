# ---- Stage 1: build the Play app ----
# Java 17 and sbt 1.11.7 match your machine and project/build.properties.
FROM eclipse-temurin:17-jdk AS build
ARG SBT_VERSION=1.11.7
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt
ENV PATH="/opt/sbt/bin:${PATH}"
WORKDIR /src
COPY . .
RUN sbt stage

# ---- Stage 2: small runtime image ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/universal/stage ./
EXPOSE 9000
# Starts the launcher script that "sbt stage" created (the one without .bat)
CMD ["sh", "-c", "exec $(ls bin/* | grep -v '\\.bat$' | head -n 1) -Dhttp.port=9000"]
