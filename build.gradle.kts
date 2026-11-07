plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
