// A deliberately NON-FHIR sibling model (REQ-DBO-CORE-SIBLING-MODELS): a
// gadget registry. Personalities may carry their own stacks — this one uses
// Jackson, proving the core neither has nor needs a JSON library.

dependencies {
    api(project(":core:dbo-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
