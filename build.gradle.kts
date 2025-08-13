plugins {
    kotlin("jvm") version "2.1.20"
    `maven-publish`
}

group = "com.tellusr"
version = "1.0.7-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    val serialization_version = "1.8.1"
    val coroutines_version = "1.10.2"
    val lucene_version = "10.2.1"
    val slf4j_version = "2.0.12"
    val logback_version = "1.5.18"

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version")

    implementation("org.apache.lucene:lucene-core:$lucene_version")
    implementation("org.apache.lucene:lucene-queryparser:$lucene_version")
    implementation("org.apache.lucene:lucene-queries:$lucene_version")
    implementation("org.apache.lucene:lucene-suggest:$lucene_version")
    implementation("org.apache.lucene:lucene-analysis-common:$lucene_version")
    implementation("org.apache.lucene:lucene-backward-codecs:$lucene_version")

    implementation("org.slf4j:slf4j-api:$slf4j_version")

    testImplementation("ch.qos.logback:logback-classic:${logback_version}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${coroutines_version}")
    testImplementation(kotlin("test"))
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.test {
    useJUnitPlatform()
    // For test-tasken
    jvmArgs(
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED"
    )
    testLogging.showStandardStreams = true
}
kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId = group as String
            artifactId = "tellusr-searchindex"
            artifact(tasks.register("sourcesJar", Jar::class) {
                from(sourceSets["main"].allSource)
                archiveClassifier.set("sources")
            })
            version = version as String
        }
    }
    repositories {
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/tellusr/framework")
            credentials {
                username = project.findProperty("gpr.user")?.toString()
                    ?: System.getenv("GITHUB_USERNAME")
                            ?: System.getenv("GH_USERNAME")
                password = project.findProperty("gpr.key")?.toString()
                    ?: System.getenv("GITHUB_TOKEN")
                            ?: System.getenv("GH_TOKEN")
            }
        }
    }
}
