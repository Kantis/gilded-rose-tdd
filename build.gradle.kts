import io.github.raphiz.dbbuild.FlywayMigrateTask

plugins {
    java
    alias(libs.plugins.kotlin.jvm)

    // Flyway does not support configuration caching..
    // https://github.com/flyway/flyway/issues/3550
//    alias(libs.plugins.flyway)

    // jOOQ now provides its own Gradle plugin. It seems to be maintained by the same guy who made the open-source
    // variant at first?
    id("org.jooq.jooq-codegen-gradle") version "3.19.15"
//    alias(libs.plugins.jooq)

    alias(libs.plugins.versions)
    alias(libs.plugins.taskinfo)
}

group = "com.gildedrose"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

val testJdbcUrl = providers.environmentVariable("JDBC_URL").orElse("jdbc:postgresql://localhost:5433/gilded-rose").get()
val databaseUsername = providers.environmentVariable("DB_USERNAME").orElse("gilded").get()
val databasePassword = providers.environmentVariable("DB_PASSWORD").orElse("rose").get()

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.result4k)
    implementation(libs.slf4j)

    implementation(platform(libs.http4k.bom))
    implementation(libs.http4k.core)
    implementation(libs.http4k.server.undertow)
    implementation(libs.http4k.config)
    implementation(libs.http4k.client.apache)

    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.module.parameter.names)
    implementation(libs.jackson.datatype.jdk8)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    implementation(libs.jooq)
    implementation(libs.kotlinx.html)

    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testImplementation(libs.junit.engine)

    testImplementation(kotlin("test"))
    testImplementation(libs.strikt)

    testImplementation(libs.playwright)

    testImplementation(libs.http4k.testing.approval)
    testImplementation(libs.http4k.testing.hamkrest)
    testImplementation(libs.http4k.testing.strikt)

    jooqCodegen(libs.postgresql)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// In order to support caching, I made a custom Gradle task. Less than ideal.
// The source for this task is in `buildSrc/src/main/kotlin/FlywayMigrateTask.kt`
val flywayMigrate by tasks.registering(FlywayMigrateTask::class) {
    url.convention(testJdbcUrl)
    username.convention(databaseUsername)
    password.convention(databasePassword)
    migrationsLocation = layout.projectDirectory.dir("src/main/resources/db/migration")
}

tasks.withType<org.jooq.codegen.gradle.CodegenTask>().configureEach {
    caching.set(true)
    // This lets Gradle know that jOOQ codegen depends on the Flyway migrations
    // If flyway migrations are up-to-date, then jOOQ codegen will not run
    inputs.files(flywayMigrate.map { it.cacheKey })
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val jooqGen = tasks.named("jooqCodegen")
    inputs.files(jooqGen.map { it.outputs })
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
        suppressWarnings = true // TODO 2024-11-13 DMCG remove me
    }
}

jooq {
    configuration {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
            driver = "org.postgresql.Driver"
            url = testJdbcUrl
            user = databaseUsername
            password = databasePassword
            properties.add(org.jooq.meta.jaxb.Property().withKey("ssl").withValue("false"))
        }
        generator {
            name = "org.jooq.codegen.DefaultGenerator"
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
                forcedTypes.add((org.jooq.meta.jaxb.ForcedType()) {
                    name = "instant"
                    includeExpression = ".*"
                    includeTypes = "TIMESTAMPTZ"
                })
            }
            generate {
                isDeprecated = false
                isRecords = true
                isImmutablePojos = true
                isFluentSetters = true
            }
            target {
                packageName = "com.gildedrose.db"
                directory = "build/generated-src/jooq/main" // default (can be omitted)
            }
            strategy {
                name = "org.jooq.codegen.DefaultGeneratorStrategy"
            }
        }
    }
}

@Suppress("UnstableApiUsage") // XMLAppendable
private operator fun <T : org.jooq.util.jaxb.tools.XMLAppendable> T.invoke(block: T.() -> Unit) = apply(block)

