@wip
Feature: Discord comm_send resolves ${VAR} secrets in the token on the live send path
  The outbound send path reads discord config live (so channel-name mappings
  hot-reload without a restart). It must resolve ${VAR} secret references in the
  token the same way the boot/gateway path does — otherwise the raw "${VAR}"
  string is sent as the bearer token and Discord rejects it with 401.

  Regression guard for: the live read (runtime-discord-cfg) bypassing
  resolve-env-values and sending "Bot ${...}" instead of the real token.

  Background:
    Given default Grover setup in "/test/discord-send-token"
    And the Discord Gateway is faked in-memory
    And a file ".env" exists with content "DISCORD_BOT_TOKEN=real-secret-token"
    And Discord is configured with:
      | key                        | value                |
      | discord/token              | ${DISCORD_BOT_TOKEN} |
      | discord/channels.C999.name | announcements        |
      | crew                       | main                 |
    And Discord outbound comm is registered

  Scenario: send! resolves a ${VAR} token reference to the real secret
    When Discord comm send! is invoked with:
      | path           | value         |
      | discord/target | announcements |
      | content        | Red alert!    |
    Then a Discord outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method                | POST                  |
      | headers.Authorization | Bot real-secret-token |
      | body.content          | Red alert!            |
