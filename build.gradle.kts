// Root build file. Repositories are managed in settings.gradle.kts

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

