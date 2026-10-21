# ---- ビルド ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

# ---- 実行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/komorebi-server-*.jar app.jar
# 無料プランのメモリ(512MB)に収まるように控えめに
ENV JAVA_OPTS="-Xmx300m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
