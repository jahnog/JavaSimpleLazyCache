plugins {
    id("java")
    id("java-library")
    id("maven-publish")
}

group = "com.example"
version = "1.1.0"

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
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Single Factory Caller")
                description.set("A thread-safe singleton factory caller library for Java applications")
                url.set("https://github.com/contentlabstudy/java-simple-lazy-cache")

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
