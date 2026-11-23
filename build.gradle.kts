plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.kover)
}

repositories {
    mavenCentral()
}

kotlin {
    // Java 17 bytecode, so Android apps can use the library too.
    jvmToolchain(17)
    // Everything public is public on purpose: the compiler asks for a visibility on each declaration.
    explicitApi()
}

java {
    withSourcesJar()
}

dependencies {
    // No engine: requests go through the HttpClient the app passes in, or one built on the engine
    // the app has on its classpath (OkHttp on Android, Apache5, CIO, ...).
    api(libs.ktor.client.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // A real engine, for a read-timeout test, and OkHttp's header check, which UserAgentRule must agree with.
    testImplementation(libs.ktor.client.okhttp)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "newznab-kotlin"
                description = "A Kotlin client for Newznab and Torznab indexers, with a DSL for searches"
                licenses {
                    license {
                        name = "PolyForm Noncommercial License 1.0.0"
                        url = "https://polyformproject.org/licenses/noncommercial/1.0.0"
                    }
                }
            }
        }
    }
}

// The licence goes into the jars in a folder of its own, so it doesn't clash with another library's.
tasks.withType<Jar>().configureEach {
    into("META-INF/${project.name}") {
        from("LICENSE.md")
    }
}
