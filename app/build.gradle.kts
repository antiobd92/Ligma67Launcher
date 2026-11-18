import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Generates the window-level open/close animation resources.
 *
 * Android only accepts window transition animations for *another app's* window as XML
 * resources (ActivityOptions.makeCustomAnimation), so durations and curves can't be set
 * at runtime. Instead we generate every style x curve x duration combination at build
 * time and pick the right one by name at launch (see anim/WindowAnimations.kt).
 */
abstract class GenerateWindowAnimsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val anim = File(root, "anim").apply { mkdirs() }
        val ns = "xmlns:android=\"http://schemas.android.com/apk/res/android\""
        val curves = linkedMapOf(
            "std" to "@interpolator/l67_standard",
            "emph" to "@interpolator/l67_emphasized",
            "decel" to "@interpolator/l67_decelerate",
            "over" to "@interpolator/l67_overshoot",
            "lin" to "@android:interpolator/linear",
        )
        val fadeIn = "@interpolator/l67_decelerate"
        val std = "@interpolator/l67_standard"
        val durations = (100..800 step 50)

        fun write(name: String, body: String) {
            File(anim, "$name.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$body\n")
        }

        fun set(vararg children: String) =
            "<set $ns android:shareInterpolator=\"false\">\n" + children.joinToString("\n") { "    $it" } + "\n</set>"

        fun alpha(from: Float, to: Float, d: Int, interp: String, offset: Int = 0) =
            "<alpha android:fromAlpha=\"$from\" android:toAlpha=\"$to\" android:duration=\"$d\" android:startOffset=\"$offset\" android:interpolator=\"$interp\"/>"

        fun scale(from: Float, to: Float, d: Int, interp: String, offset: Int = 0) =
            "<scale android:fromXScale=\"$from\" android:toXScale=\"$to\" android:fromYScale=\"$from\" android:toYScale=\"$to\" " +
                "android:pivotX=\"50%\" android:pivotY=\"50%\" android:duration=\"$d\" android:startOffset=\"$offset\" android:interpolator=\"$interp\"/>"

        fun translate(fromX: String, toX: String, fromY: String, toY: String, d: Int, interp: String) =
            "<translate android:fromXDelta=\"$fromX\" android:toXDelta=\"$toX\" android:fromYDelta=\"$fromY\" android:toYDelta=\"$toY\" " +
                "android:duration=\"$d\" android:interpolator=\"$interp\"/>"

        // Opening app window (enter) animations: style x curve x duration.
        for ((curve, i) in curves) for (d in durations) {
            write("l67_e_zoom_${curve}_$d", set(scale(0.86f, 1f, d, i), alpha(0f, 1f, d * 6 / 10, fadeIn)))
            write("l67_e_fade_${curve}_$d", set(alpha(0f, 1f, d, i)))
            write("l67_e_slideup_${curve}_$d", set(translate("0", "0", "9%", "0", d, i), alpha(0f, 1f, d * 7 / 10, fadeIn)))
            write("l67_e_slide_${curve}_$d", set(translate("100%", "0", "0", "0", d, i)))
            write("l67_e_pop_${curve}_$d", set(scale(0.5f, 1f, d, i), alpha(0f, 1f, d * 4 / 10, fadeIn)))
            write("l67_e_rise_${curve}_$d", set(translate("0", "0", "100%", "0", d, i)))
        }
        // "Expand from icon" — the launcher draws the expanding card itself; the real
        // app window fades in on top of it once the card is mostly open.
        for (d in durations) {
            val off = d * 55 / 100
            write("l67_e_expand_$d", set(scale(0.97f, 1f, d - off, fadeIn, off), alpha(0f, 1f, d - off, fadeIn, off)))
        }
        // Launcher window (exit) while an app opens.
        for (d in durations) {
            write("l67_x_hold_$d", set(alpha(1f, 1f, d, "@android:interpolator/linear")))
            write("l67_x_fade_$d", set(alpha(1f, 0f, d, std)))
            write("l67_x_zoomout_$d", set(scale(1f, 0.92f, d, std), alpha(1f, 0f, d, std)))
            write("l67_x_zoomin_$d", set(scale(1f, 1.1f, d, std), alpha(1f, 0f, d, std)))
            write("l67_x_slideaway_$d", set(translate("0", "-30%", "0", "0", d, std), alpha(1f, 0.4f, d, std)))
        }
        // Android 14+ "system close transition": launcher enter (he) + closing app exit (ax).
        for (d in durations) {
            write("l67_he_shrink_$d", set(alpha(0f, 1f, d / 2, fadeIn)))
            write("l67_ax_shrink_$d", set(scale(1f, 0.85f, d, std), alpha(1f, 0f, d, std)))
            write("l67_he_fade_$d", set(alpha(0f, 1f, d, fadeIn)))
            write("l67_ax_fade_$d", set(alpha(1f, 0f, d, std)))
            write("l67_he_slidedown_$d", set(alpha(1f, 1f, d, "@android:interpolator/linear")))
            write("l67_ax_slidedown_$d", set(translate("0", "0", "0", "100%", d, std)))
            write("l67_he_shrinkhard_$d", set(alpha(0f, 1f, d * 4 / 10, fadeIn)))
            write("l67_ax_shrinkhard_$d", set(scale(1f, 0.2f, d, "@interpolator/l67_emphasized"), alpha(1f, 0f, d, std)))
            write("l67_he_slideright_$d", set(translate("-25%", "0", "0", "0", d, std)))
            write("l67_ax_slideright_$d", set(translate("0", "100%", "0", "0", d, std)))
        }
        write("l67_none", set(alpha(1f, 1f, 0, "@android:interpolator/linear")))
    }
}

val generateWindowAnims = tasks.register<GenerateWindowAnimsTask>("generateWindowAnims") {
    outputDir.set(layout.buildDirectory.dir("generated/l67-window-anims/res"))
}

// Release signing key lives OUTSIDE the repo (never committed):
// ~/Ligma67Keys/keystore.properties, or pass -Pligma67.keystore=/path/to/keystore.properties.
// Without it, release builds fall back to the debug key so anyone can still build the project.
val releaseKeystore: File = (findProperty("ligma67.keystore") as String?)?.let(::File)
    ?: File(System.getProperty("user.home"), "Ligma67Keys/keystore.properties")

android {
    namespace = "com.ligma67.launcher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ligma67.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            val p = Properties().apply { releaseKeystore.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Compose is MUCH smoother in release (R8 + no debuggable interpreter) — use it.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateWindowAnims, GenerateWindowAnimsTask::outputDir)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
