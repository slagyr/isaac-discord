---
name: clojure
description: Use this skill when writing, reviewing, or refactoring Clojure or ClojureScript code. Covers naming, formatting, function design, state management, error handling, threading, and cross-platform conventions.
---

# Clojure

## When This Skill Applies

Use this skill whenever you write, review, or refactor Clojure (`.clj`), ClojureScript (`.cljs`), cross-platform (`.cljc`), or Babashka (`.bb`) code.

## Naming Conventions

### Suffixes and Prefixes

| Convention | Meaning | Examples |
|---|---|---|
| `!` suffix | Side effect or throws on error | `configure!`, `start!`, `conform!` |
| `?` suffix | Predicate (returns truthy/falsy) | `ok?`, `open?`, `error?` |
| `->` prefix | Conversion/construction | `->boolean`, `->json`, `->options` |
| `<-` prefix | Reverse conversion | `<-json`, `<-transit` |
| `-` prefix | Private intent, but needs direct testing | `-do-request`, `-apply-take` |
| `*` suffix | Bulk or variadic variant | `success*`, `warn*` |
| `wrap-` prefix | Ring middleware | `wrap-ajax`, `wrap-jwt` |

### Private Functions

Use `defn-` for truly private functions that don't need direct testing. Do not use `^:private` metadata.

```clojure
;; GOOD
(defn- resolve-config [path] ...)

;; BAD
(defn ^:private resolve-config [path] ...)
```

When a function is private in intent but needs to be tested directly, use the `-` prefix instead of `defn-`. This makes the function public (so tests can call it) while signaling "don't call this from outside":

```clojure
;; Private intent, but testable
(defn -parse-query [raw-input] ...)

;; In the test file:
(it "parses a simple query"
  (should= {:field "name"} (sut/-parse-query "name")))
```

Use `defn-` when the function is a small helper that gets sufficient coverage through public functions. Use `-` prefix when the function has enough logic to warrant its own tests.

## Formatting

### Indentation

2-space indentation throughout. No tabs.

### Aligned `let` Bindings

Align values in `let` bindings with extra whitespace for readability:

```clojure
(let [legend     (atom (legend/build schemas))
      db-schemas (->> (flatten schemas) (mapcat ->db-schema))
      api        (->Api config (atom nil))
      db         (->DB db-schemas legend config api)]
  ...)
```

### Aligned Map Values

Align values in map literals:

```clojure
{:kind  (s/kind :user)
 :id    {:type :long}
 :name  {:type :string}
 :email {:type :string}}
```

### Namespace Forms

`:require` goes on its own line. Each entry is indented and on its own line. Imports use parenthesized form. **Sort all entries alphabetically** — this makes duplicates easy to spot.

```clojure
(ns myapp.core
  (:import (java.io File)
           (java.net URL))
  (:require
    [c3kit.apron.corec :as ccc]
    [c3kit.apron.log :as log]
    [clojure.string :as str]
    [myapp.db :as db]
    [myapp.util :as util]))
```

### Closing Parens

Trailing — on the same line as the last expression. Never on their own line.

```clojure
;; GOOD
(defn add [a b]
  (+ a b))

;; BAD
(defn add [a b]
  (+ a b)
)
```

## Namespace Aliasing

Use consistent, short aliases:

| Namespace | Alias |
|---|---|
| `c3kit.apron.corec` | `ccc` |
| `c3kit.apron.log` | `log` |
| `c3kit.apron.schema` | `schema` (or `s`) |
| `c3kit.apron.time` | `time` |
| `c3kit.apron.util` | `util` |
| `c3kit.apron.utilc` | `utilc` |
| `clojure.string` | `str` |
| `clojure.java.io` | `io` |

In tests, alias the system under test as `sut`:

```clojure
[myapp.auth :as sut]
```

### Cross-Platform Namespace Naming

Shared `.cljc` namespaces use a `c` suffix to distinguish from platform-specific wrappers:

```
apic.cljc      — cross-platform API core
api.clj        — server-specific API
api.cljs       — client-specific API
```

## Function Design

### Keep Functions Small

Most functions should be 1-5 lines. If a function is getting long, extract helpers.

### Multi-Arity with Progressive Defaults

Build arities that layer on top of each other:

```clojure
(defn ok
  ([] {:status :ok})
  ([payload] {:payload payload :status :ok})
  ([payload msg] (flash-success (ok payload) msg)))
```

### Variadic Options with `->options`

Use `ccc/->options` to accept both map and key-value pair arguments:

```clojure
(defn configure! [& options]
  (swap! config merge (ccc/->options options)))

;; Both work:
(configure! :host "localhost" :port 3000)
(configure! {:host "localhost" :port 3000})
```

## Threading Macros

Use the right threading macro for the job:

```clojure
;; -> for data/associative pipelines
(-> request
    wrap-params
    wrap-session
    wrap-auth)

;; ->> for collection pipelines
(->> users
     (filter active?)
     (map :email)
     (into #{}))

;; cond-> for conditional transforms
(cond-> base-query
        limit  (assoc :take limit)
        offset (assoc :drop offset))

;; some-> for nil-safe chains
(some-> entity schema/message-map validation-errors)
```

Use threading macros judiciously. Don't force a pipeline when a `let` binding is clearer.

## State Management

### `defonce` for Module-Level State

Use `defonce` so REPL reloads don't reset state:

```clojure
(defonce impl (atom nil))
(defonce config (atom default-config))
```

### `volatile!` for Uncoordinated Flags

Use `volatile!` for simple flags that don't need atomic coordination:

```clojure
(defonce running (volatile! true))
```

### Configuration Pattern

Centralize state initialization in a `configure!` function:

