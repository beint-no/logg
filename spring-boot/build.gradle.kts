plugins { `java-library`; id("com.vanniktech.maven.publish") }
dependencies {
    api(project(":core"))
    implementation(kotlin("reflect"))
    compileOnly("org.springframework.boot:spring-boot:4.2.0-RC2")
    compileOnly("org.springframework:spring-core:7.1.0-RC2")
    testImplementation("org.springframework.boot:spring-boot:4.2.0-RC2")
    testImplementation("org.springframework:spring-core:7.1.0-RC2")
}
