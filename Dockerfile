FROM gradle:8.4-jdk17 AS builder
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon :server:installDist

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 rummi && useradd --uid 10001 --gid rummi --no-create-home rummi && mkdir /data && chown rummi:rummi /data
COPY --from=builder /home/gradle/project/server/build/install/server /opt/rummiclub
USER 10001:10001
ENV DATABASE_PATH=/data/rummiclub.db PORT=8080
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["/opt/rummiclub/bin/server"]
