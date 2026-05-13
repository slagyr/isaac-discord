Feature: Discord Gateway idle handling
  The Gateway adapter polls the WebSocket transport with a short
  timeout to share the reader thread between read and shutdown
  checks. A polling timeout means "no frame yet," not "connection
  closed" — only the listener's onClose / onError signals a real
  disconnect. Without this distinction, the client hangs up on
  itself in the brief idle window between IDENTIFY and READY.

  Background:
    Given the Discord Gateway is faked in-memory
    And config:
      | log.output          | memory     |
      | comms.discord.token | test-token |

  Scenario: idle gap between IDENTIFY and READY does not synthesize a close
    When the Discord client connects
    And Discord sends HELLO:
      | heartbeat_interval | 45000 |
    And Discord stays silent for 250 milliseconds
    Then the Discord client is connected
    And the log has no entries matching:
      | event                          |
      | :discord.gateway/disconnected  |
      | :discord.gateway/fatal-close   |

  Scenario: idle gap after READY does not synthesize a close
    Given the Discord client is ready as bot "bot-default"
    When Discord stays silent for 1000 milliseconds
    Then the Discord client is connected
    And the log has no entries matching:
      | event                          |
      | :discord.gateway/disconnected  |

  Scenario: a real close with a status code still propagates the code
    Given the Discord client is ready as bot "bot-default"
    When Discord closes the connection with code 4014 reason "Disallowed intents"
    Then the log has entries matching:
      | level  | event                        | status | reason              |
      | :error | :discord.gateway/fatal-close | 4014   | Disallowed intents  |
