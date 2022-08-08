import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
}

group = "me.techchrism"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")

        // As of Gradle 5.1, you can limit this to only those
        // dependencies you expect from it
        content {
            includeGroup("org.bukkit")
            includeGroup("org.spigotmc")
        }
    }

    maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
    maven(url = "https://oss.sonatype.org/content/repositories/central")
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("org.spigotmc:spigot-api:1.19-R0.1-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(project.properties)
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.jar {
    exclude("META-INF/**", "META-INF")
    archiveFileName.set("${project.name}.jar")
}

tasks.register("copyJarToPlugins") {
    doLast {
        copy {
            from("build/libs/${project.name}.jar")
            into(System.getenv("TestingPluginsDir"))
        }
    }
}

tasks.register("buildAndCopy") {
    dependsOn("jar")
    dependsOn("copyJarToPlugins")
    tasks.findByName("copyJarToPlugins")?.mustRunAfter("jar")
}
