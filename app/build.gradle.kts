import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.golocal.wbmxrq"
    minSdk = 24
    targetSdk = 36
    versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 1
    versionName = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0"
    val manifestUrl = (System.getenv("UPDATE_MANIFEST_URL") ?: "")
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
    buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$manifestUrl\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

abstract class EnsureRideRequestSoundTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val existingRawFiles: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val outputResDir: DirectoryProperty

  @TaskAction
  fun ensureSound() {
    val outDir = outputResDir.get().asFile
    val targetWav = File(outDir, "raw/golocal_ride_request.wav")
    if (!existingRawFiles.isEmpty) {
      if (targetWav.exists()) targetWav.delete()
      return
    }
    targetWav.parentFile.mkdirs()

    val sampleRate = 22050
    val durationSec = 0.85
    val totalSamples = (sampleRate * durationSec).toInt()
    val dataBytes = totalSamples * 2
    val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(1.toShort())
    buffer.putShort(1.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * 2)
    buffer.putShort(2.toShort())
    buffer.putShort(16.toShort())
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataBytes)

    for (i in 0 until totalSamples) {
      val t = i.toDouble() / sampleRate
      val (freq, localT) = when {
        t < 0.16 -> 880.0 to t
        t < 0.34 -> 1174.66 to (t - 0.16)
        else -> 1318.51 to (t - 0.34)
      }
      val attack = min(1.0, localT / 0.012)
      val decay = exp(-localT * 5.2)
      val tailFade = if (i > totalSamples - 600) (totalSamples - i) / 600.0 else 1.0
      val wave = (0.78 * sin(2.0 * PI * freq * t) + 0.22 * sin(2.0 * PI * freq * 2.0 * t))
      val sample = (wave * attack * decay * tailFade * 0.65 * Short.MAX_VALUE)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
      buffer.putShort(sample)
    }

    targetWav.writeBytes(buffer.array())
  }
}

androidComponents {
  onVariants { variant ->
    val capName = variant.name.replaceFirstChar { it.uppercase() }
    val ensureSoundTask = tasks.register<EnsureRideRequestSoundTask>("ensure${capName}RideRequestSound") {
      existingRawFiles.from(
        layout.projectDirectory.dir("src/main/res/raw").asFileTree.matching {
          include("golocal_ride_request.*")
        }
      )
      outputResDir.set(layout.buildDirectory.dir("generated/res/golocalSound/${variant.name}"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(
      ensureSoundTask,
      EnsureRideRequestSoundTask::outputResDir
    )
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.database)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.maplibre.android)
  implementation(libs.play.services.location)
  implementation(libs.zxing.core)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<Test> {
  failFast = true
  timeout.set(Duration.ofMinutes(2))
  testLogging {
    events("passed", "failed", "skipped")
  }
}
