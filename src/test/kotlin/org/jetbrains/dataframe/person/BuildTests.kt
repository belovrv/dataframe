package org.jetbrains.dataframe.person

import io.kotlintest.shouldBe
import org.jetbrains.dataframe.column
import org.jetbrains.dataframe.getType
import org.jetbrains.dataframe.toDataFrame
import org.junit.Test

class BuildTests {

    data class Person(val name: String, val age: Int)

    val persons = listOf(Person("Alice", 15), Person("Bob", 20))

    @Test
    fun test1(){
        val df = persons.toDataFrame()
        df.ncol() shouldBe 2
        df.nrow() shouldBe 2
        df["name"].type shouldBe getType<String>()
        df["age"].type shouldBe getType<Int>()
    }

    @Test
    fun test2(){
        val df = persons.toDataFrame {
            "name" { name }
            "year of birth" { 2020 - age }
        }
        df.ncol() shouldBe 2
        df.nrow() shouldBe 2
        df["name"].type shouldBe getType<String>()
        df["year of birth"].type shouldBe getType<Int>()
    }

    @Test
    fun test3(){
        val list = persons + listOf(null)
        val df = list.toDataFrame()
        df.nrow() shouldBe 3
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unequal column sizes`(){
        persons.toDataFrame() + column("id", listOf(1,2,3))
    }
}