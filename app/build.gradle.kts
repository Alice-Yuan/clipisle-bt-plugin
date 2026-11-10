import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseKeysFile = rootProject.file("key.properties")
val releaseKeys = Properties().apply { if (releaseKeysFile.isFile) releaseKeysFile.inputStream().use { load(it) } }
android {
 namespace = "com.clipisle.btplugin"
 compileSdk = 36
 defaultConfig { applicationId = "com.clipisle.btplugin"; minSdk = 26; targetSdk = 36; versionCode = 3; versionName = "1.0.2" }
 signingConfigs {
  if (releaseKeysFile.isFile) create("release") {
   storeFile = rootProject.file(requireNotNull(releaseKeys.getProperty("storeFile")))
   storePassword = requireNotNull(releaseKeys.getProperty("storePassword"))
   keyAlias = requireNotNull(releaseKeys.getProperty("keyAlias"))
   keyPassword = requireNotNull(releaseKeys.getProperty("keyPassword"))
  }
 }
 buildTypes { getByName("release") { signingConfig = signingConfigs.findByName("release") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 packaging { jniLibs { excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**") } }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("com.frostwire:jlibtorrent-android:2.0.15.3@aar")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
