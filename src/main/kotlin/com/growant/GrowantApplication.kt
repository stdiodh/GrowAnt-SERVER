package com.growant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class GrowantApplication

fun main(args: Array<String>) {
    runApplication<GrowantApplication>(*args)
}
