---
name: c3kit
description: Use this skill when working in projects that depend on c3kit libraries (apron, bucket, wire, scaffold). Covers the schema system, database API, web layer, app lifecycle, and conventions specific to the c3kit ecosystem.
---

# c3kit (Clean Coders Clojure Kit)

## When This Skill Applies

Use this skill whenever you work in a project that depends on any c3kit library. Check `deps.edn` or `project.clj` for `com.cleancoders.c3kit/apron`, `c3kit/bucket`, `c3kit/wire`, or `c3kit/scaffold`.

## Library Overview

| Library | Purpose | Key Namespaces |
|---|---|---|
| **apron** | Foundation — schemas, time, logging, app lifecycle, utilities | `schema`, `legend`, `corec`, `time`, `log`, `app`, `env` |
| **bucket** | Database abstraction — uniform API over multiple backends | `api`, `memory`, `datomic`, `jdbc`, `seed`, `bg` |
| **wire** | Web layer — AJAX, WebSocket, REST, JWT, assets, flash, Redis | `apic`, `ajax`, `websocket`, `flash`, `jwt`, `routes` |
| **scaffold** | Build tooling — CLJS compilation, CSS compilation | `cljs`, `css` |

## Apron: Foundation

### Schema System

Schemas are plain maps. Each field is a spec with `:type`, optional `:validate`, `:coerce`, `:present`, and `:message` keys.

```clojure
(def user
  {:kind  (schema/kind :user)
   :id    {:type :long}
   :name  {:type :string :validate :present}
   :email {:type :string :validate :email}
   :role  {:type :string}})
```

**Built-in types:** `:string`, `:int`, `:long`, `:float`, `:double`, `:boolean`, `:date`, `:timestamp`, `:uuid`, `:keyword`, `:ref`, `:any`

**Sequences:** Wrap a spec in a vector: `{:tags [{:type :string}]}`

**Nested schemas:** Use a map as the spec: `{:address {:street {:type :string} :city {:type :string}}}`

### Four-Operation Model

| Operation | Purpose | Non-bang | Bang |
|---|---|---|---|
| **coerce** | Convert raw values to typed values | `schema/coerce` | `schema/coerce!` |
| **validate** | Check values against rules | `schema/validate` | `schema/validate!` |
| **conform** | Coerce then validate | `schema/conform` | `schema/conform!` |
| **present** | Transform for display/output | `schema/present` | `schema/present!` |

Non-bang returns entity with error objects in failed fields. Bang throws. Use `schema/error?` to check, `schema/message-map` or `schema/message-seq` to extract messages.

```clojure
;; Conform an entity — coerce + validate in one step
(let [result (schema/conform user-schema raw-data)]
  (if (schema/error? result)
    (handle-errors (schema/message-map result))
    (save! result)))

;; Or use bang to throw on failure
(schema/conform! user-schema raw-data)
```

More complete documentation for Schema is available here: https://github.com/cleancoders/c3kit-apron/blob/master/SCHEMA.md

### Legend

A legend is a registry of schemas indexed by `:kind`. Build it once, then use kind-dispatched operations.

```clojure
(def legend (legend/build [user order product]))

;; Dispatch by :kind
(legend/coerce! legend {:kind :user :name 123})
(legend/conform! legend {:kind :order :total "42.50"})
```

### Corec (`ccc`)

Always alias as `ccc`. Provides cross-platform utilities designed for `:refer :all` in some contexts but typically aliased.

Key functions:
- **Collections:** `conjv`, `dissocv`, `assocv`, `removev` — vector operations
- **Filtering:** `ffilter`, `find-by`, `ffind-by` — first-match filtering
- **Logic:** `nand`, `nor`, `xor`
- **Options:** `->options` — normalizes variadic key-value or map args
- **Identity:** `new-uuid` — cross-platform UUID generation
- **No-op:** `noop` — useful for stubbing `println` in tests

```clojure
;; ffilter — like (first (filter ...))
(ccc/ffilter #(= :admin (:role %)) users)

;; ->options — normalize args
(defn configure! [& options]
  (swap! config merge (ccc/->options options)))
```

### Time

Starting point for dealing with time.

Creation:

```clojure
(time/now)
(time/utc 2026 3 31)
(time/local 2026 3 31 14 13)
```

Human-readable duration constructors. Designed for threading:

```clojure
(-> 5 time/minutes time/ago)        ;; 5 minutes ago
(-> 1 time/days time/from-now)      ;; tomorrow
(time/before (time/now) (-> 30 time/seconds))  ;; 30 seconds before now
```

**Parsing/unparsing** with named formats:

```clojure
(time/parse :dense "20240115")
(time/unparse :http-date some-date)
```

### App Lifecycle

The `app` namespace manages application state and service lifecycle.

```clojure
;; Define services as start/stop symbol pairs
(def my-service (app/service 'myapp.db/start 'myapp.db/stop))

;; Start services
(app/start! [my-service other-service])

;; Access app state
(app/resolution! :db)   ;; throws if missing
(app/resolution :db)    ;; returns nil if missing
```

