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
    // names it (#140). A leaf of enums and one framework interface — the
    // "zero new dependencies" note above still holds in spirit: nothing
    // third-party enters.
    implementation(project(":core:dbo-promises"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.pdi",
            "Export-Package" to "cloud.jengu.dbo.pdi;version=0.1.0",
        ))
    }
}
