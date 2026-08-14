subprojects {
    apply(plugin = "java-library")
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
