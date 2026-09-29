// This Gradle project has exactly ONE job: resolve Maven/JitPack dependencies
// and hand the raw resolved .aar/.jar files to the existing hand-rolled
// aapt2/d8/ecj pipeline (build.sh) to unpack and compile against. It is
// deliberately NOT an Android Gradle Plugin project -- no AGP applied, no
// android {} block, no build-tools/licenses SDK layout needed.

import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.java.TargetJvmEnvironment

repositories {
    google()
    mavenCentral()
    // Add other repos only as needed, e.g.:
    // maven { url = uri("https://jitpack.io") }
}

// A plain resolvable configuration, independent of any plugin's own
// configurations (no java-library/application plugin applied here at all --
// declaring dependencies/repositories doesn't require one).
//
// Explicit attributes are required here, not optional: some libraries
// (Guava, pulled in transitively by plenty of things) publish separate
// "-jre" and "-android" variants via Gradle Module Metadata with no plain
// fallback artifact. Without a consumer declaring what it wants, Gradle
// can't pick between them and fails resolution outright ("Unable to find a
// matching variant"). A real Android Gradle Plugin project sets these
// attributes automatically; since this project deliberately has no AGP,
// they're set by hand instead -- this is the one place "no AGP" isn't quite
// free.
//
// Deliberately NOT setting LibraryElements: AndroidX artifacts with strict
// Gradle Module Metadata (e.g. androidx.startup) only publish an "aar"
// library-elements variant, no plain "jar" one -- requiring "jar" makes
// those fail resolution outright even though they're exactly the AAR shape
// build.sh already knows how to unpack. Leave this attribute unset and let
// Gradle's own default variant disambiguation pick the right one per module.
// Many of AndroidX's own
// foundational modules -- androidx.annotation, androidx.collection,
// androidx.lifecycle:lifecycle-common, org.jetbrains.kotlinx:kotlinx-coroutines-*,
// and org.jetbrains.kotlin:kotlin-stdlib itself -- have all moved to real
// Kotlin-Multiplatform publishing at the versions Compose 1.12.0/material3
// 1.4.0 pull in transitively. Their bare umbrella coordinate publishes ONLY
// non-JVM-environment-tagged variants (ios*/watchos*/tvos*/js/wasmJs/etc.) at
// the Gradle Module Metadata level -- there is no plain "android" or even
// "jvm" tagged variant to match against under the ANDROID-constrained `deps`
// configuration below, so resolution fails outright. Excluded here from
// `deps` and resolved instead via the unconstrained `depsStandardJvm`
// configuration, at each module's own real JVM-target artifact coordinate
// where Google publishes one separately (collection-jvm, annotation-jvm,
// lifecycle-common-jvm), or the plain coordinate where the JVM variant is the
// one Gradle can already resolve unconstrained (kotlin-stdlib,
// kotlinx-coroutines-core/-android). Versions pinned to exactly what the
// Compose dependency graph itself requested (confirmed via the resolution
// error's own "Required by" trace), not guessed independently.
val kmpExcludes = listOf(
    "androidx.annotation" to "annotation",
    "androidx.collection" to "collection",
    "androidx.collection" to "collection-ktx",
    "androidx.lifecycle" to "lifecycle-common",
    "org.jetbrains.kotlin" to "kotlin-stdlib",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-android",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-core",
)

val deps = configurations.create("deps") {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID))
    }
    for ((group, module) in kmpExcludes) {
        exclude(group = group, module = module)
    }
}

// Unconstrained resolution -- no TargetJvmEnvironment attribute at all, so
// Gradle picks whatever JVM-compatible variant a module actually publishes
// (a real "jvm" target, or a plain non-multiplatform artifact) instead of
// hard-requiring "android" specifically.
val depsStandardJvm = configurations.create("depsStandardJvm") {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    }
}

dependencies {
    // Real, current stable versions confirmed against Google's Maven
    // metadata directly (dl.google.com/android/maven2/.../maven-metadata.xml)
    // before writing these -- not guessed from memory.
    deps("androidx.activity:activity-compose:1.13.0")
    deps("androidx.compose.runtime:runtime:1.12.0")
    deps("androidx.compose.ui:ui:1.12.0")
    deps("androidx.compose.ui:ui-graphics:1.12.0")
    deps("androidx.compose.ui:ui-unit:1.12.0")
    deps("androidx.compose.ui:ui-text:1.12.0")
    deps("androidx.compose.foundation:foundation:1.12.0")
    deps("androidx.compose.material3:material3:1.4.0")
    // Navigation and ViewModel.
    deps("androidx.navigation:navigation-compose:2.9.8")
    deps("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // SharedTransitionLayout lives in this artifact; pinned explicitly.
    deps("androidx.compose.animation:animation:1.12.0")

    // The multiplatform modules, as JVM artifacts (see above).
    "depsStandardJvm"("androidx.collection:collection-jvm:1.6.0")
    "depsStandardJvm"("androidx.annotation:annotation-jvm:1.10.0")
    "depsStandardJvm"("androidx.lifecycle:lifecycle-common-jvm:2.11.0")
    "depsStandardJvm"("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    "depsStandardJvm"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    "depsStandardJvm"("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    "depsStandardJvm"("androidx.collection:collection-ktx:1.4.2")
    "depsStandardJvm"("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3")
}

tasks.register<Sync>("resolveDeps") {
    // Sync, not Copy: a plain Copy task never removes files left over from a
    // previous resolution, so changing/removing a dependency silently leaves
    // its old jar (and any of ITS now-stale transitive deps) sitting in
    // deps/raw/ right alongside the new one -- d8 will refuse to dex two
    // different versions of the same class and the error will look like a
    // completely unrelated problem. Sync mirrors deps/raw/ to exactly the
    // current resolution every time, deleting anything stale.
    description = "Resolves declared dependencies and mirrors the raw .aar/.jar files into deps/raw/ for build.sh to unpack."
    from(deps)
    from(depsStandardJvm)
    into(layout.projectDirectory.dir("deps/raw"))
}