```clojure
(def config (atom default-config))

(defn configure! [& options]
  (swap! config merge (ccc/->options options)))
```

## Error Handling

### `ex-info` with Data Maps

Use `ex-info` for structured errors. Custom exception types are used in the rare situation that they need to be caught.

```clojure
(throw (ex-info "Entity missing!" {:kind kind :id id}))
```

### `try/catch` at Boundaries Only

Don't sprinkle `try/catch` through business logic. Catch at system boundaries (HTTP handlers, message consumers, top-level entry points).

## Cross-Platform Code

### Reader Conditionals

Use `#?(:clj ... :cljs ...)` for platform-specific code. For larger blocks, wrap in `do`:

```clojure
#?(:clj  (java.util.UUID/fromString v)
   :cljs (uuid v))

#?(:clj
   (do
     (defn server-only-fn [] ...)
     (defn another-server-fn [] ...))
   :cljs
   (do
     (defn client-only-fn [] ...)
     (defn another-client-fn [] ...)))
```

### Babashka Support

Use `#?(:bb ...)` reader conditionals when Babashka needs different behavior.

## Protocols and Abstractions

### `deftype` over `defrecord` for Protocol Implementations

Use `deftype` when implementing protocols. Access fields via `.-fieldname`.

```clojure
(deftype MemoryDB [schemas legend store]
  DB
  (-entity [_this kind id]
    (get-in @store [kind id]))
  (-tx [_this entity]
    (swap! store assoc-in [(:kind entity) (:id entity)] entity)))
```

### Protocol Methods

Protocol methods follow the same `-` prefix convention when they are internal implementation details wrapped by public API functions. The `-` signals "callers should use the public wrapper, not this directly."

```clojure
;; Protocol — internal, wrapped by public functions
(defprotocol DB
  (-clear [this])
  (-entity [this kind id])
  (-find [this kind options]))

;; Public API — what callers actually use
(defn entity [kind id] (-entity @impl kind id))
(defn find [kind & args] (-find @impl kind args))
```

Not all protocol methods need the `-` prefix. Use it when there's a public wrapper that adds convenience (e.g., implicit `impl` lookup). Skip it when callers use the protocol methods directly.

### Multimethods

Mutltimethods offer the most light-weight option for decoupling code and should be preferred when dispatching based on configurations.  

```clojure
(defmulti send-email! (-> config/active :email :service))
(defmethod send-email! :ses [email] (comment "send through ses"))
(defmethod send-email! :smtp [email] (comment "send through smtp"))
```

When deftype/defrecords are needed at runtime, multimethods may be useful as factory methods.

```clojure
(defmulti create-impl :impl)
(defmethod create-impl :memory [config] (->MemoryDB config))
(defmethod create-impl :postgres [config] (->PostgresDB config))
```

## Code Organization

### Region Comments

Use region markers to organize large files:

```clojure
;; region ----- Validations -----
...
;; endregion ^^^^^ Validations ^^^^^
```

### Schemas and Validation

Exposed data structure should always be defined and validated using [c3kit.apron.schema](https://github.com/cleancoders/c3kit-apron). Internal data structures that are never exposed to clients or external services do not need typically need their schemas defined.  But any data structure that enters the system or is exposed to clients of the system does need a schema defined and the schema should be used the validate the data. 

Define entity schemas as plain maps with keyword keys:

```clojure
(def user
  {:kind  (s/kind :user)
   :id    {:type :long}
   :name  {:type :string}
   :email {:type :string :validate :present}
   :role  {:type :string}})
```

## Testing with Speclj

Speclj is the preferred unit testing library.  See [the TDD skill](https://github.com/slagyr/agent-lib/blob/main/skills/tdd/SKILL.md) for usage.

### Stubs and Mocks

Use `with-stubs`, `stub`, and `should-have-invoked`:

```clojure
(context "saving"
  (with-stubs)
  (redefs-around [db/save! (stub :save)])

  (it "saves the entity"
    (sut/create-user! "alice")
    (should-have-invoked :save)))
```

### Whimsical Test Data

Use fun, memorable names for test entities: `bibelot`, `thingy`, `doodad`, `whatsamajigger`.

## c3kit

Clean Coders Clojure Kit is a set of libraries that are preferred for the following scenarios:

| Library                                                       | Purpose |
|---------------------------------------------------------------|---|
| [**apron**](https://github.com/cleancoders/c3kit-apron)       | Foundation — schemas, time, logging, app lifecycle, utilities |
| [**bucket**](https://github.com/cleancoders/c3kit-bucket)     | Database abstraction — uniform API over multiple backends
| [**wire**](https://github.com/cleancoders/c3kit-wire)         | Web layer — AJAX, WebSocket, REST, JWT, assets, flash, Redis |
| [**scaffold**](https://github.com/cleancoders/c3kit-scaffold) | Build tooling — CLJS compilation, CSS compilation |

See the [c3kit skill](https://github.com/slagyr/agent-lib/blob/main/skills/c3kit/SKILL.md) for more information.

## Common Mistakes

1. **Using `defrecord` where `deftype` is appropriate** — reach for `deftype` when implementing protocols
2. **Mutable state without `defonce`** — REPL reloads will reset your atoms
3. **Throwing everywhere** — return errors as data; only throw at boundaries or in `!` variants
4. **Over-destructuring in function signatures** — destructure in `let` when it aids readability
5. **Skipping alignment** — aligned `let` bindings and maps are a readability win
6. **Wrong threading macro** — `->` for associative, `->>` for collections
7. **`^:private` instead of `defn-`** — use the shorthand
8. **Missing `!` on side-effecting functions** — if it mutates state or throws, it needs a bang
