---
name: tdd
description: Use this skill when writing code, fixing bugs, adding features, or refactoring. This skill enforces Test-Driven Development — write a failing test first, make it pass with the simplest code, then refactor. No production code without a failing test.
---

# Test-Driven Development

## When This Skill Applies

**Use this skill whenever you write or change code.** Every new feature, bug fix, and refactoring follows the TDD loop. No production code is written without a failing test driving it.

## The Core Loop

```
RED → GREEN → REFACTOR → RED → ...
```

### RED Phase
Write a failing test that describes the behavior you want. The test should:
- Use domain language, not technical jargon
- Describe WHAT, not HOW
- Be a concrete example, not an abstract statement

```clojure
;; BAD: Abstract
(it "can add numbers" ...)

;; GOOD: Concrete example
(it "when adding 2 + 3, returns 5" ...)
```

### GREEN Phase
Write the **simplest possible code** to make the test pass. Two strategies:

1. **Fake It** - Return a hardcoded value
   ```clojure
   (defn add [a b]
     5) ; Simplest thing!
   ```

2. **Obvious Implementation** - If you know the solution
   ```clojure
   (defn add [a b]
     (+ a b))
   ```

**Prefer Fake It** when learning or unsure. Let more tests drive the real implementation.

### REFACTOR Phase
This is where **design happens**. Look for:
- Duplication (but wait for Rule of Three)
- Long functions to extract
- Poor names to improve
- Complex conditions to simplify

## The Three Laws of TDD

1. **No production code** without a failing test
2. **No more test code** than sufficient to fail (compilation failures count)
3. **No more production code** than sufficient to pass the one failing test

## The Rule of Three

**Only extract duplication when you see it THREE times.**

Why? Wrong abstractions are worse than duplication. Wait for the pattern to emerge.

```clojure
;; Duplication #1 - Leave it
;; Duplication #2 - Note it, leave it
;; Duplication #3 - NOW extract it
```

## Triangulation

Each new test "sculpts" the solution toward a general, robust implementation.

Think of **degrees of freedom** - like a car that needs forward/back, left/right, and rotation. Each test carves out one degree of freedom until the implementation handles all cases.

## Transformation Priority Premise

When going from RED to GREEN, prefer simpler transformations:

| Priority | Transformation |
|----------|----------------|
| 1 | {} → nil |
| 2 | nil → constant |
| 3 | constant → variable |
| 4 | unconditional → conditional |
| 5 | scalar → collection |
| 6 | statement → recursion |
| 7 | value → mutated value |

Higher priority = simpler. Avoid jumping to complex transformations too early.

## Arrange-Act-Assert with Speclj

Structure every test:

```clojure
(describe "Order"
  (it "calculates total with discount"
    ;; ARRANGE - Set up the world
    (let [order {:items [{:price 100}]}
          discount {:type :percent :value 10}]
      ;; ACT - Execute the behavior
      (let [total (calculate-total order discount)]
        ;; ASSERT - Verify the outcome
        (should= 90 total)))))
```

## Writing Tests Backwards

Sometimes it helps to write AAA in reverse:
1. Write the ASSERT first - what do you want to verify?
2. Write the ACT - what action produces that result?
3. Write the ARRANGE - what setup is needed?

```clojure
;; Start with what you want to verify
(should= 90 total)

;; What produces that?
(let [total (calculate-total order discount)]
  (should= 90 total))

;; What setup is needed?
(let [order {:items [{:price 100}]}
      discount {:type :percent :value 10}]
  (let [total (calculate-total order discount)]
    (should= 90 total)))
```

## Test Naming Principles

- Use **behavior-driven names** with domain language
- Provide **concrete examples**, not abstract statements
- **One example per test** for easy debugging
- Avoid leaking implementation details

```clojure
;; BAD: Technical, implementation-focused
(it "should set the data property to 1" ...)

;; GOOD: Behavior-focused, domain language
(it "recognizes 'mom' as a palindrome" ...)
(it "returns 5 when adding 2 and 3" ...)
(it "applies 20% discount for premium users" ...)
```

## TDD Example: Building a Calculator

