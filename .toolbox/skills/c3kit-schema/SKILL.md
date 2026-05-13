---
name: c3kit-schema
description: Use this skill when working with c3kit.apron.schema — defining schemas, calling coerce/validate/conform/present, handling errors, or writing custom spec functions. Covers the full API including nested types, entity-level specs, and error handling.
---

# c3kit.apron.schema

Full reference: https://raw.githubusercontent.com/cleancoders/c3kit-apron/refs/heads/master/SCHEMA.md

## The Silent Failure Trap

**Non-bang functions never throw. They return the entity with error objects embedded in failed fields.**

If you call a non-bang function and ignore the return value, errors vanish silently.

```clojure
;; BAD — errors silently discarded
(schema/conform user-schema raw-input)
(save! raw-input)

;; GOOD — use bang to throw on failure
(let [user (schema/conform! user-schema raw-input)]
  (save! user))

;; GOOD — use non-bang when you want to handle errors explicitly
(let [result (schema/conform user-schema raw-input)]
  (if (schema/error? result)
    (render-errors (schema/message-map result))
    (save! result)))
```

Never call `schema/coerce`, `schema/validate`, `schema/conform`, or `schema/present` and ignore the return value.

## The Four Operations

| Operation | Purpose | Non-bang | Bang |
|---|---|---|---|
| **coerce** | Convert raw values to typed values | `schema/coerce` | `schema/coerce!` |
| **validate** | Check values against rules | `schema/validate` | `schema/validate!` |
| **conform** | Coerce then validate | `schema/conform` | `schema/conform!` |
| **present** | Transform for display/output | `schema/present` | `schema/present!` |

**Prefer `conform!`** for config and boundary validation where invalid input should fail fast.
Use non-bang only when you intend to handle errors in the return value.

## Defining a Schema

A schema is a plain map. Each key maps to a spec.

```clojure
(def user
  {:kind  (schema/kind :user)   ;; enforces :kind value
   :name  {:type :string :validate :present :message "is required"}
   :email {:type :string :validate :email}
   :age   {:type :int}})
```

### Spec Keys

| Key | Purpose |
|---|---|
| `:type` | Type for coercion and type-validation (see Types below) |
| `:message` | Default error message for coerce and validate failures |
| `:coerce` | Function or list of functions `(fn [v] ...)` → coerced value |
| `:validate` | Function or list of functions `(fn [v] ...)` → truthy/falsy |
| `:validations` | List of `{:validate fn :message "..."}` maps for per-rule messages |
| `:present` | Single function `(fn [v] ...)` → presentable value (no list) |
| `:value` | Exact expected value (used by `schema/kind`) |

### Multiple Validations with Per-Rule Messages

```clojure
{:type        :int
 :message     "must be an int"           ;; used for type-validation failure
 :validations [{:validate even? :message "must be even"}
               {:validate #(<= 0 % 100) :message "out of range"}]}
```

Type-validation runs first. If it fails, other validations are skipped.

## Entity-Level Specs

Use `:*` for cross-field rules. All functions receive the whole entity.

```clojure
(def order
  {:kind   (schema/kind :order)
   :total  {:type :double}
   :tax    {:type :double}
   :*      {:amount-due {:coerce #(+ (:total %) (:tax %))
                         :validate pos?
                         :message "must be positive"}}})
```

Entity-level specs can add new computed fields or validate existing ones.

## Nested Structures

### Nested map

```clojure
(def line {:kind  (schema/kind :line)
           :start {:type :map :schema point}
           :end   {:type :map :schema point}})
```

### Sequence of typed values

```clojure
(def polygon {:kind   (schema/kind :polygon)
              :points {:type :seq
                       :spec {:type :map :schema point}
                       :validations [{:validate #(>= (count %) 3) :message "need at least 3 points"}]}})
```

All values in a seq must conform to the same spec.

### One of several schemas

```clojure
{:type :one-of :specs [{:type :map :schema point}
                       {:type :map :schema circle}]}
```

`schema` tries each spec in order; uses the first that succeeds.

## Handling Errors

```clojure
;; Check for any error
(schema/error? result)   ;; => true/false

;; Get flat list of messages
(schema/message-seq result)
;; => ["start.x can't coerce \"blah\" to int"]

;; Get nested map of messages (most common for UIs)
(schema/message-map result)
;; => {:start {:x "can't coerce \"blah\" to int"}}

;; Shortcuts — conform/coerce/validate + message-map in one call
(schema/conform-message-map schema entity)   ;; => nil or error map
(schema/coerce-message-map schema entity)
(schema/validate-message-map schema entity)
```

## Good to Know

**Unspecified fields are dropped** — any key not in the schema is removed by schema operations.

**Merging schemas** — use `schema/merge-schemas` to add context-specific validations without duplicating:

```clojure
(schema/merge-schemas base-schema {:email {:validate :unique-email :message "already taken"}})
```

**Validate your schema** — use `schema/conform-schema!` to catch malformed spec definitions early.

**Shorthands** — schema accepts abbreviated specs (e.g. `{:type [point]}` for a seq of points) and expands them on the fly. Prefer explicit forms for clarity.

## Types

```clojure
:any        ;; no type-coercion or type-validation
:bigdec     ;; BigDecimal in clj, number in cljs
:boolean
:date       ;; java.sql.Date in clj — sql databases only
:double     ;; number in cljs
:float      ;; number in cljs
:fn         ;; implements iFn
:ignore     ;; same as :any
:instant    ;; java.util.Date in clj, js/Date in cljs
:int
:keyword
:kw-ref     ;; same as :keyword, Datomic ident
:long
:map        ;; must also specify :schema
:one-of     ;; must also specify :specs
:ref        ;; same as :int, Datomic reference
:seq        ;; must also specify :spec
:string
:timestamp  ;; java.sql.Timestamp in clj — sql databases only
:uri        ;; java.net.URI in clj, string in cljs
:uuid       ;; java.util.UUID in clj, cljs.core/UUID in cljs
```
