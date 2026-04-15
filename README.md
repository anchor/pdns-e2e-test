# PowerDNS E2E test framework

## 1. System overview

**Purpose:** End-to-end validation that the PHP HTTP bridge ([`php-wrapper/bridge.php`](php-wrapper/bridge.php)) correctly drives the **PowerDNS PHP wrapper** (`DN\PowerDNS\*` via Composer) against a live **PowerDNS Authoritative** API.

**Scope:** The tests do not call PowerDNS REST directly from Java. They exercise the wrapper **only** through the bridge’s `action` + HTTP verb contract. The bridge is a thin harness, not the library itself.

**System under test (SUT):** The Composer-installed PowerDNS PHP library used by `bridge.php`, as described in project context ([`.cursor/context/wrapper-doc.md`](.cursor/context/wrapper-doc.md)) and the [PHP library spec](https://confluence.newfold.com/display/APACDEV/PHP+Library+Spec?focusedCommentId=354869054#comment-354869054) (classes and usage).

**Target platform (contract):** PowerDNS Authoritative **4.7.4**; DNS concepts must align with the native API ([`.cursor/context/requirements.md`](.cursor/context/requirements.md), [`.cursor/context/powerdns-api.md`](.cursor/context/powerdns-api.md)).

---

## 2. Architecture

```plaintext
Java TestNG Framework
        ↓
PHP built-in server (localhost:8000, document root php-wrapper/)
        ↓
PHP Bridge (bridge.php)
        ↓
PowerDNS PHP Wrapper (DN\PowerDNS\API\API, Zones API, RRSets API)  [SUT]
        ↓
PowerDNS Authoritative HTTP API (e.g. /api/v1/servers/{server_id}/...)
```

- **Java:** TestNG + Rest Assured + Allure ([`pom.xml`](pom.xml), [`PowerDNSApiTest.java`](src/test/java/dreamscapenetworks/PowerDNSApiTest.java)).
- **Bridge:** Single script routes `?action=…` and HTTP method to library calls; returns JSON (or empty body on `204`).
- **Configuration:** `PDNS_API_URL`, `PDNS_API_KEY`, `PDNS_SERVER_ID` (env or `php-wrapper/config.local.php`). If URL is unset, bridge defaults to `http://127.0.0.1:5337/api/v1`. If API key is unset, non–`healthCheck` actions respond with **503** (see tests).

---

## 3. Execution workflow

1. **Prerequisites:** JDK **11+**, PHP with Composer dependencies installed under `php-wrapper/`, reachable PowerDNS API, valid API key in env or `config.local.php`.
2. **`mvn test`:** The `exec-maven-plugin` starts `php -S localhost:8000 -t php-wrapper/` asynchronously during `process-test-classes`, passing through `PDNS_*` env vars ([`pom.xml`](pom.xml)).
3. **Surefire** runs [`src/test/resources/testSuite.xml`](src/test/resources/testSuite.xml), which loads `dreamscapenetworks.PowerDNSApiTest`.
4. **`@BeforeClass`:** `GET /bridge.php?action=healthCheck` must return `200` and `{"status":"ok"}` (health check does **not** require `PDNS_API_KEY`).
5. **`@AfterMethod`:** Deletes zones registered during the test via `deleteZone` (best-effort cleanup).

**Log file:** Bridge appends to [`php-wrapper/powerdns_api.log`](php-wrapper/powerdns_api.log). Several tests assert substrings in this file; parallel test execution can interleave lines (not addressed in code).

---

## 4. Supported operations (bridge ↔ library)

Bridge `action` values map to library usage patterns described in the [PHP library spec](https://confluence.newfold.com/display/APACDEV/PHP+Library+Spec?focusedCommentId=354869054#comment-354869054) (Zones API / RRSets API) and implemented in [`bridge.php`](php-wrapper/bridge.php).

| `action` | HTTP | Library surface (spec alignment) |
|----------|------|-----------------------------------|
| `healthCheck` | GET | No library call; liveness only |
| `getZone` | GET | `$api->zones()->get($id)` |
| `createZone` | POST | `new Zone(...)` + optional RRsets JSON → `$api->zones()->create($zone)` |
| `updateZone` | PUT or PATCH | `new Zone(...)` + `$api->zones()->update($id, $zone)` |
| `deleteZone` | DELETE | Pre-check with `get`; `$api->zones()->delete($id)` or `{"deleted":false}` |
| `getAllRRSets` | GET | `$api->rrsets($zoneId)->getAll()` |
| `getSpecificRRSet` | GET | `$api->rrsets($zoneId)->get($name, $type?)` — `type` query optional |
| `replaceRRSet` | PUT | `$api->rrsets($zoneId)->replace([...])` |
| `replaceAllRRSets` | PUT | `replaceAll` if present, else `replace` (compat fallback in bridge) |
| `deleteRRSets` | DELETE | `$api->rrsets($zoneId)->delete([...])` |

**Not exposed by the bridge (no Java E2E here):** Listing all zones (`GET …/zones` / any `zones()->list`-style API) is **not** implemented in `bridge.php`. If the library adds it later, it remains **out of current bridge scope**.

---

## 5. Data models (JSON / responses)

Aligned with [`.cursor/context/wrapper-doc.md`](.cursor/context/wrapper-doc.md) and the [PHP library spec](https://confluence.newfold.com/display/APACDEV/PHP+Library+Spec?focusedCommentId=354869054#comment-354869054) entity names:

| Concept | In library (spec) | In bridge I/O |
|---------|-------------------|---------------|
| Zone | `DN\PowerDNS\Zone` | Create/update: JSON `name`, optional `rrsets` array |
| RRSet | `DN\PowerDNS\RRSet` | JSON `name`, `type` (enum **name** string, e.g. `A`, `MX`), `ttl`, `records`, optional `comments` |
| Record | `DN\PowerDNS\RRSetRecord` | JSON `content`, optional `disabled` |
| Comment | `DN\PowerDNS\RRSetComment` | JSON `content`, optional `account`, optional `modifiedAt` |
| Record types | `RecordType` enum in spec: A, AAAA, CNAME, MX, SRV, TXT, CAA, NS, SOA | Same set accepted when parsed by `recordTypeFromString()` in [`bridge.php`](php-wrapper/bridge.php) |

**Zone GET response shape (bridge):** `id`, `name`, `rrsets` (each RRset: `name`, `type`, `ttl`, `records`, `comments` via `rrsetToArray()`).

### 5.1 RRSet JSON contract (bridge input)

When building `RRSet` objects in [`mapInputToRRSets()`](php-wrapper/bridge.php), the bridge applies these rules:

| Field | Required | Semantics |
|-------|----------|-----------|
| `name` | Yes | Owner name (FQDN), string |
| `type` | Yes | `RecordType` enum **name** (e.g. `A`, `MX`) |
| `ttl` | No | Defaults to **3600** when omitted |
| `records` | No | Array of `{ "content": "…", "disabled"?: boolean }`. Omitted or `[]` yields an RRSet with **no** records in the PHP object; a zero-record **replace** may be rejected by PowerDNS—use **`deleteRRSets`** to remove an RRSet |
| `comments` | No | If the **`comments` key is present** and the value is an array (including **`[]`**), `setComments()` is called with that list, so **`"comments": []` clears** comments on replace. If **`comments` is omitted**, `setComments` is **not** called; whether existing comments survive a replace depends on the PHP library. If `comments` is present but not an array, the bridge returns **400** |

GET responses echo `records[].content`, `records[].disabled`, `comments[].content`, `comments[].account`, and `comments[].modifiedAt` (may be null) per `rrsetToArray()`.

**PowerDNS native RRset `changetype` (REPLACE, DELETE, …):** Described in [`.cursor/context/powerdns-api.md`](.cursor/context/powerdns-api.md). The bridge does **not** expose changetype strings; it maps actions to library methods.

---

## 6. Logging and error handling

**RequestLogger:** The [PHP library spec](https://confluence.newfold.com/display/APACDEV/PHP+Library+Spec?focusedCommentId=354869054#comment-354869054) defines `DN\PowerDNS\RequestLogger` with a `log(zoneId, url, serverId, method, request_data, response_data, http_code, …)` signature. The bridge implements this as **`FileLogger`** in [`bridge.php`](php-wrapper/bridge.php), writing one line per request to `powerdns_api.log`.

- **Every response** (including errors) triggers `FileLogger::log` in a `finally` block.
- **RRSets paths** also call `attachLibraryLogger()` so the library may emit additional log lines if it supports `setLogger()` on the RRsets API object.
- **Request payload JSON** (`req=` segment) is built by `buildRequestLogPayload()`: `action`, redacted `query`, **`url`** (API base URL), and optional `body`. Sensitive keys in nested structures are redacted (`api_key`, `apiKey`, `password`, `API_KEY`).

**Errors:**

- **`DN\PowerDNS\Exception`:** JSON `error`, `details` (when `errors` property exists); HTTP status from exception code if in 4xx–5xx, else **500**.
- **Other throwables (e.g. validation):** JSON `error` with message; HTTP from exception code when valid (e.g. **400**, **405**, **503**, **404** for invalid action).

**Operational:** Missing `PDNS_API_KEY` → **503** for any action except `healthCheck`.

---

## 7. Test cases covered

All cases live in [`PowerDNSApiTest.java`](src/test/java/dreamscapenetworks/PowerDNSApiTest.java).

| Test method | Behavior asserted |
|-------------|-------------------|
| `verifyEnvironment` | `healthCheck` **200**, `status=ok` |
| `testRetrieveExistingZone` | Create zone → `getZone` **200**, body keys; log contains `action=getZone`, `method=GET`, `"url":` |
| `testCreateZoneWithoutRRSets` | `createZone` **201**, `status=created`; log |
| `testDeleteZone` | `deleteZone` **204** |
| `testFetchAllRRSets` | `getAllRRSets` **200**, array size ≥ 0; log |
| `testFetchSpecificRRSet` | Zone + A record → `getSpecificRRSet` **200**, name/type/records; log |
| `testGetNonExistentZone` | **404**, `error` present |
| `testDeleteNonExistentZone` | **200**, `deleted=false` |
| `testReplaceRRSet` | `replaceRRSet` **200** + follow-up GET shows updated content |
| `testReplaceAllRRSets` | **200**, `status=replaced_all` |
| `testDeleteRRSets` | Delete then GET returns empty list (`size() == 0`) |
| `testUpdateZoneMetadata` | `updateZone` via **PATCH** **200**, `status=updated` |
| `testCreateZoneWithCommentOnRRSet` | MX + comment; GET shows comment content |
| `testInvalidJsonReturns400` | Malformed JSON on create → **400** |
| `testWrongHttpMethodReturns405` | Wrong method on `getZone` / `replaceRRSet` → **405** |
| `testNonHealthActionReturns503WhenApiKeyUnset` | **Skipped** unless `-Dpdns.e2e.noApiKey=true` and bridge has no key → **503** |
| `testMissingRequiredParametersReturn400` | Various missing `id` / `zoneId` / name / body fields → **400** |
| `testUnknownActionReturns404` | Unknown `action` → **404** |
| `testGetSpecificRRSetWithInvalidRecordTypeReturns400` | Invalid `type` string → **400** |
| `testReplaceRRSetMultiRecordDisabledFlag` | `replaceRRSet` with two A records, one `disabled: true` → GET asserts counts, contents, and disabled flag |
| `testRRSetCommentModifiedAtRoundTrip` | Create RRSet with fixed `modifiedAt` → GET echoes same epoch (client-set stamp; relax if PDNS normalizes) |
| `testRRSetMultipleCommentsRoundTrip` | Create with two comments → GET asserts both contents |
| `testReplaceRRSetOmitCommentsKeyPreservesExistingComments` | Replace with new records/TTL but **no** `comments` key → GET still shows prior comment (requires library to preserve when `setComments` is omitted) |
| `testReplaceRRSetUpdatesComments` | Replace with new `comments` array → GET shows updated comment text |
| `testReplaceRRSetWithEmptyCommentsClearsComments` | Replace with `"comments": []` → GET `comments` length **0** |
| `testDeleteRRSetsWithTwoKeys` | `deleteRRSets` body lists **two** name/type keys → both RRSets gone on GET |
| `testReplaceRRSetCommentsMustBeArrayWhenPresent` | `comments` key with non-array value → **400** |

---

## 8. Test coverage comparison (E2E vs PHP library spec)

Reference: [PHP library spec](https://confluence.newfold.com/display/APACDEV/PHP+Library+Spec?focusedCommentId=354869054#comment-354869054) (classes, usage, `RecordType`, `RequestLogger`, validation notes).

| Spec / library area | Covered by current E2E | Gap or note |
|---------------------|------------------------|-------------|
| `API` construction (URL, key, server_id) | Indirectly (bridge uses env/config) | No Java test varies bad URL/key except optional 503 test |
| `zones()->get` | Yes (`getZone`, missing zone) | — |
| `zones()->create` without RRsets | Yes | — |
| `zones()->create` with RRsets | Yes (A, MX + comments) | Spec also shows CNAME and multi-RRset create; E2E does not use every record type |
| `zones()->update` | Yes (**PATCH** only in tests) | Bridge allows **PUT**; not exercised in Java |
| `zones()->delete` | Yes + idempotent delete | Matches spec idea of non-fatal missing zone (bridge returns `deleted:false`) |
| List zones | No | **Not defined in current bridge** |
| `rrsets($zone)->getAll` | Yes | — |
| `rrsets($zone)->get($name)` only | Partially | Bridge supports omitting `type`; **no** dedicated Java test for name-only query |
| `rrsets($zone)->get($name, RecordType::X)` | Yes (A, MX) | Spec lists AAAA, CNAME, SRV, TXT, CAA, NS, SOA; E2E does not hit each enum in a happy path |
| `replace` / bulk replace | Yes (single-A replace + RRSet lifecycle cases) | Spec multi-RRset `replace` example not duplicated as a dedicated multi-RRSet **replace** body (multi-record **within** one RRSet is covered) |
| `replaceAll` | Yes (`replaced_all`) | If library lacks `replaceAll`, bridge falls back to `replace` (behavioral difference not asserted against PDNS state) |
| `delete` multiple keys | Yes (`testDeleteRRSetsWithTwoKeys`) | Matches spec “two keys” delete pattern |
| `RRSetRecord` disabled flag | Yes (`testReplaceRRSetMultiRecordDisabledFlag`) | Bridge maps `disabled`; multi-record GET asserts mixed flags |
| `RRSetComment` with timestamp | Yes (`testRRSetCommentModifiedAtRoundTrip`) | Bridge JSON `modifiedAt`; asserts client-supplied epoch on GET |
| Multi-comment RRSet | Yes (`testRRSetMultipleCommentsRoundTrip`, replace comment tests) | Order not guaranteed; assertions use `hasItems` / single index where appropriate |
| Comments: clear vs omit | Yes (`testReplaceRRSetWithEmptyCommentsClearsComments`, `testReplaceRRSetOmitCommentsKeyPreservesExistingComments`) | Depends on PHP library when `comments` key is omitted; see README §5.1 |
| In-memory `setRecords` / `setComments` on `RRSet` | No | Bridge uses JSON → `mapInputToRRSets`; not the spec’s mutate-then-call pattern |
| `RequestLogger` / `logRequest` example | Partially | FileLogger + optional library logger on RRsets; zones() calls do not attach library logger in bridge |
| Spec future: “batch update facility” | No | Explicitly noted as future in the PHP library spec; **not in repo** |

---

## 9. Running tests

```text
mvn test
```

Optional 503 scenario: start the PHP server **without** an API key, then:

```text
mvn test -Dpdns.e2e.noApiKey=true
```

Ensure `RestAssured` base URI matches the bridge host/port (default `http://localhost:8000` in [`PowerDNSApiTest.java`](src/test/java/dreamscapenetworks/PowerDNSApiTest.java)).
