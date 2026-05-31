plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = findProperty("group") as String
    version = findProperty("version") as String

    repositories {
        mavenCentral()
    }
}
