# API Contract Checks

How the HTTP interface behaves. Status code rules are in `spring-boot` → `references/web.md`; this list is what to look for in a diff.

- **Verb does not match the effect:** a `GET` that changes data (`/orders/{id}/cancel` on `GET`), or a `POST` that only reads (a search with a body when query parameters would do).
- **`PUT` that is not a full replacement**, or a `PUT` whose repeated call has a different effect.
- **Status code hides what happened:** `200` after creating (should be `201` + `Location`), `200` with an error body, `500` for bad client input, `404` for a permission problem.
- **Error body outside the standard:** a custom error shape instead of Problem Details, or a validation error without the per-field `errors` list.
- **Breaking change without a new version:** a removed or renamed field, a narrower allowed value, a new required field (`conventions`, semantic versioning).
- **Lists without paging** or a page size without an upper limit.

Keep the base path the project already uses; do not suggest a different URL versioning scheme in a review.
