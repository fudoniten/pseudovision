> **ARCHIVED (2026-07):** a narrow, one-off incident note rather than living
> documentation. The underlying gotcha (below) is presumably still true of
> Malli, but this isn't a maintained reference — treat as historical.

# Malli Schema Validation Error: `:merge` Operator Fix

## Problem

When adding pagination parameters (`:limit`, `:offset`) to routes that already have other query parameters (e.g., UUID filtering), developers may attempt to use the `:merge` operator to combine multiple Malli schemas:

```clojure
;; ❌ INVALID — causes MalliCoreInvalidSchemaException
:parameters {:query [:merge
                      [:map [:limit {:optional true} int?]
                            [:offset {:optional true} int?]]
                      [:map [:uuid {:optional true} uuid?]]]}
```

**Error:**
```
Caused by: clojure.lang.ExceptionInfo: :malli.core/invalid-schema
{:schema :merge, :form [:merge [:map ...] [:map ...]]}
```

**Root cause:** Malli does not support `:merge` as a top-level operator for query parameter schemas in Reitit.

## Solution

Flatten all query parameters into a single `:map` schema:

```clojure
;; ✅ CORRECT — all params in one map
:parameters {:query [:map
                     [:limit  {:optional true} :int]
                     [:offset {:optional true} :int]
                     [:uuid   {:optional true} :uuid]]}
```

## When to Apply This Fix

Search for `:merge` patterns in query parameter schemas:
```bash
grep -rn ":merge" src/pseudovision/http/ --include="*.clj"
```

If found, flatten all parameters into a single `:map`.

## Testing After Fix

1. Rebuild: `docker build -t pseudovision:fixed .`
2. Verify schema compilation: Pod should start without Malli validation errors
3. Test endpoint with pagination: `curl "http://localhost:8080/api/items?limit=10&offset=20&kind=filler"`
