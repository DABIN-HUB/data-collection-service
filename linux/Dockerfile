# Linux 发行镜像只接收已在干净源码上构建并核验的可执行 JAR。
FROM eclipse-temurin:17-jre-jammy
ARG SOURCE_SHA
ARG JAR_SHA256
ARG RELEASE_VERSION
LABEL org.opencontainers.image.source="https://github.com/DABIN-HUB/data-collection-service" \
      org.opencontainers.image.revision="${SOURCE_SHA}" \
      org.opencontainers.image.version="${RELEASE_VERSION}" \
      org.opencontainers.image.description="WangbinCollector Linux 服务"
WORKDIR /opt/wangbin-collector
COPY app.jar app.jar
RUN printf '%s  app.jar\n' "$JAR_SHA256" | sha256sum -c - && \
    groupadd --gid 10001 wbcollector && \
    useradd --uid 10001 --gid 10001 --home-dir /opt/wangbin-collector --no-create-home wbcollector && \
    mkdir -p /opt/wangbin-collector/logs && \
    chown 10001:10001 /opt/wangbin-collector/logs
USER 10001:10001
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/opt/wangbin-collector/app.jar"]
