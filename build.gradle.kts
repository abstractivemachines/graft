import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

// Set -PlocalIdePath=/Applications/WebStorm.app to compile and verify against an installed IDE
// instead of downloading the platform version from gradle.properties.
val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
    // The IDE ships the Kotlin stdlib, so it is compile-only here (kotlin.stdlib.default.dependency=false in gradle.properties).
    compileOnly(kotlin("stdlib"))
    testCompileOnly(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform dependencies -> https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            webstorm(providers.gradleProperty("platformVersion"))
        }

        // The bundled MCP Server plugin owns the com.intellij.mcpServer.mcpToolset extension point.
        bundledPlugins("com.intellij.mcpServer")

        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
}

// Changelog -> https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Rendered eagerly: a lazy provider here would capture the build script (and so the Project) and break the configuration cache.
val changeNotesHtml: String = with(changelog) {
    renderItem(
        (getOrNull(providers.gradleProperty("pluginVersion").get()) ?: getUnreleased())
            .withHeader(false)
            .withEmptySections(false),
        Changelog.OutputType.HTML,
    )
}

// Plugin configuration -> https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // The plugin description is the README section between the two marker comments.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            val lines = readme.lines()
            require(lines.containsAll(listOf(start, end))) { "Plugin description section not found in README.md: $start ... $end" }
            markdownToHTML(lines.subList(lines.indexOf(start) + 1, lines.indexOf(end)).joinToString("\n"))
        }

        changeNotes = changeNotesHtml

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }

        vendor {
            name = "Abstractive Machines"
            url = "https://github.com/abstractivemachines"
        }
    }

    pluginVerification {
        ides {
            if (localIdePath.isPresent) {
                local(file(localIdePath.get()))
            } else {
                recommended()
            }
        }
    }

    // Marketplace upload for versions after the first, manual one: ./gradlew publishPlugin with PUBLISH_TOKEN set.
    // A pre-release version such as 0.2.0-beta.1 goes to the "beta" channel; a plain version goes to the default channel.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter("-", "").substringBefore(".").ifEmpty { "default" })
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
