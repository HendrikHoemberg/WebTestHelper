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
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl tzdata \
      libxcursor1 libgtk-3-0t64 libpangocairo-1.0-0 libcairo-gobject2 libgdk-pixbuf-2.0-0 \
 && apt-get clean && rm -rf /var/lib/apt/lists/* \
 && rm -rf /opt/playwright-cli
# Chromium is baked in above; without this, Playwright.create() downloads Firefox and WebKit
# (~200 MB, never used) into the container on first start.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

COPY --from=build /app/target/webtesthelper.jar /app/webtesthelper.jar

RUN groupadd --system appuser \
 && useradd --system --gid appuser --create-home --home-dir /home/appuser appuser \
 && mkdir -p /app /data \
 && chown -R appuser:appuser /app /data /ms-playwright

ENV WTH_DATA_DIR=/data
# A fixed heap, not a percentage of the container limit: Chromium runs in the same container
# and needs the rest. Override via JAVA_TOOL_OPTIONS in compose if a deployment needs more.
ENV JAVA_TOOL_OPTIONS="-Xmx1g"
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/webtesthelper.jar"]
