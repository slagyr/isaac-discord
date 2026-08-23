Feature: Discord send! waits out gateway flaps
  Outbound comm_send deliveries POST via Discord REST. REST is independent
  of the Gateway websocket, but a down gateway is the observed signal that
  the connection is flapping — send! must not claim success (or burn retry
  attempts) while a gateway client is present and not READY. The delivery
  worker honors :defer? by leaving the record pending.

  When no gateway client is attached (outbound-only hosts), send! still
  POSTs — see comm_send_target.feature.

  Background:
    Given default Grover setup in "/test/discord-send-flap"
    And the Discord Gateway is faked in-memory
    And Discord is configured with:
      | key           | value      |
      | discord/token | test-token |
      | crew          | main       |
    And the Discord client is ready as bot "harbormaster-bot"

  Scenario: send! posts while the gateway is READY
    When Discord comm send! is invoked with:
      | path    | value                      |
      | target  | C999                       |
      | content | Lantern trimmed and ready. |
    Then a Discord outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method                | POST                       |
      | headers.Authorization | Bot test-token             |
      | body.content          | Lantern trimmed and ready. |

  Scenario: send! does not HTTP-post while the gateway is down
    When Discord closes the connection with code 1006
    And Discord comm send! is invoked with:
      | path    | value                           |
      | target  | C999                            |
      | content | Hold the watch — lantern's out. |
    Then no Discord outbound HTTP request was made
    And the log has entries matching:
      | level | event                             | channelId | status         |
      | :warn | :discord.send/gateway-unavailable | C999      | :disconnected  |
