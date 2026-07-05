package com.finanzas.invisibles

import io.ktor.server.application.Application

fun Application.module() {
    configureHTTP()
    configureSerialization()
    configureRouting()
}