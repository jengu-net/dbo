plugins {
    id("biz.aQute.bnd.builder")
}

// Personal-data isolation (§14): identifying elements are encrypted
// IN the payload (per-person AES-GCM keys), so history, archives, feeds and
// sync carry ciphertext by construction; the vault holds only wrapped keys,
// an HMAC identifier index, the restricted flag and the shred ledger.
// Crypto-shredding = destroy the key. ZERO new dependencies.

dependencies {
    api(project(":core:dbo-core"))
    // the store's promise catalogue: a refusal that paraphrases a promise
    // names it. A leaf of enums and one framework interface — the
    // "zero new dependencies" note above still holds in spirit: nothing
    // third-party enters.
    implementation(project(":core:dbo-promises"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.pdi",
            // SUBSTITUTABLE: this bundle imports what it exports.
            //
            // Policy, not inventory — the packages are named, the rest is
            // still bnd's to compute. bnd writes an import for a bundle's own
            // export only when some OTHER package inside the bundle uses it,
            // and a bundle whose code is one package never does. Without the
            // import the bundle can only ever wire to itself, so a host that
            // supplies these classes from outside the framework is ignored:
            // the two copies then differ, and the framework HIDES a service
            // whose type the consuming bundle loads differently. Nothing is
            // logged and nothing throws; an extension point simply never
            // fires. In a container with no other provider this changes
            // nothing at all — the bundle wires to itself, as before.
            "Import-Package" to "cloud.jengu.dbo.pdi,*",
            "Export-Package" to "cloud.jengu.dbo.pdi;version=0.1.0",
        ))
    }
}
