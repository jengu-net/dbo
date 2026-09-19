package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant declares what identifies a person on the type, and nowhere else.
 *
 * <p>It used to be two places. A type named the systems it is identified by,
 * and {@code scim} named the namespace {@code externalId} values live in — a
 * namespace no type mentioned, feeding the same vault claim from a second
 * vocabulary. The membrane read one of them, so the directory's uniqueness
 * disappeared while everything still looked right: a second User claiming one
 * employee's number was created rather than refused, and lookup by it answered
 * nothing at all.
 *
 * <p>So {@code scim} selects rather than declares, and a selection of
 * something undeclared is refused at registration — where a declaration
 * disagreeing with itself can still be corrected by the person who wrote it.
 */
class WhatIdentifiesSomebodyIsSaidOnceTest {

    private static final String SYSTEM = "urn:test:idp:staff";

    @Test
    @DisplayName("scim naming a system Person is not declared identified by is refused, "
            + "and the refusal says how to declare it")
    void aSystemDeclaredOnlyByScimIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"kaks","face":"r4","pdi":true,
                         "scim":{"system":"%s"},
                         "types":[
                          {"name":"Person","identity":"internal","handling":"operational"}]}"""
                        .formatted(SYSTEM)));
        assertTrue(refused.getMessage().contains(SYSTEM)
                        && refused.getMessage().contains("identifier"),
                "a refusal a reader cannot act on is a refusal they will work around: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("declared once on the type, scim names one of those systems, and the "
            + "tenant stands")
    void theTypeCarriesTheDeclarationAndScimSelectsIt() {
        TenantSpec spec = TenantSpec.parse("""
                {"code":"uks","face":"r4","pdi":true,
                 "scim":{"system":"%s"},
                 "types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Practitioner","identity":"internal",
                   "handling":"operational"}]}""".formatted(SYSTEM, SYSTEM));
        assertEquals(SYSTEM, spec.scim().system());
        assertTrue(spec.types().stream()
                        .filter(t -> "Person".equals(t.typeName()))
                        .anyMatch(t -> t.identitySystems().contains(SYSTEM)),
                "the type is where it is said, and the vault reads it from there");
    }

    @Test
    @DisplayName("a tenant declaring no Person at all is refused here too, because a file "
            + "naming a door it does not carry the parts for is still a file to change")
    void aMissingPersonTypeIsAlsoThisFilesProblem() {
        // This used to parse, on the reading that a missing type is a
        // different mistake from a disagreeing one and therefore bring-up's
        // to refuse. The first half of that is true and the second did not
        // follow from it: what decides where a mistake is caught, everywhere
        // else in this constructor, is whether the file alone can be wrong
        // about it. A face is left to bring-up because whether anything
        // serves it depends on what is installed; a step id is refused at
        // parse because a typo is a typo. A Person that was never declared is
        // the second kind.
        //
        // It also stopped being bring-up's in fact rather than only in
        // principle: the scim door is an activity now, selected on the one
        // condition a file cannot answer, so there is no half-built tenant
        // left for a missing type to be discovered in.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"kolm","face":"r4","pdi":true,
                         "scim":{"system":"%s"},
                         "types":[
                          {"name":"Observation","identity":"internal",
                           "handling":"operational"}]}"""
                        .formatted(SYSTEM)));

        assertTrue(refused.getMessage().contains("Person"), refused.getMessage());
    }
}
