Feature: Discord session frequencies
  Each Discord channel's routing config is a session-frequencies map validated
  by the shared schema. Inbound MESSAGE_CREATE events resolve channel frequencies
  through isaac.session.frequencies before dispatching the turn.

  Background:
    Given default Grover setup in "/test/discord-frequencies"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.users  | ["123"]    |
      | comms.discord.discord/allow-from.guilds | ["G789"]   |
      | sessions.naming-strategy                | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: route by session-tags via the shared resolver
    Given config:
      | comms.discord.discord/channels.C999.session-tags | #{:project/coil} |
    And the following sessions exist:
      | name    | tags             |
      | coil-wk | #{:project/coil} |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | routed  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then session "coil-wk" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | routed          |

  Scenario: with-model override flows to the inbound turn
    Given the following sessions exist:
      | name    |
      | kitchen |
    And config:
      | comms.discord.discord/channels.C999.session    | kitchen |
      | comms.discord.discord/channels.C999.with-model | grover2 |
    And the isaac EDN file "config/models/grover2.edn" exists with:
      | path           | value    |
      | model          | echo-alt |
      | provider       | grover   |
      | context-window | 16384    |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | routed  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then session "kitchen" has transcript matching:
      | type    | message.role | message.model | message.content |
      | message | user         |               | hello           |
      | message | assistant    | echo-alt      | routed          |