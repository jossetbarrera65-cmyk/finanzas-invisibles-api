package com.finanzas.invisibles

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(
        factory = Netty,
        host = "0.0.0.0",
        port = port,
        module = Application::module
    ).start(wait = true)
}