```clojure
;; RED: First failing test
(describe "Calculator"
  (it "returns 5 when adding 2 and 3"
    (should= 5 (add 2 3))))

;; GREEN: Simplest passing implementation
(defn add [a b]
  5) ; Fake it!

;; RED: Another test to triangulate
(it "returns 7 when adding 3 and 4"
  (should= 7 (add 3 4)))

;; GREEN: Now we need the real implementation
(defn add [a b]
  (+ a b))

;; REFACTOR: Nothing to refactor yet...

;; RED: New feature
(it "returns 0 when adding 0 and 0"
  (should= 0 (add 0 0)))

;; GREEN: Already passes! Good test coverage indicator.
```

## TDD Example: Building a Stack

```clojure
;; RED: Empty stack
(describe "Stack"
  (it "is empty when created"
    (should (empty-stack? (make-stack)))))

;; GREEN
(defn make-stack [] [])
(defn empty-stack? [stack] (empty? stack))

;; RED: Push makes it non-empty
(it "is not empty after push"
  (should-not (empty-stack? (push (make-stack) 1))))

;; GREEN
(defn push [stack item]
  (conj stack item))

;; RED: Pop returns pushed item
(it "returns pushed item when popped"
  (let [stack (-> (make-stack) (push 1))]
    (should= 1 (peek-stack stack))))

;; GREEN
(defn peek-stack [stack]
  (peek stack))

;; RED: LIFO behavior
(it "returns items in LIFO order"
  (let [stack (-> (make-stack) (push 1) (push 2))]
    (should= 2 (peek-stack stack))))

;; Already passes! The design emerges.
```

## Classic vs Mockist TDD

**Classic (Detroit/Chicago) TDD:**
- Test with real dependencies
- Higher confidence, slower tests
- Best for: Pure functions, domain logic

**Mockist (London) TDD:**
- Mock external dependencies
- Faster tests, more isolated
- Best for: Functions with infrastructure dependencies

Start with Classic TDD to learn the technique. Add mocks when testing code with databases, APIs, etc.

```clojure
;; Classic: Use real collaborators
(describe "OrderService"
  (it "calculates total correctly"
    (let [pricing (->StandardPricing)
          order {:items [{:price 100}]}]
      (should= 100 (calculate-total pricing order)))))

;; Mockist: Mock infrastructure
(describe "OrderService"
  (it "saves order to database"
    (let [saved (atom nil)]
      (with-redefs [db/save! #(reset! saved %)]
        (save-order! {:id "123"})
        (should= "123" (:id @saved))))))
```

## Clean Test Output

**Test output must be clean.** The only output from a test run should be the test framework's own reporting (dots, summaries, failures). No `println` noise.

Production code that prints (CLI progress, logging, etc.) **must be silenced during tests**. When calling functions that print to stdout in test code, wrap them:

```clojure
;; BAD: println from production code clutters test output
(it "parses features"
  (parse-features! dir output-dir)  ; prints "Parsing foo.feature -> ..."
  (should ...))

;; GOOD: suppress stdout in tests
(it "parses features"
  (with-out-str (parse-features! dir output-dir))
  (should ...))
```

If you need the return value from a function that prints:

```clojure
(it "returns exit code 0"
  (let [result (atom nil)]
    (with-out-str (reset! result (run-features dir)))
    (should= 0 @result)))
```

**Before closing a bead, run the full test suite and verify the output is clean** — only dots and the summary line. If you see any text that isn't from the test framework, fix it.

## Common Mistakes

1. **Writing code before tests** - Violates the fundamental principle
2. **Writing too much test** - Just enough to fail
3. **Writing too much code** - Just enough to pass
4. **Skipping refactor** - This is where design lives
5. **Testing implementation** - Test behavior, not how it's done
6. **Abstract test names** - Use concrete examples
7. **Extracting too early** - Wait for Rule of Three
8. **Noisy test output** - Production println leaking into test runs

## TDD Rhythm

```
1. Think about what you want to achieve
2. Write a test expressing that
3. See it fail (confirms test works)
4. Write minimal code to pass
5. See it pass
6. Look for refactoring opportunities
7. Refactor if needed (tests still pass)
8. Repeat
```

## When TDD Feels Hard

- **Don't know what to test?** → Start with the simplest case
- **Test is hard to write?** → Design might need simplification
- **Many mocks needed?** → Too many dependencies, consider redesign
- **Tests are brittle?** → Testing implementation, not behavior
- **Tests are slow?** → Too many integration tests, add more unit tests
