package org.jetbrains.dataframe

import org.jetbrains.dataframe.DataFrame
import org.jetbrains.dataframe.impl.codeGen.CodeGenerator
import org.jetbrains.dataframe.internal.schema.extractSchema

inline fun <reified T> DataFrame<T>.generateCode(fields: Boolean = true, extensionProperties: Boolean = true): String {
    val name = if(T::class.isAbstract)
        T::class.simpleName!!
    else "DataEntry"
    return generateCode(name, fields, extensionProperties)
}

fun <T> DataFrame<T>.generateCode(markerName: String, fields: Boolean = true, extensionProperties: Boolean = true): String {
    val codeGen = CodeGenerator.create()
    return codeGen.generate(
        extractSchema(),
        markerName,
        fields = fields,
        extensionProperties = extensionProperties,
        isOpen = true,
    ).code.declarations
}

inline fun <reified T> DataFrame<T>.generateInterfaces() = generateCode(true, false)

fun <T> DataFrame<T>.generateInterfaces(markerName: String) = generateCode(markerName, true, false)