package org.jetbrains.dataframe.jupyter

import org.jetbrains.dataframe.*
import org.jetbrains.dataframe.annotations.DataSchema
import org.jetbrains.dataframe.columns.MapColumn
import org.jetbrains.dataframe.impl.codeGen.CodeGenerator
import org.jetbrains.dataframe.io.toHTML
import org.jetbrains.dataframe.stubs.DataFrameToListNamedStub
import org.jetbrains.dataframe.stubs.DataFrameToListTypedStub
import org.jetbrains.kotlinx.jupyter.api.*
import org.jetbrains.kotlinx.jupyter.api.annotations.JupyterLibrary
import org.jetbrains.kotlinx.jupyter.api.libraries.*
import kotlin.reflect.KClass

internal val newDataSchemas = mutableListOf<KClass<*>>()

@JupyterLibrary
internal class Integration : JupyterIntegration(){

    override fun Builder.onLoaded() {

        val codeGen = CodeGenerator.create()

        render<AnyFrame> { HTML(it.toHTML()) }
        render<AnyRow> { it.toDataFrame() }
        render<MapColumn<*>> { it.df }
        render<AnyCol> { dataFrameOf(listOf(it)) }
        render<GroupedDataFrame<*, *>> { it.plain() }

        import("org.jetbrains.dataframe.*")
        import("org.jetbrains.dataframe.annotations.*")
        import("org.jetbrains.dataframe.io.*")

        updateVariable<AnyFrame> { df, property ->
            codeGen.generate(df, property)?.let {
                val code = it.with(property.name)
                execute(code).name
            }
        }

        updateVariable<DataFrameToListNamedStub> { stub, prop ->
            val code = codeGen.generate(stub).with(prop.name)
            execute(code).name
        }

        updateVariable<DataFrameToListTypedStub> { stub, prop ->
            val code = codeGen.generate(stub).with(prop.name)
            execute(code).name
        }

        fun KotlinKernelHost.addDataSchemas(classes: List<KClass<*>>) {
            val code = classes.mapNotNull {
                codeGen.generateExtensionProperties(it)
            }.joinToString("\n").trim()

            if (code.isNotEmpty()) {
                execute(code)
            }
        }

        onClassAnnotation<DataSchema> { addDataSchemas(it) }

        afterCellExecution { snippet, result ->
            if (newDataSchemas.isNotEmpty()) {
                addDataSchemas(newDataSchemas)
                newDataSchemas.clear()
            }
        }
    }
}

fun KotlinKernelHost.useDataSchemas(vararg schemaClasses: KClass<*>){
    newDataSchemas.addAll(schemaClasses)
}