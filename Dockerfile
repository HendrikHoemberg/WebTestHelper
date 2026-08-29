# syntax=docker/dockerfile:1

# Spec 16: JRE 25 + app + Chromium and its system libraries. The Chromium build
# is pinned to the Playwright version from pom.xml (playwright.version): the
# browser, the driver and the Java bindings are upgraded together or not at all.

# ---- build -----------------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/ src/
RUN mvn -q -B -DskipTests package
RUN PW_VERSION=$(mvn -q -B help:evaluate -Dexpression=playwright.version -DforceStdout) \
 && APP_VERSION=$(mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout) \
 && mkdir -p /opt/playwright-cli \
 && cp "/root/.m2/repository/com/microsoft/playwright/playwright/${PW_VERSION}/playwright-${PW_VERSION}.jar" /opt/playwright-cli/ \
 && cp "/root/.m2/repository/com/microsoft/playwright/driver/${PW_VERSION}/driver-${PW_VERSION}.jar" /opt/playwright-cli/ \
 && cp "/root/.m2/repository/com/microsoft/playwright/driver-bundle/${PW_VERSION}/driver-bundle-${PW_VERSION}.jar" /opt/playwright-cli/ \
 && mv "target/webtesthelper-${APP_VERSION}.jar" target/webtesthelper.jar

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
COPY --from=build /opt/playwright-cli/ /opt/playwright-cli/
RUN java -cp "/opt/playwright-cli/*" com.microsoft.playwright.CLI install chromium \
 && java -cp "/opt/playwright-cli/*" com.microsoft.playwright.CLI install-deps chromium \
 && apt-get install -y --no-install-recommends \
      libxcursor1 libgtk-3-0t64 libpangocairo-1.0-0 libcairo-gobject2 libgdk-pixbuf-2.0-0 \
 && apt-get clean && rm -rf /var/lib/apt/lists/* \
 && rm -rf /opt/playwright-cli

COPY --from=build /app/target/webtesthelper.jar /app/webtesthelper.jar

ENV WTH_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/webtesthelper.jar"]
