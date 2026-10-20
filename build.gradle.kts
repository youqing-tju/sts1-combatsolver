plugins {
    java
}

group = "io.github.youqing"
version = "0.0.1"

repositories {
    mavenCentral()
}

// 第三方编译依赖：从游戏目录同步到 lib/（见 tools/sync-libs.ps1），不入库
dependencies {
    implementation(fileTree("lib") { include("*.jar") })

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 游戏运行在 JRE 8 上，产物必须是 class 版本 52
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("STS1CombatSolver.jar")
    manifest {
        attributes(
            "Implementation-Title" to "STS1 Combat Solver",
            "Implementation-Version" to project.version.toString()
        )
    }
}
