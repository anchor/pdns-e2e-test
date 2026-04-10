# PowerDNS Authoritative API (Curated for E2E Framework)

## Overview

This document captures the essential parts of the PowerDNS Authoritative HTTP API required for validating the PHP wrapper and E2E test framework.

It focuses only on:
- Zones
- RRSets
- Core data models
- Status codes
- Behavioral rules

---

## Base Configuration

Base URL:
http://<host>:5337/api/v1

Authentication:
- Header: X-API-Key

Server Scope:
- All endpoints are prefixed with:
  /servers/{server_id}/
- Default server_id: localhost

---

## Zones API

### List Zones

GET /servers/{server_id}/zones

- 200 → returns array of zones
- Empty array if no zones exist

---

### Create Zone

POST /servers/{server_id}/zones

- 201 → zone created
- 422 → validation error

Notes:
- Zone name must include trailing dot (example.com.)
- May include rrsets during creation

---

### Retrieve Zone

GET /servers/{server_id}/zones/{zone_id}

- 200 → returns full Zone object (includes rrsets)
- 404 → zone not found

---

### Delete Zone

DELETE /servers/{server_id}/zones/{zone_id}

- 204 → successfully deleted (no response body)
- 404 → zone not found

Important:
- No response body is returned on success

---

## RRSet Operations

PowerDNS does NOT expose separate CRUD endpoints for RRSets.

All operations are performed via:

PATCH /servers/{server_id}/zones/{zone_id}

---

### Supported Change Types

Each RRSet operation must include a "changetype":

- REPLACE → create or update RRSet
- DELETE → remove RRSet
- EXTEND → add records to existing RRSet
- PRUNE → remove specific records

---

### RRSet Payload Structure

```json
{
  "rrsets": [
    {
      "name": "www.example.com.",
      "type": "A",
      "ttl": 3600,
      "changetype": "REPLACE",
      "records": [
        {
          "content": "192.0.2.10",
          "disabled": false
        }
      ]
    }
  ]
}