plugins {
    id("java")
    id("java-library")
    id("maven-publish")
    id("jacoco")
    id("name.remal.jacoco-to-cobertura") version "2.0.4"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.example"
version = "1.3.0"

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    pitest("org.pitest:pitest-history-plugin:0.0.1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
    }
}

pitest {
    pitestVersion.set("1.30.0")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(setOf("org.example.*"))
    targetTests.set(setOf("org.example.*"))
    timestampedReports.set(false)
    outputFormats.set(setOf("HTML", "XML"))
    enableDefaultIncrementalAnalysis.set(true)
    historyInputLocation.set(layout.buildDirectory.file("pitHistory.txt"))
    historyOutputLocation.set(layout.buildDirectory.file("pitHistory.txt"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Single Factory Caller")
                description.set("A thread-safe singleton factory caller library for Java applications")
                url.set("https://github.com/jahnog/JavaSimpleLazyCache")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("example")
                        name.set("Content Lab Study")
                    }
                }
            }
        }
    }
}
