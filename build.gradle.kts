plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.sqlsplice"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2024.3.6")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "JDBC SQL Splicer"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.release = 17
        options.encoding = "UTF-8"
    }
    buildSearchableOptions {
        enabled = false
    }
}
