version = 3

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: "MadaraUciha000/Tollystream"

cloudstream {
    language = "ta"
    authors = listOf("Taisir Shaik")
    description = "Indian movies from 1TamilMV (Tamil, Telugu, Hindi, Malayalam, Kannada) - " +
        "torrents, magnets and direct links, with Cloudflare bypass and TMDB metadata " +
        "(posters, banners, plot, rating, genres, runtime, cast)."
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
