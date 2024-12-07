package io.github.raphiz.dbbuild

import org.flywaydb.core.Flyway
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

abstract class FlywayMigrateTask : DefaultTask() {

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val username: Property<String>

    @get:Input
    abstract val password: Property<String>

    @get:InputDirectory
    abstract var migrationsLocation: Directory

    @get:OutputFile
    val cacheKey: RegularFileProperty = project.objects.fileProperty();

    init {
        cacheKey.convention(project.layout.buildDirectory.file(".flyway/migrationCacheKey.txt"));

        outputs.cacheIf { true }
        outputs.upToDateWhen {
            // This will say we are up-to-date as long as migrations are unchanged between runs
            // TODO: What if the DB is nuked? We probably also need to check that the DB instance is unchanged
            // OTOH, even if that happens, the jooq schema would be up-to-date?
            val file = cacheKey.asFile.get()
            file.exists() && Files.readString(file.toPath()) == hashMigrations().toString()
        }
    }

    @TaskAction
    fun run() {
        val migrationResult = Flyway.configure()
            .dataSource(url.get(), username.get(), password.get())
            .locations("filesystem:${migrationsLocation.asFile}")
            .cleanOnValidationError(true)
            .load()
            .migrate()

        if (migrationResult.success) {
            cacheKey.asFile.get().writeText(hashMigrations().toString())
        }
    }

    private fun hashMigrations(): Map<String, Int> =
        migrationsLocation
            .asFile
            .listFiles()
            .associate { file ->
                // There's probably better ways to generate this hash..
                file.name to file.readLines().hashCode()
            }
}
