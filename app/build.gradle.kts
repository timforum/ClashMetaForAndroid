import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

// Every one of these is several megabytes, so a floor this low never rejects a
// real file and still catches the common way one goes wrong: a transfer that
// stops early. It cannot catch a file that is 99% of the right size, because
// the release publishes no checksum to check it against.
val minGeoFileBytes = 1024L * 1024

task("downloadGeoFiles") {

    val geoFilesUrls = mapOf(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z" to "BundleMRS.7z",
    )

    val geoFiles = geoFilesUrls.map { (downloadUrl, name) -> downloadUrl to file("$geoFilesDownloadDir/$name") }

    // The assets directory is not tracked, so nothing else tells Gradle these
    // files are already here. Declared without that, the task runs on every
    // single build and pulls twenty-six megabytes over a link that fails often
    // enough to lose a build to it - and the file it loses the build on is one
    // that was already downloaded.
    outputs.files(geoFiles.map { it.second })

    outputs.upToDateWhen {
        geoFiles.all { (_, path) -> path.isFile && path.length() > minGeoFileBytes }
    }

    doLast {
        geoFiles.forEach { (downloadUrl, outputPath) ->
            outputPath.parentFile.mkdirs()

            // Downloaded beside the target and moved into place only once it is
            // whole. Copying straight onto the output truncates it first, so a
            // transfer that dies halfway leaves a file that exists, passes the
            // check above, and poisons every build from then on.
            val partial = File(outputPath.parentFile, "${outputPath.name}.part")

            try {
                var attempt = 1
                while (true) {
                    try {
                        URL(downloadUrl).openStream().use { input ->
                            Files.copy(input, partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        break
                    } catch (e: Exception) {
                        if (attempt >= 3) {
                            throw GradleException("could not download ${outputPath.name} from $downloadUrl", e)
                        }
                        logger.lifecycle(
                            "${outputPath.name}: attempt ${attempt} failed (${e.message}), retrying"
                        )
                        attempt++
                    }
                }

                if (partial.length() <= minGeoFileBytes) {
                    throw GradleException(
                        "${outputPath.name} came back at ${partial.length()} bytes, which is not a database"
                    )
                }

                Files.move(partial.toPath(), outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("${outputPath.name} downloaded to $outputPath")
            } finally {
                partial.delete()
            }
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}