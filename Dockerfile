# ---------------------------------------------
# Build stage: compile the Spring Boot fat jar
# ---------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /workspace

# 先复制父 POM 和各模块 POM，提高 Maven 依赖下载缓存命中率。
COPY pom.xml ./pom.xml
COPY collector-common/pom.xml collector-common/pom.xml
COPY collector-protocol-spi/pom.xml collector-protocol-spi/pom.xml
COPY collector-runtime/pom.xml collector-runtime/pom.xml
COPY collector-telemetry/pom.xml collector-telemetry/pom.xml
COPY collector-protocol-iot/pom.xml collector-protocol-iot/pom.xml
COPY collector-protocol-bacnet/pom.xml collector-protocol-bacnet/pom.xml
COPY collector-protocol-plc/pom.xml collector-protocol-plc/pom.xml
COPY collector-protocol-modbus/pom.xml collector-protocol-modbus/pom.xml
COPY collector-protocol-opc/pom.xml collector-protocol-opc/pom.xml
COPY collector-protocol-iec/pom.xml collector-protocol-iec/pom.xml
COPY collector-cloud/pom.xml collector-cloud/pom.xml
COPY collector-storage/pom.xml collector-storage/pom.xml
COPY collector-monitor/pom.xml collector-monitor/pom.xml
COPY collector-application/pom.xml collector-application/pom.xml
COPY collector-web/pom.xml collector-web/pom.xml
COPY collector-boot/pom.xml collector-boot/pom.xml
RUN mvn -B -ntp -pl collector-boot -am dependency:go-offline

# 复制实际源码和资源，使用 Maven reactor 构建最终 boot 模块。
COPY collector-common collector-common
COPY collector-protocol-spi collector-protocol-spi
COPY collector-runtime collector-runtime
COPY collector-telemetry collector-telemetry
COPY collector-protocol-iot collector-protocol-iot
COPY collector-protocol-bacnet collector-protocol-bacnet
COPY collector-protocol-plc collector-protocol-plc
COPY collector-protocol-modbus collector-protocol-modbus
COPY collector-protocol-opc collector-protocol-opc
COPY collector-protocol-iec collector-protocol-iec
COPY collector-cloud collector-cloud
COPY collector-storage collector-storage
COPY collector-monitor collector-monitor
COPY collector-application collector-application
COPY collector-web collector-web
COPY collector-boot collector-boot
RUN mvn -B -ntp -pl collector-boot -am clean package -DskipTests && \
    mkdir -p /workspace/docker-artifact && \
    find collector-boot/target -maxdepth 1 -type f \
      -name 'data-collection-service-*.jar' \
      ! -name '*.original' \
      ! -name 'original-*.jar' \
      -exec cp {} /workspace/docker-artifact/app.jar \; && \
    test -f /workspace/docker-artifact/app.jar

# ---------------------------------------------
# Runtime stage: lightweight JRE image
# ---------------------------------------------
FROM eclipse-temurin:17-jre-jammy
ENV APP_HOME=/opt/app \
    JAVA_OPTS=""
WORKDIR ${APP_HOME}
RUN groupadd --system spring && useradd --system --gid spring --home ${APP_HOME} spring
COPY --from=builder /workspace/docker-artifact/app.jar app.jar
RUN chown -R spring:spring ${APP_HOME}
USER spring
EXPOSE 9090
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /opt/app/app.jar"]
