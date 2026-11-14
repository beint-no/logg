import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    kotlin("jvm") version "2.5.0-Beta2" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("me.champeau.jmh") version "0.7.3" apply false
}
allprojects { group = "no.beint.logg"; version = "0.1.0" }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(27)
        compilerOptions { allWarningsAsErrors.set(true) }
    }
    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:6.1.3"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.jetbrains.kotlin:kotlin-test:2.5.0-Beta2")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped") }
        reports.junitXml.required.set(true)
    }
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
            publishToMavenCentral()
            signAllPublications()
            pom {
                name.set("Logg ${project.name}")
                description.set("Focused Kotlin/JDK structured logging with bounded delivery and explicit failure semantics.")
                url.set("https://github.com/beint-no/logg")
                inceptionYear.set("2026")
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
                developers { developer { id.set("beint-no"); name.set("Beint"); url.set("https://github.com/beint-no") } }
                scm { url.set("https://github.com/beint-no/logg"); connection.set("scm:git:https://github.com/beint-no/logg.git"); developerConnection.set("scm:git:ssh://git@github.com/beint-no/logg.git") }
            }
        }
    }
}
tasks.register("printReleaseVersion") { doLast { println(project.version) } }
