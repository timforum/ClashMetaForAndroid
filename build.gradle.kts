@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

// ---- App version ----------------------------------------------------------
//
// The build moves the app version on by itself, so an APK that is already on a
// phone is never mistaken for the one built before it. A run that packages an
// artifact takes the last segment of the name and the code up by one; every
// other invocation - cleaning, listing, compiling a library - leaves them
// alone, because those produce nothing to install.
//
// The two live in a file of their own that is not tracked. Keeping them in
// gradle.properties would mean every packaging run left a change behind in the
// working tree, which says nothing about the source and has to be thrown away
// before the next commit.

val versionFile = rootProject.file("version.properties")

fun readVersion(key: String, fallback: String): String {
    val file = versionFile

    return try {
        if (!file.isFile) {
            fallback
        } else {
            Properties()
                .apply { file.inputStream().use { load(it) } }
                .getProperty(key)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

// The fallback for a tree that has never packaged anything, kept at the last
// version actually shipped so a fresh clone counts up from there rather than
// from a number this file was written with long ago.
var appVersionName = readVersion("versionName", "2.11.34.9")
var appVersionCode = readVersion("versionCode", "211043").toInt()

if (gradle.startParameter.taskNames.any {
        it.contains("assemble", ignoreCase = true) || it.contains("package", ignoreCase = true)
    }
) {
    val segments = appVersionName.trim().split('.')
    val build = (segments.lastOrNull()?.toIntOrNull() ?: 0) + 1

    appVersionName = (segments.dropLast(1) + build).joinToString(".")
    appVersionCode += 1

    // Written back only once the build has finished rather than as soon as the
    // number is worked out, so the file records what was shipped and not what
    // was attempted. A run that fails partway leaves no gap: the next one
    // starts from the last number that actually reached a phone.
    gradle.addListener(object : org.gradle.BuildAdapter() {
        override fun buildFinished(result: org.gradle.BuildResult) {
            if (result.failure == null) {
                versionFile.writeText("versionName=$appVersionName\nversionCode=$appVersionCode\n")
            }
        }
    })
}

// ---- Core version header --------------------------------------------------
//
// The About screen's second line is this header. Gradle writes it because it
// knows which commit this repository is on and what day the build ran; CMake
// used to own it and only rewrote it when it reconfigured, which left the date
// frozen at the day it last happened to run while every newer APK carried the
// same string.

fun gitLine(vararg args: String): String =
    runCatching {
        providers.exec {
            commandLine(listOf("git", "-C", rootDir.absolutePath) + args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get()
    }.getOrDefault("")
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .ifEmpty { "unknown" }

val versionHeaderTemplate = rootProject.file("core/src/main/cpp/version.h.in")
val versionHeader = rootProject.file("core/src/main/cpp/version.h")

if (versionHeaderTemplate.exists()) {
    val coreVersion = "%s_%s_%s".format(
        gitLine("rev-parse", "--abbrev-ref", "HEAD"),
        gitLine("log", "-1", "--format=%h"),
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")),
    )

    val wanted = versionHeaderTemplate.readText().replace("@GIT_VERSION@", coreVersion)

    if (!versionHeader.exists() || versionHeader.readText() != wanted) {
        versionHeader.writeText(wanted)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

    // Only app (manifest/Tileservice) and design (layouts) consume these two
    // labels. They are emitted as a *reference* into the source strings, so any
    // module that lacks those strings fails resource linking - hideapi, which
    // has no res/ at all, always did.
    val needsAppLabels = isApp || name == "design"

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    fun queryConfigProperty(key: String): Any? {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        } else {
            return null
        }
        return localProperties.getProperty(key)
    }

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                val customApplicationId = queryConfigProperty("custom.application.id") as? String?
                applicationId = customApplicationId.takeIf { it?.isNotBlank() == true } ?: "com.github.metacubex.clash"
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            minSdk = 21
            targetSdk = 35

            versionName = appVersionName
            versionCode = appVersionCode

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += listOf("arm64-v8a")
            }

            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a")
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "cmfa-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                }
            }
        }

        productFlavors {
            flavorDimensions("feature")

            val removeSuffix = (queryConfigProperty("remove.suffix") as? String)?.toBoolean() == true

            create("alpha") {
                isDefault = true
                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Alpha"
                }


                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                if (needsAppLabels) {
                    resValue("string", "launch_name", "@string/launch_name_alpha")
                    resValue("string", "application_name", "@string/application_name_alpha")
                }

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".alpha"
                }
            }

            create("meta") {

                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Meta"
                }

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                if (needsAppLabels) {
                    resValue("string", "launch_name", "@string/launch_name_meta")
                    resValue("string", "application_name", "@string/application_name_meta")
                }

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".meta"
                }
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val keystore = rootProject.file("signing.properties")
            if (keystore.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        keystore.inputStream().use(this::load)
                    }

                    storeFile = rootProject.file(
                        prop.getProperty("keystore.file") ?: "release.keystore"
                    )
                    storePassword = prop.getProperty("keystore.password")!!
                    keyAlias = prop.getProperty("key.alias")!!
                    keyPassword = prop.getProperty("key.password")!!
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
                    isUniversalApk = true
                    reset()
                    include("arm64-v8a")
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}