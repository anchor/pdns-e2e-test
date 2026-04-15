package dreamscapenetworks;

import io.qameta.allure.Feature;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

@Feature("PowerDNS bridge")
public class PowerDNSApiTest {

    private static final Path LOG_PATH = Path.of("php-wrapper", "powerdns_api.log");
    private final List<String> zonesToDelete = new CopyOnWriteArrayList<>();

    static {
        RestAssured.baseURI = "http://localhost:8000";
    }

    @BeforeClass(alwaysRun = true)
    public void verifyEnvironment() {
        RestAssured.filters(new AllureRestAssured());
        given()
                .when()
                .get("/bridge.php?action=healthCheck")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("ok"));
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupZones() {
        for (String zoneId : zonesToDelete) {
            given()
                    .queryParam("action", "deleteZone")
                    .queryParam("id", zoneId)
                    .when()
                    .delete("/bridge.php")
                    .then()
                    .statusCode(anyOf(is(204), is(200)));
        }
        zonesToDelete.clear();
    }

    private String uniqueZoneFqdn() {
        return "e2e-" + System.nanoTime() + ".test.";
    }

    private void registerCleanup(String zoneId) {
        zonesToDelete.add(zoneId);
    }

    private void assertLogContains(String needle) throws IOException {
        if (!Files.isRegularFile(LOG_PATH)) {
            throw new AssertionError("Expected log file at " + LOG_PATH.toAbsolutePath());
        }
        String log = Files.readString(LOG_PATH, StandardCharsets.UTF_8);
        if (!log.contains(needle)) {
            throw new AssertionError("Log missing expected fragment \"" + needle + "\"");
        }
    }

    @Test
    public void testRetrieveExistingZone() throws IOException {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        String createPayload = "{\"name\":\"" + zone + "\"}";
        createZoneRaw(createPayload);

        given()
                .queryParam("action", "getZone")
                .queryParam("id", zone)
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasKey("id"))
                .body("$", hasKey("name"))
                .body("$", hasKey("rrsets"))
                .body("id", equalTo(zone))
                .body("name", equalTo(zone))
                .body("rrsets", notNullValue());

