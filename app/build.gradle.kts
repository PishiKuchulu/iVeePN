import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val hevSocks5TunnelVersion = "2.17.1"
// BLD-FX-09: a git tag is mutable. Pin the exact commit the 2.17.1 tag
// resolves to and verify the checkout before any native build uses it.
val hevSocks5TunnelCommit = "9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a"
val hevSocks5TunnelDir = file("src/main/jni")

val fetchHevSocks5Tunnel = tasks.register<Exec>("fetchHevSocks5Tunnel") {
    val marker = File(hevSocks5TunnelDir, "Android.mk")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    doFirst { hevSocks5TunnelDir.mkdirs() }
    commandLine(
        "git", "clone",
        "--branch", hevSocks5TunnelVersion,
        "--depth", "1",
        "--recursive",
        "--shallow-submodules",
        "https://github.com/heiher/hev-socks5-tunnel.git",
        hevSocks5TunnelDir.absolutePath,
    )
}

val verifyHevSocks5Tunnel = tasks.register<Exec>("verifyHevSocks5Tunnel") {
    dependsOn(fetchHevSocks5Tunnel)
    // NOTE: plain ByteArrayOutputStream via import — in a Gradle Kotlin
    // script `java` resolves to the Java plugin extension, so the fully
    // qualified `java.io.…` form does not compile here.
    val captured = ByteArrayOutputStream()
    commandLine("git", "-C", hevSocks5TunnelDir.absolutePath, "rev-parse", "HEAD")
    standardOutput = captured
    isIgnoreExitValue = true
    doLast {
        val head = captured.toString().trim()
        if (head != hevSocks5TunnelCommit) {
            throw GradleException(
                "hev-socks5-tunnel checkout is at '$head', expected pinned commit " +
                    "$hevSocks5TunnelCommit (tag $hevSocks5TunnelVersion). Refusing to build " +
                    "against an unverified native dependency.",
            )
        }
    }
}

tasks.matching { it.name.startsWith("externalNativeBuild") || it.name.contains("NdkBuild") }
    .configureEach { dependsOn(verifyHevSocks5Tunnel) }
tasks.named("preBuild").configure { dependsOn(verifyHevSocks5Tunnel) }

// The release keystore is a PKCS#12 file; accept either name so the CI
// secret decode step can keep the format explicit (.p12 preferred).
val releaseKeystoreFile = listOf("release-keystore.p12", "release-keystore.jks")
    .map { rootProject.file(it) }
    .firstOrNull { it.exists() }
    ?: rootProject.file("release-keystore.p12")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile.exists() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.pishi.iveepn"
    compileSdk = 35

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.pishi.iveepn"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.10"

        externalNativeBuild {
            ndkBuild {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                arguments += listOf(

                    "APP_PLATFORM=android-26",

                    "APP_CFLAGS=-DPKGNAME=com/pishi/iveepn/vpn/tun -DCLSNAME=HevSocks5Tunnel",
                )
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // V-52: the BLD-FX-10 suppression is gone — lint keeps its default
    // abortOnError = true, so lint errors are fatal in local/CI builds
    // that run the lint task as a gate. (The CI workflow's lint step is
    // report-only via continue-on-error, so this does not gate releases.)

    packaging {
        resources.excludes.add("META-INF/INDEX.LIST")
        resources.excludes.add("META-INF/*.SF")
        resources.excludes.add("META-INF/*.DSA")
        resources.excludes.add("META-INF/*.RSA")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Version managed by the Compose BOM above (no explicit pin).
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // security-crypto 1.1.0-alpha07 is the newest published release of
    // this library and no stable successor exists yet, so the alpha pin
    // here is deliberate.
    implementation("androidx.security:security-crypto:1.1.0-alpha07")




    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
