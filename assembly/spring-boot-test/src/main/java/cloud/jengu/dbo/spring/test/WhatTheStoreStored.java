package cloud.jengu.dbo.spring.test;

import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * What a write answered with, and the one thing a caller needs next.
 *
 * <p>A test that writes a record almost always reads it back or searches for
 * it, and both need the id the store gave it. Digging that out of the returned
 * document with a regular expression is what every test did before this — a
 * parser per test, over a document whose shape is not the point of the test.
 *
 * <p><b>Taken from {@code Location}, not from the body.</b> A create answers
 * with the address of what it made, which is the contract; the id happening to
 * appear in the rendered document is a convenience of the renderer. Reading
 * the header is reading what was promised, and it keeps working for a face
 * that renders differently or for a write whose body is a shell — which is
 * every write to a tenant that holds its people in the vault.
 */
public record WhatTheStoreStored(HttpResponse<String> response) {

    /** Whether the store took it. */
    public boolean accepted() {
        return response.statusCode() == 201;
    }

    public int statusCode() {
        return response.statusCode();
    }

    public String body() {
        return response.body();
    }

    /**
     * The id the store gave it, off the address it answered with.
     *
     * <p>Empty where there is no address, which is every refusal: a caller
     * that asked for the id of something the store declined is asking about
     * nothing, and an id invented here would send it looking for a record that
     * does not exist.
     */
    public Optional<String> id() {
        return response.headers().firstValue("Location")
                .map(at -> at.substring(at.lastIndexOf('/') + 1));
    }

    /**
     * The id, or a failure saying what the store said instead.
     *
     * <p>For the common line — write, then read it back — where an empty
     * optional would surface three lines later as a null id in a URL.
     */
    public String idOrFail() {
        return id().orElseThrow(() -> new AssertionError(
                "the store answered " + statusCode() + " with no Location, so there is no "
                        + "record to go on with: " + body()));
    }
}