Services are started/stopped via symbols resolved at runtime (`requiring-resolve`), enabling decoupled initialization.

### Environment

Priority chain for environment values: overrides -> system properties -> env vars -> `.env` file.

```clojure
(env/env "DATABASE_URL")           ;; resolve from chain
(env/override! "PORT" "3000")      ;; set override for testing
```

### Logging

Always alias as `log`. Wraps Timbre.

```clojure
(log/info "Starting server on port" port)
(log/warn "Deprecated: use foo instead")
(log/error e "Request failed")

;; Capture logs in tests
(log/capture-logs
  (do-something)
  (should-contain "Starting" (log/captured-logs-str)))
```

## Bucket: Database

### The `api` Namespace

All database operations go through `c3kit.bucket.api`. It provides a uniform API across Datomic, JDBC (Postgres, SQLite3, MSSQL, H2), in-memory, and IndexedDB backends.

### Global Impl

A global atom holds the current DB implementation. Most functions use it implicitly. Functions suffixed with `-` take an explicit `db` parameter.

```clojure
;; Implicit (uses global impl)
(api/entity :user 123)
(api/find :user :name "alice")
(api/tx {:kind :user :name "alice"})

;; Explicit (takes db as first arg)
(api/entity- my-db :user 123)
(api/find- my-db :user :name "alice")
```

### Core Operations

```clojure
;; Create/update — tx handles both
(api/tx {:kind :user :name "Alice" :email "alice@example.com"})

;; Read
(api/entity :user 123)                          ;; by id
(api/find :user :name "Alice")                   ;; returns seq
(api/ffind :user :name "Alice")                  ;; returns first match

;; Delete
(api/delete {:kind :user :id 123})

;; Count
(api/count :user :role "admin")

;; Reduce (streaming, doesn't load all into memory)
(api/reduce :user conj [] :active true)
```

### Find Options

The `find` API accepts data-driven query options:

```clojure
;; Filtering
(api/find :user :role "admin" :active true)

;; With options map
(api/find :user {:where    {:role "admin"}
                 :order-by [:name :asc]
                 :take     10
                 :drop     20})

;; Operators in filters
(api/find :order :total ['> 100])
(api/find :user :name ['like "Ali%"])
(api/find :product :category ['not= "archived"])
```

### Safety Guard

Destructive operations require safety to be off:

```clojure
;; This will throw in production
(api/clear)

;; Disable safety for tests/migrations
(api/with-safety-off
  (api/clear))
```

### Soft Delete

```clojure
(api/soft-delete entity)  ;; returns {:kind k :id id :db/delete? true}
(api/delete? entity)      ;; checks if entity is soft-deleted
```

### Compare-and-Swap (CAS)

Optimistic locking via metadata:

```clojure
(api/tx (api/cas {:version 3} updated-entity))
```

### Seed Entities

Deref-able entities that auto-create on first access:

```clojure
(def admin-user (seed/entity :user {:name "Admin" :role "admin"}))

;; First deref creates, subsequent derefs return cached
@admin-user  ;; => {:kind :user :id 1 :name "Admin" :role "admin"}
```

### Testing with Bucket

Use `spec-helperc/with-schemas` to set up a fresh in-memory DB per test context:

```clojure
(describe "User Service"
  (helperc/with-schemas [user order])

  (it "creates a user"
    (let [result (api/tx {:kind :user :name "Alice"})]
      (should= "Alice" (:name result)))))
```

This sets up an in-memory DB, turns safety off, and clears between tests.

### Background Tasks

Schedule recurring tasks with `bg`:

```clojure
(bg/start! {:task     'myapp.jobs/cleanup
            :schedule "0 0 * * *"
            :name     "nightly-cleanup"})
```

### Supported Backends

| Backend | Config `:impl` | Platform |
|---|---|---|
| In-memory | `:memory` | clj, cljs |
| Datomic On-Prem | `:datomic` | clj |
| Datomic Cloud | `:datomic-cloud` | clj |
| PostgreSQL | `:jdbc` + `:dialect :postgres` | clj |
| SQLite3 | `:jdbc` + `:dialect :sqlite3` | clj |
| H2 | `:jdbc` + `:dialect :h2` | clj |
| MSSQL | `:jdbc` + `:dialect :mssql` | clj |
| Reagent Memory | via `re-memory` | cljs |
| IndexedDB | via `indexeddb` | cljs |

## Wire: Web Layer

### API Responses (`apic`)

All communication channels (AJAX, WebSocket, REST) share the same response shape:

```clojure
(apic/ok)                              ;; {:status :ok}
(apic/ok payload)                      ;; {:status :ok :payload payload}
(apic/ok payload "Success!")           ;; with flash message
(apic/fail "Validation failed")        ;; {:status :fail :flash [...]}
(apic/error "Server error")            ;; {:status :error :flash [...]}
(apic/redirect "/login")               ;; {:status :redirect :uri "/login"}
```

