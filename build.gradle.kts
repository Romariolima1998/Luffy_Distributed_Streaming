import java.nio.charset.StandardCharsets
import java.security.MessageDigest

plugins {
    application
    java
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "dev.luffy"
version = "0.1.0"
val javafxVersion = "21.0.5"
// vlcj 4.x e a linha estavel destinada ao VLC/libVLC 3.x. Nao usar vlcj 5.x
// experimental nem VLC 4 nightly durante esta migracao.
val vlcjVersion = "4.12.1"

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

javafx {
    version = javafxVersion
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.media", "javafx.swing")
}

val linuxJavafx by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/**
 * Runtime VLC distribuído com a imagem Windows. Por padrão reutiliza a cópia
 * oficial instalada na máquina de build; CI pode informar outra origem com
 * -PbundledVlcWindowsHome=<diretório-do-VLC>.
 */
val bundledVlcWindowsHome = providers.gradleProperty("bundledVlcWindowsHome")
    .map { file(it) }
    .orElse(providers.provider { file("C:/Program Files/VideoLAN/VLC") })
val bundledVlcWindowsOutput = layout.buildDirectory.dir("bundled-vlc/windows/vlc")

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.github.atomashpolskiy:bt-core:1.10")
    // Extensão oficial do bt-core para trackers HTTP/HTTPS; o autoLoadModules()
    // existente passa a atender magnets públicos que não usam somente UDP.
    implementation("com.github.atomashpolskiy:bt-http-tracker-client:1.10")
    implementation("com.github.atomashpolskiy:bt-dht:1.10")
    implementation("com.offbynull.portmapper:portmapper:2.0.6")
    // Backend libVLC isolado pela MediaPlayerBackend. O empacotamento Windows
    // inclui o runtime VLC 3.x e o descobre antes de qualquer instalação local.
    implementation("uk.co.caprica:vlcj:$vlcjVersion")
    // A superfície JavaFX é implementada no Luffy com CallbackVideoSurface e
    // PixelBuffer. Isso evita a incompatibilidade binária do vlcj-javafx
    // publicado com a assinatura de RenderCallback do vlcj 4.12.x.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    listOf("base", "graphics", "controls", "media", "swing").forEach { module ->
        add(linuxJavafx.name, "org.openjfx:javafx-$module:$javafxVersion:linux") {
            isTransitive = false
        }
    }
}

application { mainClass.set("dev.lufi.ui.LufiApplication") }
tasks.test { useJUnitPlatform() }
tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.encoding = "UTF-8" }

/**
 * Gera os tres artefatos oficiais do swarm Ola Luffy somente quando eles ainda
 * nao existem. Uma execucao posterior apenas valida os bytes versionados: ela
 * nunca os reescreve, para impedir que instalacoes recebam outro infoHash.
 */
tasks.register("generateOfficialBootstrapArtifacts") {
    group = "distribution"
    description = "Gera uma unica vez os artefatos BitTorrent oficiais do swarm Ola Luffy."
    doLast {
        val bootstrapDirectory = layout.projectDirectory.dir("src/main/resources/bootstrap").asFile
        val content = "Olá Luffy".toByteArray(StandardCharsets.UTF_8)
        val pieceHash = MessageDigest.getInstance("SHA-1").digest(content)
        val info = "d6:lengthi10e4:name13:ola-luffy.txt12:piece lengthi1048576e6:pieces20:"
            .toByteArray(StandardCharsets.US_ASCII) + pieceHash + byteArrayOf('e'.code.toByte())
        val infoHash = MessageDigest.getInstance("SHA-1").digest(info)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        check(infoHash == "08e3e48a8916ff0b0fdc04fa903977d5efa404c7") {
            "O gerador deterministico do swarm Ola Luffy produziu um infoHash inesperado: $infoHash"
        }
        val torrent = "d4:info".toByteArray(StandardCharsets.US_ASCII) + info + byteArrayOf('e'.code.toByte())
        val magnet = "magnet:?xt=urn:btih:$infoHash&dn=Ol%C3%A1+Luffy"
            .toByteArray(StandardCharsets.US_ASCII)

        fun createOnceOrValidate(fileName: String, expected: ByteArray) {
            val target = bootstrapDirectory.resolve(fileName)
            if (target.isFile) {
                check(target.readBytes().contentEquals(expected)) {
                    "O artefato oficial $fileName nao pode ser modificado. Restaure os bytes versionados."
                }
                return
            }
            check(!target.exists()) { "O caminho de artefato oficial nao e um arquivo: $target" }
            check(bootstrapDirectory.mkdirs() || bootstrapDirectory.isDirectory) {
                "Nao foi possivel criar $bootstrapDirectory"
            }
            target.writeBytes(expected)
        }

        createOnceOrValidate("ola-luffy.txt", content)
        createOnceOrValidate("ola-luffy.torrent", torrent)
        createOnceOrValidate("ola-luffy-magnet.txt", magnet)
    }
}

