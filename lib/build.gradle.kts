plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    signing
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test {
    useJUnitPlatform()
}

val pomName = findProperty("POM_NAME") as String
val pomDescription = findProperty("POM_DESCRIPTION") as String
val pomUrl = findProperty("POM_URL") as String
val pomScmUrl = findProperty("POM_SCM_URL") as String
val pomScmConnection = findProperty("POM_SCM_CONNECTION") as String
val pomScmDevConnection = findProperty("POM_SCM_DEV_CONNECTION") as String
val pomLicenseName = findProperty("POM_LICENSE_NAME") as String
val pomLicenseUrl = findProperty("POM_LICENSE_URL") as String

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "tcpduplex"
            version = project.version.toString()

            pom {
                name.set(pomName)
                description.set(pomDescription)
                url.set(pomUrl)
                licenses {
                    license {
                        name.set(pomLicenseName)
                        url.set(pomLicenseUrl)
                    }
                }
                developers {
                    developer {
                        id.set("hdmain")
                        name.set("hdmain")
                    }
                }
                scm {
                    url.set(pomScmUrl)
                    connection.set(pomScmConnection)
                    developerConnection.set(pomScmDevConnection)
                }
            }
        }
    }
}

signing {
    val signingKey = findProperty("SIGNING_KEY") as String?
    val signingPassword = findProperty("SIGNING_PASSWORD") as String?
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
