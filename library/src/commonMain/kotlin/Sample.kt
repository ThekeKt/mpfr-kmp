package io.github.thekekt.template

public fun printStrings() {
    println("The first string is: $firstString !")
    println("The second string is: $secondString !")
}

public expect val platformName : String
public expect val firstString: String
public expect val secondString: String
