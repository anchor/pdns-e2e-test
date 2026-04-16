<?php
declare(strict_types=1);

require_once __DIR__ . '/vendor/autoload.php';

use DN\PowerDNS\API\API;
use DN\PowerDNS\Zone;
use DN\PowerDNS\RRSet;
use DN\PowerDNS\RRSetRecord;
use DN\PowerDNS\RRSetComment;
use DN\PowerDNS\RecordType;
use DN\PowerDNS\RequestLogger;
use DN\PowerDNS\Exception as PowerDNSException;

$localFile = __DIR__ . '/config.local.php';
$local = is_file($localFile) ? require $localFile : [];

$url = getenv('PDNS_API_URL') ?: ($local['api_url'] ?? '');
$apiKey = getenv('PDNS_API_KEY') ?: ($local['api_key'] ?? '');
$serverId = getenv('PDNS_SERVER_ID') ?: ($local['server_id'] ?? 'localhost');

if ($url === '') {
    $url = 'http://127.0.0.1:5337/api/v1';
}

$action = $_GET['action'] ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$rawInput = file_get_contents('php://input') ?: '';
$input = $rawInput !== '' ? json_decode($rawInput, true) : null;
if ($rawInput !== '' && $input === null && json_last_error() !== JSON_ERROR_NONE) {
    $input = false;
}

$requestTimestamp = time();
$zoneIdForLog = '';
$httpCode = 500;
$responseBody = '';
$noJsonBody = false;

$fileLogger = new FileLogger();
$requestLogPayload = buildRequestLogPayload($action, $input, $_GET, $url);

