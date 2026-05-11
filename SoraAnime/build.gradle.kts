// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "Anime-only provider based on SoraStream sources"
     authors = listOf("Hexated", "Sora")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
    )

    iconUrl = "https://github.com/duro92/ExtCloud/blob/main/SoraAnime/Icon.png"
}
