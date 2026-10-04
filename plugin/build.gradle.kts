import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
}

android {
    namespace = "io.github.lnrplugin.wenkukeeper"
    compileSdk = 37

    // 显式指定 Build Tools：AGP 9.2.1 默认要求 36.0.0，而 SDK 里不一定装了那一个版本
    // （例如只装了 37.0.0）。写死一个与 SDK 一同安装的版本，避免构建时再去下载。
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.lnrplugin.wenkukeeper"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The host installs plugins as ".lnrp" files (an Android package with a different extension).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            val outputImpl = it as com.android.build.api.variant.impl.VariantOutputImpl
            val originalFileName = outputImpl.outputFileName.get()
            val newFileName = originalFileName.replace(".apk", ".apk.lnrp")
            outputImpl.outputFileName = newFileName
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

// The KSP `compiler` artifact generates the manifest that declares the plugin entry point
// to the host. It must be merged into the variant manifest, and KSP must run first.
androidComponents {
    onVariants { variant ->
        variant.sources.manifests.addStaticManifestFile(
            layout.buildDirectory.file("generated/ksp/${variant.name}/resources/auto_register_manifest.xml").get().toString()
        )
    }
}

afterEvaluate {
    listOf("Debug", "Release").forEach { variant ->
        tasks.findByName("process${variant}MainManifest")?.dependsOn("ksp${variant}Kotlin")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.foundation.layout)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)

    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jaxen)
    // kotlin-result 必须是 compileOnly：它出现在插件 API 的返回类型里
    // （WebBookDataSource.getBookInformation(): Result<BookInformation, WebRequestError>），
    // 而宿主自己也带同一份（版本一致，均为 2.3.1）。
    //
    // PluginClassLoader 是子加载器优先，白名单里没有 com.github.michaelbull.result，
    // 所以只要插件自带一份，宿主拿到的就是「插件的 Result」，而宿主的
    // ProxyPriorityWebBookDataSource 会把它 cast 成「宿主的 Result」：
    //     java.lang.ClassCastException: com.github.michaelbull.result.Result
    //         cannot be cast to com.github.michaelbull.result.Result
    // 宿主的 uncaughtException 处理器随后调用 System.exit(1)，整个 App 直接死掉。
    //
    // 官方模板与 linovelib 插件都写的 implementation，因此这个坑在真机上只要
    // 「书架里有来自该插件的书」就会必现；compileOnly 让类回落到父加载器（宿主）的那一份。
    compileOnly(libs.kotlin.result)
    compileOnly(libs.kotlin.result.coroutines)

    implementation(libs.jsoup)

    // Ktor: the CIO engine is used, not OkHttp. The host's PluginClassLoader loads classes
    // child-first, so an OkHttp bundled here would be the copy that runs, and OkHttp 5
    // crashes with NoSuchMethodError on android.net.ssl.SSLSockets (see the synthetic
    // framework stubs D8 packages into the plugin dex).
    // ktor-client-logging is deliberately NOT a dependency: no logger is installed
    // anywhere in this plugin, so request headers (including credentials) can never
    // reach Logcat.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // LightNovelReader plugin API (provided by the host at runtime).
    compileOnly(libs.lightnovelreader.api)
    ksp(libs.lightnovelreader.compiler)
}

// ---------------------------------------------------------------------------
// Convenience tasks: build the plugin and push it to a connected device.
// ---------------------------------------------------------------------------
val debugHostPkg = "indi.dmzz_yyhyy.lightnovelreader.debug"
val releaseHostPkg = "indi.dmzz_yyhyy.lightnovelreader"

fun pluginApk(variant: String): File =
    File(layout.buildDirectory.asFile.get(), "outputs/apk/${variant.lowercase()}")
        .walkTopDown()
        .first { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".lnrp")) }

fun installPluginTask(name: String, hostPkg: String, variant: String) {
    tasks.register(name) {
        description = "Builds and installs the $variant plugin, then restarts the host app"
        group = "plugin"
        dependsOn("assemble$variant")

        doLast {
            val adb = listOf(androidComponents.sdkComponents.adb.get().asFile.absolutePath) +
                    (System.getenv("ANDROID_SERIAL")?.let { listOf("-s", it) } ?: emptyList())
            val src = pluginApk(variant)
            // adb install refuses a ".lnrp" extension, so temporarily rename it.
            val file =
                if (src.name.endsWith(".apk")) src
                else File(src.parent, src.name.removeSuffix(".lnrp")).also { src.renameTo(it) }

            try {
                providers.exec { commandLine(adb + listOf("install", "-r", "-t", file)) }.result.get()
            } finally {
                if (file != src) file.renameTo(src)
            }

            providers.exec { commandLine(adb + listOf("shell", "am", "force-stop", hostPkg)) }.result.get()
            providers.exec {
                commandLine(
                    adb + listOf(
                        "shell", "monkey", "-p", hostPkg, "-c",
                        "android.intent.category.LAUNCHER", "1"
                    )
                )
            }.result.get()
        }
    }
}

installPluginTask("runDebugHostWithDebugPlugin", debugHostPkg, "Debug")
installPluginTask("runReleaseHostWithDebugPlugin", releaseHostPkg, "Debug")
installPluginTask("runDebugHostWithReleasePlugin", debugHostPkg, "Release")
installPluginTask("runReleaseHostWithReleasePlugin", releaseHostPkg, "Release")
