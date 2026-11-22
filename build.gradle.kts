// Top-level build file. Plugin versions are declared here so that the
// project can be opened directly in Android Studio (Giraffe or newer).
plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // Generates the serializers for the network DTOs without reflection (TZ §11.2).
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
