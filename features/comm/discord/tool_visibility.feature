Feature: Discord comm offers the crew's configured tools
  The Discord comm channel must surface the crew's :tools.allow set
  on every turn, like every other comm. The core invariant (every
  channel that drives a turn offers the same tools) lives in Isaac's
  bridge tests; this file verifies the Discord-side of that contract.

  Background:
    Given default Grover setup
    And the crew "main" allows tools: "read,write,exec"
    And the Discord Gateway is faked in-memory
    And Discord is configured with:
      | key   | value    |
      | token | test-tok |
    And the Discord client is ready as bot "isaac"

  Scenario: Discord surfaces the crew's configured tools
    When Discord sends MESSAGE_CREATE:
      | channel_id | 1  |
      | content    | hi |
      | author.id  | 2  |
    Then the prompt has tools:
      | name  |
      | read  |
      | write |
      | exec  |
