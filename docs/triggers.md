# Triggers and Events

Mobilerun Portal exposes trigger management over the local `ContentProvider`, the local WebSocket JSON-RPC API, and the reverse WebSocket connection used by cloud control. The rule and run payloads use the same `TriggerJson` schema that Portal persists internally; there is no separate external trigger schema.

Trigger endpoints are headless-safe: `triggers/*` methods remain available even when the Accessibility Service is not connected.

## Catalog and Status

`triggers/catalog` returns:

- `schemaVersion`
- `eventTypes`
- `triggerSources`
- `runDispositions`
- `stringMatchModes`
- `networkTypes`
- `thresholdComparisons`
- `sourceMetadata`

`triggers/status` returns:

- `accessibilityServiceConnected`
- `notificationAccessEnabled`
- `receiveSmsGranted`
- `readContactsGranted`
- `exactAlarmAvailable`
- `ruleCount`
- `runCount`
- `schemaVersion`

`schemaVersion` is `TriggerJson.CURRENT_SCHEMA_VERSION`.

## JSON-RPC Methods

These methods are available over the local WebSocket server and over reverse connection:

| Method | Params |
| --- | --- |
| `triggers/catalog` | none |
| `triggers/status` | none |
| `triggers/rules/list` | none |
| `triggers/rules/get` | `{ "ruleId": "..." }` |
| `triggers/rules/save` | `{ "rule": { ...TriggerJson rule... } }` |
| `triggers/rules/delete` | `{ "ruleId": "..." }` |
| `triggers/rules/setEnabled` | `{ "ruleId": "...", "enabled": true }` |
| `triggers/rules/test` | `{ "ruleId": "..." }` |
| `triggers/runs/list` | `{ "limit": 50 }` |
| `triggers/runs/delete` | `{ "runId": "..." }` |
| `triggers/runs/clear` | none |

Example:

```json
{
  "id": "rule-save-1",
  "method": "triggers/rules/save",
  "params": {
    "rule": {
      "id": "rule-1",
      "name": "Morning check-in",
      "source": "TIME_DAILY",
      "promptTemplate": "Summarize new notifications",
      "dailyHour": 9,
      "dailyMinute": 0
    }
  }
}
```

## ContentProvider Endpoints

All trigger URIs use the authority `content://com.mobilerun.portal/`.

### Query URIs

- `content://com.mobilerun.portal/triggers/catalog`
- `content://com.mobilerun.portal/triggers/status`
- `content://com.mobilerun.portal/triggers/rules`
- `content://com.mobilerun.portal/triggers/rules/<ruleId>`
- `content://com.mobilerun.portal/triggers/runs?limit=<n>`

### Mutation URIs

- `content://com.mobilerun.portal/triggers/rules/save`
  Required values: `rule_json` or `rule_json_base64`
- `content://com.mobilerun.portal/triggers/rules/delete`
  Required value: `rule_id`
- `content://com.mobilerun.portal/triggers/rules/set_enabled`
  Required values: `rule_id`, `enabled`
- `content://com.mobilerun.portal/triggers/rules/test`
  Required value: `rule_id`
- `content://com.mobilerun.portal/triggers/runs/delete`
  Required value: `run_id`
- `content://com.mobilerun.portal/triggers/runs/clear`

Examples:

```bash
adb shell content query --uri content://com.mobilerun.portal/triggers/catalog
adb shell content query --uri content://com.mobilerun.portal/triggers/status
adb shell content query --uri content://com.mobilerun.portal/triggers/rules
adb shell content query --uri content://com.mobilerun.portal/triggers/rules/rule-1
adb shell content query --uri 'content://com.mobilerun.portal/triggers/runs?limit=20'

adb shell content insert --uri content://com.mobilerun.portal/triggers/rules/save \
  --bind rule_json_base64:s:"BASE64_RULE_JSON"

adb shell content insert --uri content://com.mobilerun.portal/triggers/rules/set_enabled \
  --bind rule_id:s:"rule-1" \
  --bind enabled:b:false

adb shell content insert --uri content://com.mobilerun.portal/triggers/rules/test \
  --bind rule_id:s:"rule-1"

adb shell content insert --uri content://com.mobilerun.portal/triggers/rules/delete \
  --bind rule_id:s:"rule-1"

adb shell content insert --uri content://com.mobilerun.portal/triggers/runs/delete \
  --bind run_id:s:"run-1"

adb shell content insert --uri content://com.mobilerun.portal/triggers/runs/clear
```

