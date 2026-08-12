// 버전은 빌드 시점 최신 패치로 조정하세요 (Spring Boot 4.0.x / Kotlin 2.1.x 기준).
plugins {
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"
}

group = "com.growant"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

tasks.withType<Test> { useJUnitPlatform() }

fun Test.useProjectTestRuntime() {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named<Test>("test") {
    // 성능 측정은 환경 편차가 크므로 일반 회귀 테스트에서 실행하지 않는다.
    exclude("**/*Benchmark*.class")
}

tasks.register<Test>("unitTest") {
    group = "verification"
    description = "Runs unit and web-layer tests whose class names end with Test"
    useProjectTestRuntime()
    filter { includeTestsMatching("*Test") }
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs PostgreSQL integration tests whose class names end with IT"
    useProjectTestRuntime()
    filter { includeTestsMatching("*IT") }
}

val marketBenchmarkTicksPerMinute = providers.gradleProperty("marketBenchmarkTicksPerMinute").orElse("1000")
val marketBenchmarkWarmupRounds = providers.gradleProperty("marketBenchmarkWarmupRounds").orElse("3")
val marketBenchmarkMeasurementRounds = providers.gradleProperty("marketBenchmarkMeasurementRounds").orElse("7")
val marketBenchmarkRunId = providers.environmentVariable("RUN_ID").orNull?.also { runId ->
    require(runId.matches(Regex("[A-Za-z0-9._-]+"))) {
        "RUN_ID may contain only letters, numbers, '.', '_' and '-'"
    }
}
val marketBenchmarkReportDirectory = if (marketBenchmarkRunId == null) {
    "reports/market-data"
} else {
    "reports/market-data/$marketBenchmarkRunId"
}

tasks.register<JavaExec>("benchmarkMinuteCandles") {
    val reportFile = layout.buildDirectory.file(
        "$marketBenchmarkReportDirectory/minute-candle-aggregation-benchmark.md",
    )

    group = "verification"
    description = "Benchmarks replay tick aggregation and writes a Markdown report"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.growant.market.benchmark.MinuteCandleAggregationBenchmark")
    args(
        "--ticks-per-minute=${marketBenchmarkTicksPerMinute.get()}",
        "--warmup-rounds=${marketBenchmarkWarmupRounds.get()}",
        "--measurement-rounds=${marketBenchmarkMeasurementRounds.get()}",
        "--output=${reportFile.get().asFile}",
    )
    marketBenchmarkRunId?.let { args("--run-id=$it") }
    outputs.file(reportFile)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("benchmarkMinuteCandleStorage") {
    val reportFile = layout.buildDirectory.file(
        "$marketBenchmarkReportDirectory/minute-candle-storage-benchmark.md",
    )

    group = "verification"
    description = "Benchmarks minute-candle PostgreSQL storage and writes a Markdown report"
    useProjectTestRuntime()
    filter {
        includeTestsMatching("com.growant.market.candle.persistence.MinuteCandleStorageBenchmark")
    }
    systemProperty("marketBenchmarkReport", reportFile.get().asFile.absolutePath)
    marketBenchmarkRunId?.let { systemProperty("marketBenchmarkRunId", it) }
    outputs.file(reportFile)
    outputs.upToDateWhen { false }
}