// Testes unitários ficam em src/test. Os testes entre duas máquinas reais só
// são compilados/executados pela tarefa explícita integrationTest.
val integrationTest by sourceSets.creating {
    java.srcDir("src/integrationTest/java")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output
    compileClasspath += configurations.testRuntimeClasspath.get()
    runtimeClasspath += output
    runtimeClasspath += compileClasspath
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Executa testes P2P reais somente com LUFFY_REAL_NETWORK_TESTS=true."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    onlyIf { providers.environmentVariable("LUFFY_REAL_NETWORK_TESTS").orNull == "true" }
}

tasks.register<Jar>("executableJar") {
    group = "distribution"
    description = "Gera um JAR executável do Luffy com as dependências incluídas."
    archiveBaseName.set("Luffy")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "dev.lufi.ui.LuffyLauncher" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
}

tasks.register<Jar>("linuxExecutableJar") {
    group = "distribution"
    description = "Gera o JAR executável do Luffy para Linux, com JavaFX nativo Linux."
    archiveBaseName.set("Luffy")
    archiveClassifier.set("linux")
    destinationDirectory.set(layout.buildDirectory.dir("linux/artifacts"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "dev.lufi.ui.LuffyLauncher" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath, linuxJavafx)
    from({
        configurations.runtimeClasspath.get()
            .filterNot { it.name.matches(Regex("javafx-.*-win\\.jar")) }
            .plus(linuxJavafx)
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
}

tasks.register<Sync>("prepareBundledVlcWindows") {
    group = "distribution"
    description = "Prepara o runtime nativo VLC 3.x que acompanha a distribuição Windows do Luffy."
    from(bundledVlcWindowsHome)
    into(bundledVlcWindowsOutput)
    // O desinstalador pertence à instalação de origem, não ao runtime do Luffy.
    exclude("uninstall.exe")
    doFirst {
        val source = bundledVlcWindowsHome.get()
        check(source.resolve("libvlc.dll").isFile && source.resolve("libvlccore.dll").isFile
                && source.resolve("plugins").isDirectory) {
            "Runtime VLC Windows não encontrado em $source. Instale VLC 3.x x64 na máquina de build " +
                    "ou informe -PbundledVlcWindowsHome=<diretório-do-VLC>."
        }
    }
    doLast {
        val runtime = bundledVlcWindowsOutput.get().asFile
        val cacheGenerator = runtime.resolve("vlc-cache-gen.exe")
        check(cacheGenerator.isFile) { "vlc-cache-gen.exe não foi copiado para o runtime integrado do Luffy." }
        exec {
            executable = cacheGenerator.absolutePath
            args(runtime.resolve("plugins").absolutePath)
        }
    }
}

tasks.register<Copy>("linuxDistribution") {
    group = "distribution"
    description = "Monta a pasta de distribuição Linux do Luffy."
    dependsOn("linuxExecutableJar")
    from(tasks.named<Jar>("linuxExecutableJar"))
    from("src/linux")
    into(layout.buildDirectory.dir("linux/Luffy"))
}

tasks.register<Zip>("linuxDistributionZip") {
    group = "distribution"
    description = "Gera o pacote ZIP da distribuição Linux do Luffy."
    dependsOn("linuxDistribution")
    archiveBaseName.set("Luffy")
    archiveClassifier.set("linux")
    destinationDirectory.set(layout.buildDirectory.dir("linux"))
    from(layout.buildDirectory.dir("linux/Luffy"))
}

tasks.register<Exec>("windowsAppImage") {
    group = "distribution"
    description = "Gera uma pasta Windows isolada com Luffy.exe para uma regra de firewall exclusiva do aplicativo."
    val executableJar = tasks.named<Jar>("executableJar")
    val appImageDestination = providers.gradleProperty("windowsAppImageDestination")
        .map { file(it) }
        .orElse(layout.buildDirectory.dir("windows").map { it.asFile })
    dependsOn(executableJar, "prepareBundledVlcWindows")
    inputs.file(executableJar.flatMap { it.archiveFile })
    inputs.dir(bundledVlcWindowsOutput)
    // Uma versao em uso no Windows nao pode ser apagada. A propriedade permite
    // publicar uma imagem nova sem interferir em um Luffy.exe aberto.
    doFirst { delete(appImageDestination.get().resolve("Luffy")) }
    val jpackage = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.file("bin/jpackage.exe").asFile
    executable = jpackage.absolutePath
    args(
        "--type", "app-image",
        "--dest", appImageDestination.get().absolutePath,
        "--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        "--name", "Luffy",
        "--main-jar", "Luffy-0.1.0-all.jar",
        "--main-class", "dev.lufi.ui.LuffyLauncher",
        "--app-content", bundledVlcWindowsOutput.get().asFile.absolutePath,
        "--java-options", "-Dfile.encoding=UTF-8"
    )
    outputs.dir(appImageDestination.map { it.resolve("Luffy") })
}

/**
 * Gera um instalador Windows. Diferentemente de windowsAppImage, o instalador
 * registra Luffy nas capacidades do sistema para magnet: e arquivos .torrent,
 * sem substituir a escolha padrão do usuário.
 *
 * Requer WiX Toolset na máquina que gera o pacote, como exigido pelo jpackage.
 */
tasks.register<Exec>("windowsInstaller") {
    group = "distribution"
    description = "Gera o instalador Windows do Luffy com suporte a magnet e .torrent."
    val executableJar = tasks.named<Jar>("executableJar")
    val installerDestination = providers.gradleProperty("windowsInstallerDestination")
        .map { file(it) }
        .orElse(layout.buildDirectory.dir("windows-installer").map { it.asFile })
    dependsOn(executableJar, "prepareBundledVlcWindows")
    inputs.file(executableJar.flatMap { it.archiveFile })
    inputs.dir(layout.projectDirectory.dir("src/windows/jpackage"))
    inputs.dir(bundledVlcWindowsOutput)
    val jpackage = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.file("bin/jpackage.exe").asFile
    executable = jpackage.absolutePath
    args(
        "--type", "exe",
        "--dest", installerDestination.get().absolutePath,
        "--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        "--name", "Luffy",
        "--main-jar", "Luffy-0.1.0-all.jar",
        "--main-class", "dev.lufi.ui.LuffyLauncher",
        "--app-content", bundledVlcWindowsOutput.get().asFile.absolutePath,
        "--java-options", "-Dfile.encoding=UTF-8",
        "--vendor", "Luffy",
        "--description", "Streaming e downloads BitTorrent no Luffy",
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
        "--resource-dir", layout.projectDirectory.dir("src/windows/jpackage").asFile.absolutePath
    )
    outputs.dir(installerDestination)
}