        assertLogContains("action=getZone");
        assertLogContains("method=GET");
        assertLogContains("\"url\":");
    }

    @Test
    public void testCreateZoneWithoutRRSets() throws IOException {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        String payload = "{\"name\":\"" + zone + "\"}";

        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("status", equalTo("created"));

        assertLogContains("action=createZone");
        assertLogContains("method=POST");
    }

    @Test
    public void testDeleteZone() {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        given()
                .queryParam("action", "deleteZone")
                .queryParam("id", zone)
                .when()
                .delete("/bridge.php")
                .then()
                .statusCode(204);

        zonesToDelete.remove(zone);
    }

    @Test
    public void testFetchAllRRSets() throws IOException {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        given()
                .queryParam("action", "getAllRRSets")
                .queryParam("zoneId", zone)
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));

        assertLogContains("action=getAllRRSets");
    }

    @Test
    public void testFetchSpecificRRSet() throws IOException {
        String zone = uniqueZoneFqdn();
        String host = "www." + zone;
        registerCleanup(zone);
        String payload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.10\"}]}]"
                + "}";
        createZoneRaw(payload);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].name", equalTo(host))
                .body("[0].type", equalTo("A"))
                .body("[0].records", notNullValue());

        assertLogContains("action=getSpecificRRSet");
    }

    @Test
    public void testGetNonExistentZone() {
        given()
                .queryParam("action", "getZone")
                .queryParam("id", "non-existent-" + System.nanoTime() + ".")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(404)
                .body("error", notNullValue());
    }

    @Test
    public void testDeleteNonExistentZone() {
        given()
                .queryParam("action", "deleteZone")
                .queryParam("id", "missing-" + System.nanoTime() + ".")
                .when()
                .delete("/bridge.php")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("deleted", equalTo(false));
    }

    @Test
    public void testReplaceRRSet() {
        String zone = uniqueZoneFqdn();
        String host = "api." + zone;
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        String body = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.50\"}]}]"
                + "}";

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(200)
                .body("status", equalTo("replaced"));

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].records[0].content", equalTo("192.0.2.50"));
    }

    @Test
    public void testReplaceAllRRSets() {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        String host = "root." + zone;
        String body = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.1\"}]}]"
                + "}";

        given()
                .queryParam("action", "replaceAllRRSets")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(200)
                .body("status", equalTo("replaced_all"));
    }

    @Test
    public void testDeleteRRSets() {
        String zone = uniqueZoneFqdn();
        String host = "tmp." + zone;
        registerCleanup(zone);
        String createPayload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.99\"}]}]"
                + "}";
        createZoneRaw(createPayload);

        String deleteBody = "{\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\"}]}";

        given()
                .queryParam("action", "deleteRRSets")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(deleteBody)
                .when()
                .delete("/bridge.php")
                .then()
                .statusCode(200)
                .body("status", equalTo("deleted"));

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    public void testUpdateZoneMetadata() {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        given()
                .queryParam("action", "updateZone")
                .queryParam("id", zone)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + zone + "\"}")
                .when()
                .patch("/bridge.php")
                .then()
                .statusCode(200)
                .body("status", equalTo("updated"));
    }

    @Test
    public void testCreateZoneWithCommentOnRRSet() {
        String zone = uniqueZoneFqdn();
        String host = "mail." + zone;
        registerCleanup(zone);
        String payload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"MX\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"10 mail." + zone + "\"}],"
                + "\"comments\":[{\"content\":\"Mail routing\",\"account\":\"admin\"}]"
                + "}]"
                + "}";

        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(201);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "MX")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].comments", notNullValue())
                .body("[0].comments.size()", greaterThanOrEqualTo(1))
                .body("[0].comments[0].content", equalTo("Mail routing"));
    }

    @Test
    public void testInvalidJsonReturns400() {
        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body("{not-json")
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    public void testWrongHttpMethodReturns405() {
        given()
                .queryParam("action", "getZone")
                .queryParam("id", "example.test.")
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(405)
                .body("error", notNullValue());

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", "example.test.")
                .contentType(ContentType.JSON)
                .body("{\"rrsets\":[]}")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(405)
                .body("error", notNullValue());
    }

    /**
     * Requires the PHP bridge to be started without {@code PDNS_API_KEY} (and matching local config).
     * Run with {@code -Dpdns.e2e.noApiKey=true}; otherwise skipped.
     */
    @Test
    public void testNonHealthActionReturns503WhenApiKeyUnset() {
        if (!Boolean.parseBoolean(System.getProperty("pdns.e2e.noApiKey", "false"))) {
            throw new SkipException("Pass -Dpdns.e2e.noApiKey=true with bridge running without API key");
        }
        given()
                .queryParam("action", "getZone")
                .queryParam("id", "example.test.")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(503)
                .body("error", notNullValue());
    }

    @Test
    public void testMissingRequiredParametersReturn400() {
        given()
                .queryParam("action", "getZone")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());

        given()
                .queryParam("action", "deleteZone")
                .when()
                .delete("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());

        given()
                .queryParam("action", "getAllRRSets")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", "z.test.")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());

        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());

        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");
        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body("{\"not_rrsets\":[]}")
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    public void testUnknownActionReturns404() {
        given()
                .queryParam("action", "notARealAction")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(404)
                .body("error", notNullValue());
    }

    @Test
    public void testGetSpecificRRSetWithInvalidRecordTypeReturns400() {
        String zone = uniqueZoneFqdn();
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");
        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", zone)
                .queryParam("type", "NOT_A_VALID_TYPE")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    public void testReplaceRRSetMultiRecordDisabledFlag() {
        String zone = uniqueZoneFqdn();
        String host = "multi." + zone;
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        String replaceBody = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":["
                + "{\"content\":\"192.0.2.1\",\"disabled\":false},"
                + "{\"content\":\"192.0.2.2\",\"disabled\":true}"
                + "]}]"
                + "}";

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(replaceBody)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(200)
                .body("status", equalTo("replaced"));

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].records.size()", equalTo(2))
                .body("[0].records.content", containsInAnyOrder("192.0.2.1", "192.0.2.2"))
                .body("[0].records.findAll { it.disabled == true }.size()", equalTo(1))
                .body("[0].records.findAll { it.disabled == true }[0].content", equalTo("192.0.2.2"));
    }

    /**
     * Asserts client-supplied {@code modifiedAt} survives a create + GET round-trip. PowerDNS may
     * normalize timestamps; if this flakes, relax the assertion or compare only the echoed value
     * shape (integer vs string).
     */
    @Test
    public void testRRSetCommentModifiedAtRoundTrip() {
        String zone = uniqueZoneFqdn();
        String host = "meta." + zone;
        registerCleanup(zone);
        int modifiedAt = 1704067200;
        String payload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.88\"}],"
                + "\"comments\":[{\"content\":\"Stamped\",\"account\":\"e2e\",\"modifiedAt\":"
                + modifiedAt + "}]"
                + "}]"
                + "}";

        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(201);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].comments.size()", greaterThanOrEqualTo(1))
                .body(
                        "[0].comments[0].modifiedAt",
                        anyOf(
                                equalTo(modifiedAt),
                                equalTo((long) modifiedAt),
                                equalTo(BigDecimal.valueOf(modifiedAt))));
    }

    @Test
    public void testRRSetMultipleCommentsRoundTrip() {
        String zone = uniqueZoneFqdn();
        String host = "notes." + zone;
        registerCleanup(zone);
        String payload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.77\"}],"
                + "\"comments\":["
                + "{\"content\":\"First note\",\"account\":\"qa\"},"
                + "{\"content\":\"Second note\",\"account\":\"qa2\"}"
                + "]"
                + "}]"
                + "}";

        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(201);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].comments.size()", greaterThanOrEqualTo(2))
                .body("[0].comments.content", hasItems("First note", "Second note"));
    }

    /**
     * When {@code comments} is omitted from the replace payload, the bridge does not call
     * {@code setComments}; existing comments should remain if the PHP library preserves them.
     */
    @Test
    public void testReplaceRRSetOmitCommentsKeyPreservesExistingComments() {
        String zone = uniqueZoneFqdn();
        String host = "keep." + zone;
        registerCleanup(zone);
        String createPayload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.10\"}],"
                + "\"comments\":[{\"content\":\"Do not drop\",\"account\":\"e2e\"}]"
                + "}]"
                + "}";
        createZoneRaw(createPayload);

        String replaceBody = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":7200,"
                + "\"records\":[{\"content\":\"192.0.2.20\"}]"
                + "}]"
                + "}";

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(replaceBody)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(200);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].records[0].content", equalTo("192.0.2.20"))
                .body("[0].ttl", equalTo(7200))
                .body("[0].comments.size()", greaterThanOrEqualTo(1))
                .body("[0].comments[0].content", equalTo("Do not drop"));
    }

    @Test
    public void testReplaceRRSetUpdatesComments() {
        String zone = uniqueZoneFqdn();
        String host = "swap." + zone;
        registerCleanup(zone);
        String createPayload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.30\"}],"
                + "\"comments\":[{\"content\":\"Original\",\"account\":\"e2e\"}]"
                + "}]"
                + "}";
        createZoneRaw(createPayload);

        String replaceBody = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.30\"}],"
                + "\"comments\":[{\"content\":\"Replaced note\",\"account\":\"e2e\"}]"
                + "}]"
                + "}";

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(replaceBody)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(200);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].comments[0].content", equalTo("Replaced note"));
    }

    @Test
    public void testReplaceRRSetWithEmptyCommentsClearsComments() {
        String zone = uniqueZoneFqdn();
        String host = "clear." + zone;
        registerCleanup(zone);
        String createPayload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.40\"}],"
                + "\"comments\":[{\"content\":\"Gone soon\",\"account\":\"e2e\"}]"
                + "}]"
                + "}";
        createZoneRaw(createPayload);

        String replaceBody = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.40\"}],"
                + "\"comments\":[]"
                + "}]"
                + "}";

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(replaceBody)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(200);

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", host)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("[0].comments.size()", equalTo(0));
    }

    @Test
    public void testDeleteRRSetsWithTwoKeys() {
        String zone = uniqueZoneFqdn();
        String hostA = "one." + zone;
        String hostB = "two." + zone;
        registerCleanup(zone);
        String createPayload = "{"
                + "\"name\":\"" + zone + "\","
                + "\"rrsets\":["
                + "{\"name\":\"" + hostA + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.61\"}]},"
                + "{\"name\":\"" + hostB + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.62\"}]}"
                + "]"
                + "}";
        createZoneRaw(createPayload);

        String deleteBody = "{"
                + "\"rrsets\":["
                + "{\"name\":\"" + hostA + "\",\"type\":\"A\"},"
                + "{\"name\":\"" + hostB + "\",\"type\":\"A\"}"
                + "]"
                + "}";

        given()
                .queryParam("action", "deleteRRSets")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(deleteBody)
                .when()
                .delete("/bridge.php")
                .then()
                .statusCode(200)
                .body("status", equalTo("deleted"));

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", hostA)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));

        given()
                .queryParam("action", "getSpecificRRSet")
                .queryParam("zoneId", zone)
                .queryParam("name", hostB)
                .queryParam("type", "A")
                .when()
                .get("/bridge.php")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    public void testReplaceRRSetCommentsMustBeArrayWhenPresent() {
        String zone = uniqueZoneFqdn();
        String host = "bad." + zone;
        registerCleanup(zone);
        createZoneRaw("{\"name\":\"" + zone + "\"}");

        String replaceBody = "{"
                + "\"rrsets\":[{\"name\":\"" + host + "\",\"type\":\"A\",\"ttl\":3600,"
                + "\"records\":[{\"content\":\"192.0.2.70\"}],"
                + "\"comments\":\"not-an-array\""
                + "}]"
                + "}";

        given()
                .queryParam("action", "replaceRRSet")
                .queryParam("zoneId", zone)
                .contentType(ContentType.JSON)
                .body(replaceBody)
                .when()
                .put("/bridge.php")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    private void createZoneRaw(String jsonBody) {
        given()
                .queryParam("action", "createZone")
                .contentType(ContentType.JSON)
                .body(jsonBody)
                .when()
                .post("/bridge.php")
                .then()
                .statusCode(201)
                .body("status", equalTo("created"));
    }
}
