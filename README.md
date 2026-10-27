# Livesteal Heads (Fabric, Minecraft 1.21.11)

Build: `./gradlew build` -> `build/libs/livesteal-heads-1.0.0.jar`. Put it in the server's `mods/` with Fabric API.
If you lack the Gradle wrapper, copy `src/` + `build.gradle` + `gradle.properties` into a fresh project from
https://fabricmc.net/develop/template and keep that template's wrapper / version numbers.

Server-side only: vanilla clients can join (items use vanilla models via the item_model component).

State is saved in `<world>/livesteal.json` (crafted items, ranks, hearts). Remove an id from "crafted" to allow re-crafting it.
Edit recipes in `src/main/resources/data/livesteal/recipe/*.json`.
