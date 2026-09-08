package com.danila.hacustomwidgets.data.remote

import org.junit.Assert.*
import org.junit.Test

class RegistryLoadDiagnosticsTest {
    @Test fun tracksStagesAndLastRequestWithoutIssuingAnyRequests() {
        val lines = mutableListOf<String>()
        val diagnostics = RegistryLoadDiagnostics("sample-secret") { _, line -> lines.add(line) }
        diagnostics.stage("auth_ok")
        listOf("device", "entity", "area", "floor").forEachIndexed { index, registry ->
            diagnostics.command(index + 1, "config/${registry}_registry/list")
            diagnostics.stage("parse_${registry}_registry")
            assertEquals(index + 1, diagnostics.state.requestId)
        }
        diagnostics.stage("completed")
        assertEquals(4, diagnostics.state.requestId)
        assertTrue(lines.last().contains("stage=completed"))
    }

    @Test fun logsFullCauseStackWithCredentialsRedacted() {
        val lines = mutableListOf<String>()
        val diagnostics = RegistryLoadDiagnostics("sample-secret") { _, line -> lines.add(line) }
        diagnostics.command(2, "config/entity_registry/list")
        diagnostics.failure("websocket_failure",
            java.io.IOException("sample-secret", IllegalStateException("bad frame")), 502, "Bad Gateway")
        val output = lines.joinToString("\n")
        assertFalse(output.contains("sample-secret"))
        assertTrue(output.contains("java.io.IOException"))
        assertTrue(output.contains("Caused by: java.lang.IllegalStateException: bad frame"))
        assertTrue(output.contains("RegistryLoadDiagnosticsTest"))
        assertTrue(output.contains("requestId=2"))
        assertTrue(output.contains("httpCode=502"))
        assertFalse(diagnostics.redact("Authorization: Bearer header-secret").contains("header-secret"))
        assertFalse(diagnostics.redact("{\"access_token\":\"json-secret\"}").contains("json-secret"))
    }
}
