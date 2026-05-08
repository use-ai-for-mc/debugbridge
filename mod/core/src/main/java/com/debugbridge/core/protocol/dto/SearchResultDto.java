package com.debugbridge.core.protocol.dto;

/**
 * One entry in the {@code search} response array.
 *
 * <p>The {@code search} endpoint, unlike most others, returns a bare array on
 * the wire (no {@code {results: [...]}} wrapper) — schema-locked here as a
 * {@code List<SearchResultDto>} that Gson serializes to a JsonArray directly.
 *
 * <p>Field meanings:
 * <ul>
 *   <li>{@code type} — one of {@code "class"}, {@code "method"}, {@code "field"}.
 *   <li>{@code name} — the matched name (full class name for {@code "class"}
 *       hits; method signature for {@code "method"}; field name for
 *       {@code "field"}).
 *   <li>{@code owner} — the declaring class for method/field hits; null and
 *       omitted for class hits.
 * </ul>
 */
public final class SearchResultDto {
    public String type;
    public String name;
    public String owner;
}
