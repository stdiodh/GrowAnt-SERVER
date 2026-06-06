package com.growant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GrowantApplication

fun main(args: Array<String>) {
    runApplication<GrowantApplication>(*args)
}
