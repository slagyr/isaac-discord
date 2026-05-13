---
name: todo
description: Convert TODO comments in the codebase into beans. Use when the user says "/todo" or asks to create beans from TODOs.
user-invocable: true
---

# Create Beans from TODOs

Convert TODO comments in the code into beans.

1. Find all TODO comments in the codebase.
2. Skip any TODOs that already have a bean id.
3. Consolidate similar TODOs into a single bean when appropriate.
4. Create a bean for each TODO (or group): `beans create "Title" --type=task --body "Context..."`.
5. Update each TODO comment to include the bean id, e.g.: `TODO (isaac-abcd) — original text...`.
6. Commit the bean files together with the source-file TODO annotations.
