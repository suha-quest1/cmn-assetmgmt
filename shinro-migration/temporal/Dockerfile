FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package
RUN mvn -B -q dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeScope=runtime

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /src/target/cmnassetmgmt-1.0.0.jar /app/app.jar
COPY --from=builder /src/target/dependency /app/dependency
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp \"/app/app.jar:/app/dependency/*\" com.shinro.migration.cmnassetmgmt.Worker"]