Check responses: `apic/ok?`, `apic/fail?`, `apic/error?`

### AJAX

**Server-side** — middleware stack for Transit serialization:

```clojure
(-> handler
    ajax/wrap-ajax)
```

**Client-side** — request functions:

```clojure
(ajax/post! "/api/users" {:name "Alice"}
  :on-ok #(handle-success %)
  :on-fail #(handle-failure %))

(ajax/get! "/api/users"
  :on-ok #(reset! users (:payload %)))
```

### WebSocket

**Server-side:**

```clojure
;; Configure handlers
(websocket/configure!
  :handler-key 'myapp.ws/handle-message)

;; In handlers, return apic responses
(defn handle-message [request]
  (apic/ok {:result "processed"}))
```

**Client-side:**

```clojure
(websocket/call! :my/action {:data "value"}
  :on-ok #(process-response %))
```

### REST Client

Sync and async HTTP methods:

```clojure
(rest/get! "https://api.example.com/users")
(rest/post! "https://api.example.com/users" {:name "Alice"})
(rest/put! "https://api.example.com/users/1" {:name "Bob"})
(rest/delete! "https://api.example.com/users/1")
```

REST response constructors in `restc`:

```clojure
(restc/ok body)
(restc/created body)
(restc/bad-request body)
(restc/not-found)
(restc/unauthorized)
```

### Flash Messages

**Server-side** — attach to Ring responses. **Client-side** — Reagent atom with auto-timeout:

```clojure
;; Create flash messages
(flashc/success "Saved!")
(flashc/warn "Are you sure?")
(flashc/error "Something went wrong")

;; Client: add to UI state
(flash/add! (flashc/success "Done!"))
```

### JWT Authentication

```clojure
;; Sign
(jwt/sign {:user-id 123} secret)

;; Middleware
(-> handler
    (jwt/wrap-jwt {:secret secret}))
```

### Asset Fingerprinting

MD5-based cache-busting for static assets:

```clojure
;; Middleware strips fingerprints from incoming requests
(-> handler
    assets/wrap-asset-fingerprint)

;; In templates, add fingerprints
(assets/add-fingerprint "/css/app.css")
;; => "/css/app-a1b2c3d4.css"
```

### Routes

```clojure
;; Lazy routes for development (reloads on each request)
(routes/lazy-routes 'myapp.routes/app-routes)

;; Redirect routes
(routes/redirect-routes
  {"/" "/dashboard"
   "/old-page" "/new-page"})
```

### Lock and MessageQueue

Pluggable infrastructure with in-memory (testing) and Redis (production) backends:

```clojure
;; Distributed locking
(lock/with-lock "my-resource"
  (do-exclusive-work))

;; Message queue
(mq/enqueue :my-topic {:data "value"})
(mq/on-message :my-topic
  (fn [msg] (process msg)))
```

### Testing with Wire

Wire provides rich test helpers in `spec_helper` and `spec_helperc`:

```clojure
;; Assert AJAX responses
(should-be-ajax-ok response)
(should-be-ajax-fail response "Expected message")
(should-redirect-to response "/login")

;; Mock AJAX in CLJS tests
(should-have-invoked-ajax-post "/api/users")

;; Mock WebSocket
(should-have-invoked-ws :my/action)
```

## Scaffold: Build Tooling

### ClojureScript Compilation

Configure in `config/cljs.edn`:

```edn
{:development {:output-dir "resources/public/cljs/dev"
               :output-to  "resources/public/cljs/dev/main.js"
               :main       myapp.main}
 :production  {:output-dir "resources/public/cljs/prod"
               :output-to  "resources/public/cljs/prod/main.js"
               :main       myapp.main
               :optimizations :advanced}}
```

Run: `clj -M:cljs once development` or `clj -M:cljs auto development`

### CSS from Garden

Configure in `config/css.edn`:

```edn
{:styles-var myapp.styles/styles
 :output-to  "resources/public/css/app.css"}
```

Run: `clj -M:css once` or `clj -M:css auto`

## Common Mistakes

1. **Using `api/find` without knowing the return type** — `find` returns a seq, `ffind` returns a single entity, `entity` looks up by id
2. **Forgetting safety guard** — `api/clear` and `api/delete-all` throw unless wrapped in `api/with-safety-off`
3. **Skipping `conform!`** — always conform entities through their schema before persisting
4. **Returning values from `defthen` without asserting** — produces 0 assertions (see gherclj skill)
5. **Using `clojure.test` assertions** — the ecosystem uses speclj (`should=`, `should-throw`, etc.)
6. **Wrong alias for `corec`** — it's `ccc`, not `corec` or `core`
7. **Calling protocol methods directly** — use the public API functions (`api/entity`, not `(-entity impl ...)`), protocol methods are prefixed with `-` to signal they're internal
8. **Ignoring the `-` suffix convention** — `api/find-` takes an explicit db; `api/find` uses the global impl