## Save Validation

External saves use the same validation logic as the trigger editor UI:

- `name` is required.
- `promptTemplate` is required.
- `cooldownSeconds` must be `0` or greater when the source supports cooldown.
- `maxLaunchCount` must be positive when provided.
- `BATTERY_LEVEL_CHANGED.thresholdValue` must be in `0..100`.
- `TIME_DELAY.delayMinutes` must be greater than `0`.
- `TIME_ABSOLUTE.absoluteTimeMillis` must be present and in the future.
- `TIME_DAILY` and `TIME_WEEKLY` require `dailyHour` and `dailyMinute`.
- `TIME_WEEKLY` also requires at least one weekday.

## EventType Contract

The current emitted `EventType` names are:

- `NOTIFICATION`
- `NOTIFICATION_POSTED`
- `NOTIFICATION_REMOVED`
- `APP_ENTERED`
- `APP_EXITED`
- `BATTERY_LOW`
- `BATTERY_OKAY`
- `BATTERY_LEVEL_CHANGED`
- `POWER_CONNECTED`
- `POWER_DISCONNECTED`
- `USER_PRESENT`
- `NETWORK_CONNECTED`
- `NETWORK_TYPE_CHANGED`
- `SMS_RECEIVED`
- `PING`
- `PONG`
- `UNKNOWN`

Notes:

- `NOTIFICATION` is a legacy compatibility event.
- `PING` and `PONG` are protocol events.
- `UNKNOWN` is internal fallback and should not be treated as a stable business event type.
- Backend storage should generally key off the specific device events such as `NOTIFICATION_POSTED`, `APP_ENTERED`, `BATTERY_LEVEL_CHANGED`, and `SMS_RECEIVED`.

## TriggerSource Contract

The current rule `TriggerSource` names are:

- `TIME_DELAY`
- `TIME_ABSOLUTE`
- `TIME_DAILY`
- `TIME_WEEKLY`
- `NOTIFICATION_POSTED`
- `NOTIFICATION_REMOVED`
- `APP_ENTERED`
- `APP_EXITED`
- `BATTERY_LOW`
- `BATTERY_OKAY`
- `BATTERY_LEVEL_CHANGED`
- `POWER_CONNECTED`
- `POWER_DISCONNECTED`
- `USER_PRESENT`
- `NETWORK_CONNECTED`
- `NETWORK_TYPE_CHANGED`
- `SMS_RECEIVED`

Time-based sources are trigger-rule sources only. They are not emitted device events.

## Event Payload Keys

- `NOTIFICATION_POSTED`: `package`, `title`, `text`, `id`, `tag`, `is_ongoing`, `post_time`, `key`
- `NOTIFICATION_REMOVED`: `package`, `id`, `key`, `removed`
- `NOTIFICATION`: legacy compatibility event; posted payload matches `NOTIFICATION_POSTED`, removed payload matches `NOTIFICATION_REMOVED`
- `APP_ENTERED`: `package`, optional `previous_package`
- `APP_EXITED`: `package`, `next_package`
- `BATTERY_LEVEL_CHANGED`: `battery_level`, `is_charging`
- `BATTERY_LOW`: `battery_level`
- `BATTERY_OKAY`: `battery_level`
- `POWER_CONNECTED`: `battery_level`
- `POWER_DISCONNECTED`: `battery_level`
- `USER_PRESENT`: no payload fields
- `NETWORK_CONNECTED`: `network_type`
- `NETWORK_TYPE_CHANGED`: `network_type`, `previous_network_type`
- `SMS_RECEIVED`: `phone_number`, `message`, optional `contact_name`
- `PING`: protocol keepalive; clients usually send it without a payload
- `PONG`: protocol reply; payload is typically `"pong"`
- `UNKNOWN`: internal event with no stable payload contract
