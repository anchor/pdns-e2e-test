# PowerDNS PHP Wrapper Documentation (Curated)

## Overview

This library provides a PHP abstraction over the PowerDNS Authoritative API.

It enables:
- Zone management (CRUD)
- RRSet management (CRUD via abstraction)
- Record and comment handling
- Structured logging via RequestLogger

The library is accessed through a central API class and exposes domain-specific APIs for zones and rrsets.

---

## Core Entry Point

### API Class

```php
$api = new API(
  'http://10.24.23.3:5337/api/v1',
  'API_KEY',
  'localhost'
);