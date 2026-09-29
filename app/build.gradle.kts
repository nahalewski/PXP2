plugins { id("com.android.application") }
android {
    namespace = "org.pxp2.fresh"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.pxp2.fresh"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-xmb"
    }
    buildTypes {
        // Optimised build for real use; signed with the local debug key so it installs over development builds.
        getByName("release") { isMinifyEnabled = false; isDebuggable = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir("../core/src")
}
