package io.github.thekekt.template

fun printStrings() {
    println("The first string is: $firstString !")
    println("The second string is: $secondString !")
}

expect val platformName : String
expect val firstString: String
expect val secondString: String
