import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val uploadKeyPropertiesFile = rootProject.file("key.properties")
val uploadKeyProperties = Properties().apply {
    if (uploadKeyPropertiesFile.isFile) uploadKeyPropertiesFile.inputStream().use { load(it) }
}
// A phone-test APK can coexist with the Google Play installation and uses demo ads.
val previewBuild = providers.gradleProperty("previewBuild").orNull == "true"

android {
    namespace = "com.vpnapp"
    compileSdk = 36
    testBuildType = providers.gradleProperty("androidTestBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = if (previewBuild) "com.rahanetmci.securevpn.preview" else "com.rahanetmci.securevpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = if (previewBuild) "1.1.7-test" else "1.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        manifestPlaceholders["appLabel"] = if (previewBuild) "SecureVPN Test" else "@string/app_name"
        vectorDrawables {
            useSupportLibrary = true
        }
        

    }

    signingConfigs {
        create("upload") {
            if (uploadKeyPropertiesFile.isFile) {
                storeFile = rootProject.file(uploadKeyProperties.getProperty("storeFile"))
                storePassword = uploadKeyProperties.getProperty("storePassword")
                keyAlias = uploadKeyProperties.getProperty("keyAlias")
                keyPassword = uploadKeyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (uploadKeyPropertiesFile.isFile) signingConfigs.getByName("upload") else null
            manifestPlaceholders["admobAppId"] = if (previewBuild) "ca-app-pub-3940256099942544~3347511713" else "ca-app-pub-5284715425712192~7524274903"
            buildConfigField("String", "ADMOB_BANNER_ID", if (previewBuild) "\"ca-app-pub-3940256099942544/6300978111\"" else "\"ca-app-pub-5284715425712192/9988540732\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Test the non-minified app on a dedicated emulator without uninstalling release data.
            if (providers.gradleProperty("debugUploadSigning").orNull == "true") {
                signingConfig = signingConfigs.getByName("upload")
            }
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        }
    }


    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Generate a loopback identity only for instrumentation APKs.
// A software key avoids emulator AndroidKeyStore signing failures; it never enters release assets or Git.
val testTlsAssets = layout.buildDirectory.dir("generated/androidTestTls")
android.sourceSets.getByName("androidTest").assets.srcDir(testTlsAssets)
val generateTestTlsIdentity by tasks.registering(Exec::class) {
    val identity = testTlsAssets.get().file("loopback-test.p12").asFile
    outputs.file(identity)
    onlyIf { !identity.isFile }
    doFirst { identity.parentFile.mkdirs() }
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
    commandLine(File(System.getProperty("java.home"), "bin/$executable").absolutePath,
        "-genkeypair", "-alias", "loopback", "-keyalg", "EC", "-groupname", "secp256r1",
        "-sigalg", "SHA256withECDSA", "-storetype", "PKCS12", "-keystore", identity.absolutePath,
        "-storepass", "local-test-only", "-keypass", "local-test-only", "-dname", "CN=localhost",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "3650", "-noprompt")
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn(generateTestTlsIdentity)
}

tasks.matching { it.name in setOf("bundleRelease", "assembleRelease") }.configureEach {
    doFirst {
        check(uploadKeyPropertiesFile.isFile) {
            "Release signing is not configured. Create the ignored key.properties file first; never use the debug key for Play uploads."
        }
    }
}


repositories {
    google()
    mavenCentral()
    flatDir {
        dirs("libs")
    }
}

configurations.configureEach { exclude(group = "androidx.graphics", module = "graphics-path") }

dependencies {
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // Sing-box for V2Ray protocols (VLESS, VMess, Trojan, Shadowsocks)
    implementation(files("libs/libbox.aar"))
    
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation(files("libs/graphics-path-1.1.0-securevpn.aar"))
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // BouncyCastle for DTLS (UDP-based TLS) support
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bctls-jdk18on:1.77")
    
    // Bundled Conscrypt to bypass Android 15 Hidden API restrictions on System SSL
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}



