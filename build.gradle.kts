subprojects {
    apply(plugin = "java-library")
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    group = "cloud.jengu.dbo"
    version = "0.1.0"
}
