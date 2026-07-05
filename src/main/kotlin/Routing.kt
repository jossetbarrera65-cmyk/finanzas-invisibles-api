package com.finanzas.invisibles

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Modelo de datos que recibe el backend desde la app móvil.
 *
 * Ejemplo:
 * {
 *   "textoBanco": "Compra en OXXO por $45.00 MXN"
 * }
 */
@Serializable
data class AnalisisRequest(
    val textoBanco: String
)

/**
 * Modelo de respuesta que el backend devuelve a la app móvil.
 */
@Serializable
data class AnalisisResponse(
    val ok: Boolean,
    val resultado: String? = null,
    val error: String? = null
)

/**
 * Configuración de rutas del backend Ktor.
 *
 * Aquí se crean los endpoints que serán consumidos por el frontend.
 */
fun Application.configureRouting() {
    routing {

        /**
         * Ruta de prueba para verificar que el backend está activo.
         */
        get("/") {
            call.respond(
                AnalisisResponse(
                    ok = true,
                    resultado = "Backend Ktor de Finanzas Invisibles funcionando correctamente"
                )
            )
        }

        /**
         * Endpoint principal.
         *
         * La app móvil enviará un texto de movimiento bancario
         * y este backend lo mandará a Gemini API.
         */
        post("/api/analizar") {
            try {
                val request = call.receive<AnalisisRequest>()

                if (request.textoBanco.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AnalisisResponse(
                            ok = false,
                            error = "El campo textoBanco no puede estar vacío"
                        )
                    )
                    return@post
                }

                val apiKey = System.getenv("GEMINI_API_KEY")

                if (apiKey.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AnalisisResponse(
                            ok = false,
                            error = "Falta configurar GEMINI_API_KEY en el servidor"
                        )
                    )
                    return@post
                }

                val resultado = analizarConGemini(
                    textoBanco = request.textoBanco,
                    apiKey = apiKey
                )

                call.respond(
                    AnalisisResponse(
                        ok = true,
                        resultado = resultado
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AnalisisResponse(
                        ok = false,
                        error = e.message ?: "Error desconocido en el servidor"
                    )
                )
            }
        }
    }
}

/**
 * Función que consume Gemini API desde el backend.
 *
 * La API Key no se guarda en la app móvil.
 * Se toma desde una variable de entorno llamada GEMINI_API_KEY.
 */
fun analizarConGemini(textoBanco: String, apiKey: String): String {
    val modelo = System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash"

    val prompt = """
        Analiza el siguiente movimiento bancario:

        "$textoBanco"

        Identifica:
        1. Categoría del gasto.
        2. Monto.
        3. Si parece gasto hormiga.
        4. Una recomendación breve.

        Responde sin acentos y exactamente con este formato:
        Categoria: ...
        Monto: ...
        Gasto hormiga: Si/No
        Recomendacion: ...
    """.trimIndent()

    val body = buildJsonObject {
        put("contents", buildJsonArray {
            add(
                buildJsonObject {
                    put("parts", buildJsonArray {
                        add(
                            buildJsonObject {
                                put("text", prompt)
                            }
                        )
                    })
                }
            )
        })
    }.toString()

    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent"

    val client = HttpClient.newHttpClient()

    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("x-goog-api-key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response = client.send(
        httpRequest,
        HttpResponse.BodyHandlers.ofString()
    )

    val responseBody = response.body()

    if (response.statusCode() !in 200..299) {
        return "Error de Gemini: $responseBody"
    }

    return extraerTextoGemini(responseBody)
}

/**
 * Extrae el texto principal que responde Gemini.
 *
 * Gemini responde en formato JSON y el texto viene dentro de:
 * candidates -> content -> parts -> text
 */
fun extraerTextoGemini(jsonText: String): String {
    return try {
        val root = Json.parseToJsonElement(jsonText).jsonObject

        val candidates = root["candidates"]?.jsonArray
        val firstCandidate = candidates?.firstOrNull()?.jsonObject
        val content = firstCandidate?.get("content")?.jsonObject
        val parts = content?.get("parts")?.jsonArray

        val respuesta = parts
            ?.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString("\n")
            ?.trim()

        respuesta ?: "Gemini respondió, pero no se encontró texto."
    } catch (e: Exception) {
        "Error al leer la respuesta de Gemini: ${e.message}"
    }
}