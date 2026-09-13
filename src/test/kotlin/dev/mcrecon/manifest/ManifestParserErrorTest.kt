package dev.mcrecon.manifest

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ManifestParserErrorTest {
    private val parser = ManifestParser()

    @Test
    fun `reports invalid JSON with a source location`() {
        val exception = assertFailsWith<ManifestException> { parser.parse("{broken", "bad.json") }
        assertContains(exception.message.orEmpty(), "Invalid JSON")
        assertContains(exception.message.orEmpty(), "bad.json")
    }

    @Test
    fun `reports a missing required field`() {
        val exception = assertFailsWith<ManifestException> { parser.parse("{}", "empty.json") }
        assertContains(exception.message.orEmpty(), "downloads is required")
    }

    @Test
    fun `reports a non-object JSON document as a manifest error`() {
        val exception = assertFailsWith<ManifestException> { parser.parse("[]", "array.json") }
        assertContains(exception.message.orEmpty(), "manifest must be an object")
    }

    @Test
    fun `rejects unsupported rule structures`() {
        val json = minimalManifest(
            """[{"name":"example:test:1","rules":[{"action":"allow","unexpected":true}]}]""",
        )
        val exception = assertFailsWith<ManifestException> { parser.parse(json, "rules.json") }
        assertContains(exception.message.orEmpty(), "unsupported fields: unexpected")
    }

    @Test
    fun `identifies an artifact with a missing URL`() {
        val json = minimalManifest(
            """[{"name":"example:test:1","downloads":{"artifact":{"sha1":"0000000000000000000000000000000000000000","size":1,"path":"example/test/1/test-1.jar"}}}]""",
        )
        val exception = assertFailsWith<ManifestException> { parser.parse(json, "missing-url.json") }
        assertContains(exception.message.orEmpty(), "libraries[0].downloads.artifact.url")
    }

    private fun minimalManifest(libraries: String): String = """
        {
          "id": "test",
          "type": "snapshot",
          "time": "2026-01-01T00:00:00Z",
          "releaseTime": "2026-01-01T00:00:00Z",
          "downloads": {
            "client": {"sha1":"0000000000000000000000000000000000000000","size":1,"url":"https://example.invalid/client.jar"},
            "server": {"sha1":"0000000000000000000000000000000000000000","size":1,"url":"https://example.invalid/server.jar"}
          },
          "assetIndex": {"id":"test","sha1":"0000000000000000000000000000000000000000","size":1,"totalSize":1,"url":"https://example.invalid/assets.json"},
          "javaVersion": {"component":"java-runtime-test","majorVersion":25},
          "libraries": $libraries,
          "arguments": {"jvm":[],"game":[]},
          "mainClass": "example.Main"
        }
    """.trimIndent()
}
