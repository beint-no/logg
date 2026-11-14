plugins { `java-library`; id("com.vanniktech.maven.publish"); id("me.champeau.jmh") }
dependencies { api("org.slf4j:slf4j-api:2.0.20") }
dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.2.0-RC2"))
    testImplementation("tools.jackson.core:jackson-databind")
}

tasks.test { systemProperty("logg.test.classpath", sourceSets.test.get().runtimeClasspath.asPath) }

dependencies { jmhImplementation("ch.qos.logback:logback-classic:1.6.6") }
kotlin.target.compilations.named("jmh") { associateWith(kotlin.target.compilations.getByName("main")) }
jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(2)
    resultFormat.set("JSON")
    profilers.set(listOf("gc"))
}