try {
    if ($action === 'healthCheck') {
        if ($method !== 'GET') {
            throw new RuntimeException('Method Not Allowed', 405);
        }
        $httpCode = 200;
        $responseBody = json_encode(['status' => 'ok'], JSON_THROW_ON_ERROR);
    } else {
        if ($apiKey === '') {
            throw new RuntimeException('PDNS_API_KEY not configured (set env or php-wrapper/config.local.php)', 503);
        }

        $api = new API($url, $apiKey, $serverId);

        switch ($action) {
            case 'getZone':
                if ($method !== 'GET') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['id'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $zone = $api->zones()->get($zoneId);
                if ($zone === null) {
                    $httpCode = 404;
                    $responseBody = json_encode(['error' => 'Zone not found'], JSON_THROW_ON_ERROR);
                } else {
                    $httpCode = 200;
                    $responseBody = json_encode([
                        'id' => $zoneId,
                        'name' => $zone->name,
                        'rrsets' => rrsetsToArray($zone->getRRSets()),
                    ], JSON_THROW_ON_ERROR);
                }
                break;

            case 'createZone':
                if ($method !== 'POST') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $body = requireJsonArray($input);
                if (empty($body['name']) || !is_string($body['name'])) {
                    throw new RuntimeException('name required', 400);
                }
                $zoneIdForLog = $body['name'];
                $zone = new Zone($body['name']);
                if (isset($body['rrsets']) && is_array($body['rrsets'])) {
                    $zone->setRRSets(mapInputToRRSets($body['rrsets']));
                }
                $api->zones()->create($zone);
                $httpCode = 201;
                $responseBody = json_encode(['status' => 'created'], JSON_THROW_ON_ERROR);
                break;

            case 'updateZone':
                if ($method !== 'PUT' && $method !== 'PATCH') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['id'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $body = requireJsonArray($input);
                if (empty($body['name']) || !is_string($body['name'])) {
                    throw new RuntimeException('name required', 400);
                }
                $zone = new Zone($body['name']);
                $api->zones()->update($zoneId, $zone);
                $httpCode = 200;
                $responseBody = json_encode(['status' => 'updated'], JSON_THROW_ON_ERROR);
                break;

            case 'deleteZone':
                if ($method !== 'DELETE') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['id'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $existing = $api->zones()->get($zoneId);
                if ($existing === null) {
                    $httpCode = 200;
                    $responseBody = json_encode(['deleted' => false], JSON_THROW_ON_ERROR);
                } else {
                    $api->zones()->delete($zoneId);
                    $httpCode = 204;
                    $responseBody = '';
                    $noJsonBody = true;
                }
                break;

            case 'getAllRRSets':
                if ($method !== 'GET') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['zoneId'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('Zone ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $rrApi = $api->rrsets($zoneId);
                attachLibraryLogger($rrApi, $fileLogger);
                $rrsets = $rrApi->getAll();
                $httpCode = 200;
                $responseBody = json_encode(rrsetsToArray($rrsets), JSON_THROW_ON_ERROR);
                break;

            case 'getSpecificRRSet':
                if ($method !== 'GET') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['zoneId'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('Zone ID required', 400);
                }
                $name = $_GET['name'] ?? null;
                if ($name === null || $name === '') {
                    throw new RuntimeException('RRSet Name required', 400);
                }
                $zoneIdForLog = $zoneId;
                $typeString = $_GET['type'] ?? null;
                $type = null;
                if ($typeString !== null && $typeString !== '') {
                    $type = recordTypeFromString($typeString);
                }
                $rrApi = $api->rrsets($zoneId);
                attachLibraryLogger($rrApi, $fileLogger);
                $rrsets = $rrApi->get($name, $type);
                $httpCode = 200;
                $responseBody = json_encode(rrsetsToArray($rrsets), JSON_THROW_ON_ERROR);
                break;

            case 'replaceRRSet':
                if ($method !== 'PUT') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['zoneId'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('Zone ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $body = requireJsonArray($input);
                if (empty($body['rrsets']) || !is_array($body['rrsets'])) {
                    throw new RuntimeException('rrsets array required', 400);
                }
                $rrApi = $api->rrsets($zoneId);
                attachLibraryLogger($rrApi, $fileLogger);
                $rrApi->replace(mapInputToRRSets($body['rrsets']));
                $httpCode = 200;
                $responseBody = json_encode(['status' => 'replaced'], JSON_THROW_ON_ERROR);
                break;

            case 'replaceAllRRSets':
                if ($method !== 'PUT') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['zoneId'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('Zone ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $body = requireJsonArray($input);
                if (empty($body['rrsets']) || !is_array($body['rrsets'])) {
                    throw new RuntimeException('rrsets array required', 400);
                }
                $mapped = mapInputToRRSets($body['rrsets']);
                $rrApi = $api->rrsets($zoneId);
                attachLibraryLogger($rrApi, $fileLogger);
                if (method_exists($rrApi, 'replaceAll')) {
                    $rrApi->replaceAll($mapped);
                } else {
                    $rrApi->replace($mapped);
                }
                $httpCode = 200;
                $responseBody = json_encode(['status' => 'replaced_all'], JSON_THROW_ON_ERROR);
                break;

            case 'deleteRRSets':
                if ($method !== 'DELETE') {
                    throw new RuntimeException('Method Not Allowed', 405);
                }
                $zoneId = $_GET['zoneId'] ?? null;
                if ($zoneId === null || $zoneId === '') {
                    throw new RuntimeException('Zone ID required', 400);
                }
                $zoneIdForLog = $zoneId;
                $body = requireJsonArray($input);
                if (empty($body['rrsets']) || !is_array($body['rrsets'])) {
                    throw new RuntimeException('rrsets array required', 400);
                }
                $rrApi = $api->rrsets($zoneId);
                attachLibraryLogger($rrApi, $fileLogger);
                $rrApi->delete(mapInputToRRSetKeys($body['rrsets']));
                $httpCode = 200;
                $responseBody = json_encode(['status' => 'deleted'], JSON_THROW_ON_ERROR);
                break;

            default:
                throw new RuntimeException('Invalid Action', 404);
        }
    }
} catch (PowerDNSException $e) {
    $httpCode = httpStatusFromCode($e->getCode(), 500);
    $responseBody = json_encode([
        'error' => $e->getMessage(),
        'details' => powerDnsExceptionErrors($e),
    ], JSON_THROW_ON_ERROR);
} catch (Throwable $e) {
    $httpCode = httpStatusFromCode($e->getCode(), 400);
    if ($httpCode < 400 || $httpCode > 599) {
        $httpCode = 400;
    }
    $responseBody = json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
} finally {
    $fileLogger->log(
        $zoneIdForLog,
        $url,
        $serverId,
        $method,
        $requestLogPayload,
        $noJsonBody ? '(no content)' : $responseBody,
        $httpCode,
        $requestTimestamp,
        time()
    );

    http_response_code($httpCode);
    if ($noJsonBody && $httpCode === 204) {
        header('Content-Type: text/plain');
        echo '';
    } else {
        header('Content-Type: application/json; charset=utf-8');
        echo $responseBody;
    }
}

/**
 * @param mixed $input
 * @return array<string, mixed>
 */
function requireJsonArray(mixed $input): array
{
    if ($input === false || !is_array($input)) {
        throw new RuntimeException('Invalid JSON body', 400);
    }

    return $input;
}

function buildRequestLogPayload(string $action, mixed $input, array $query, string $apiBaseUrl): string
{
    $payload = [
        'action' => $action,
        'query' => redactSensitive($query),
        'url' => $apiBaseUrl,
    ];
    if (is_array($input)) {
        $payload['body'] = redactSensitive($input);
    } elseif (is_string($input)) {
        $payload['body'] = $input;
    }

    return json_encode($payload, JSON_UNESCAPED_SLASHES) ?: '{}';
}

/**
 * @param array<string, mixed> $data
 * @return array<string, mixed>
 */
function redactSensitive(array $data): array
{
    $keys = ['api_key', 'apiKey', 'password', 'API_KEY'];
    foreach ($keys as $k) {
        if (array_key_exists($k, $data)) {
            $data[$k] = '***';
        }
    }

    return $data;
}

function httpStatusFromCode(int $code, int $fallback): int
{
    if ($code >= 400 && $code <= 599) {
        return $code;
    }

    return $fallback;
}

function powerDnsExceptionErrors(PowerDNSException $e): array
{
    if (property_exists($e, 'errors')) {
        /** @var mixed $err */
        $err = $e->errors;

        return is_array($err) ? $err : [];
    }

    return [];
}

/**
 * @param object $rrApi
 */
function attachLibraryLogger(object $rrApi, RequestLogger $logger): void
{
    if (method_exists($rrApi, 'setLogger')) {
        $rrApi->setLogger($logger);
    }
}

function recordTypeFromString(string $type): RecordType
{
    foreach (RecordType::cases() as $case) {
        if ($case->name === $type) {
            return $case;
        }
    }

    throw new RuntimeException('Invalid record type: ' . $type, 400);
}

/**
 * @param array<int, mixed> $rrsets
 * @return array<int, array<string, mixed>>
 */
function rrsetsToArray(array $rrsets): array
{
    $out = [];
    foreach ($rrsets as $r) {
        $out[] = rrsetToArray($r);
    }

    return $out;
}

function rrsetToArray(object $r): array
{
    $typeVal = $r->type ?? null;
    if ($typeVal instanceof \UnitEnum) {
        $typeName = $typeVal->name;
    } else {
        $typeName = (string) $typeVal;
    }

    $records = [];
    if (method_exists($r, 'getRecords')) {
        foreach ($r->getRecords() as $rec) {
            $records[] = [
                'content' => $rec->content,
                'disabled' => $rec->disabled ?? false,
            ];
        }
    }

    $comments = [];
    if (method_exists($r, 'getComments')) {
        foreach ($r->getComments() as $c) {
            $comments[] = [
                'content' => $c->content,
                'account' => $c->account ?? '',
                'modifiedAt' => $c->modifiedAt ?? null,
            ];
        }
    }

    return [
        'name' => $r->name,
        'type' => $typeName,
        'ttl' => $r->ttl ?? 3600,
        'records' => $records,
        'comments' => $comments,
    ];
}

/**
 * Maps JSON rrset objects to RRSet instances.
 *
 * Comments: if the JSON object has a "comments" key and its value is an array (including []),
 * RRSet::setComments() is called so an explicit empty array clears comments on replace. If
 * "comments" is omitted, setComments is not called (library-dependent whether existing comments
 * survive a replace).
 *
 * Records: "records" is optional; omitted or empty yields an RRSet with no records. Prefer
 * deleteRRSets to remove an RRSet if the server rejects a zero-record replace.
 *
 * @param array<int, array<string, mixed>> $data
 * @return array<int, RRSet>
 */
function mapInputToRRSets(array $data): array
{
    $rrsets = [];
    foreach ($data as $item) {
        $records = [];
        if (!empty($item['records']) && is_array($item['records'])) {
            foreach ($item['records'] as $r) {
                if (!is_array($r) || !isset($r['content'])) {
                    throw new RuntimeException('Each record requires content', 400);
                }
                $records[] = new RRSetRecord(
                    (string) $r['content'],
                    (bool) ($r['disabled'] ?? false)
                );
            }
        }

        $comments = [];
        $hasCommentsKey = array_key_exists('comments', $item);
        if ($hasCommentsKey) {
            if (!is_array($item['comments'])) {
                throw new RuntimeException('comments must be an array when provided', 400);
            }
            foreach ($item['comments'] as $c) {
                if (!is_array($c) || !isset($c['content'])) {
                    throw new RuntimeException('Each comment requires content', 400);
                }
                $comments[] = new RRSetComment(
                    (string) $c['content'],
                    isset($c['account']) ? (string) $c['account'] : '',
                    isset($c['modifiedAt']) ? (int) $c['modifiedAt'] : null
                );
            }
        }

        if (empty($item['name']) || empty($item['type'])) {
            throw new RuntimeException('Each rrset requires name and type', 400);
        }

        $type = recordTypeFromString((string) $item['type']);
        $ttl = isset($item['ttl']) ? (int) $item['ttl'] : 3600;

        $rr = new RRSet((string) $item['name'], $type, $ttl, $records);
        if ($hasCommentsKey) {
            $rr->setComments($comments);
        }
        $rrsets[] = $rr;
    }

    return $rrsets;
}

/**
 * Minimal RRSet payloads for delete (name + type only).
 *
 * @param array<int, array<string, mixed>> $data
 * @return array<int, RRSet>
 */
function mapInputToRRSetKeys(array $data): array
{
    $rrsets = [];
    foreach ($data as $item) {
        if (empty($item['name']) || empty($item['type'])) {
            throw new RuntimeException('Each rrset requires name and type', 400);
        }
        $type = recordTypeFromString((string) $item['type']);
        $rrsets[] = new RRSet((string) $item['name'], $type);
    }

    return $rrsets;
}

class FileLogger implements RequestLogger
{
    private string $logFile;

    public function __construct(string $filename = 'powerdns_api.log')
    {
        $this->logFile = __DIR__ . '/' . $filename;
    }

    public function log(
        string $zoneId,
        string $url,
        string $serverId,
        string $method,
        string $request_data,
        ?string $response_data,
        int $http_code,
        int $request_timestamp,
        int $response_timestamp
    ): void {
        $logEntry = sprintf(
            "[%s] action=%s | method=%s | zoneId=%s | url=%s | serverId=%s | status=%d | req_ts=%d | resp_ts=%d | req=%s | resp=%s\n",
            date('Y-m-d H:i:s', $request_timestamp),
            $this->extractActionFromRequestData($request_data),
            $method,
            $zoneId,
            $url,
            $serverId,
            $http_code,
            $request_timestamp,
            $response_timestamp,
            $request_data,
            $response_data ?? 'NULL'
        );
        file_put_contents($this->logFile, $logEntry, FILE_APPEND);
    }

    private function extractActionFromRequestData(string $request_data): string
    {
        $decoded = json_decode($request_data, true);
        if (is_array($decoded) && isset($decoded['action']) && is_string($decoded['action'])) {
            return $decoded['action'];
        }

        return '';
    }
}
