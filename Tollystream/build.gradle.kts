version = 1

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: "USERNAME/Tollystream"

cloudstream {
    language = "ta"
    authors = listOf("Tollystream")
    description = "Indian movies from 1TamilMV (Tamil, Telugu, Hindi, Malayalam, Kannada) - " +
        "torrents, magnets and direct links, with a Cloudflare bypass on every request."
    status = 1
    tvTypes = listOf("Movie")
    requiresResources = false
    setRepo("https://github.com/$ghRepo")
    iconUrl = "https://raw.githubusercontent.com/$ghRepo/main/Tollystream/icon.png"
}

android {
    namespace = "com.tollystream"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
