version = 1
cloudstream {
    description = "Pelis182 experimental. Series pendientes."
    authors = listOf("raiderking2022-wq")
    language = "es"
    tvTypes = listOf("Movie")
    requiresResources = false
}

dependencies {
    add("implementation", "org.jspecify:jspecify:1.0.0")
    add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}
