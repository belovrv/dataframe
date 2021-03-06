package org.jetbrains.dataframe.impl.columns

import org.jetbrains.dataframe.*
import org.jetbrains.dataframe.columns.ColumnReference
import org.jetbrains.dataframe.columns.DataColumn
import org.jetbrains.dataframe.columns.ColumnWithPath
import org.jetbrains.dataframe.columns.MapColumn

internal class MapColumnWithParent<T>(override val parent: MapColumnReference?, val source: MapColumn<T>) : DataColumnWithParent<DataRow<T>>, MapColumn<T> by source {

    private fun <T> DataColumn<T>.addParent(parent: MapColumn<*>) = (this as DataColumnInternal<T>).addParent(parent)

    override fun get(columnName: String): AnyCol {
        val col = df[columnName]
        return col.addParent(this)
    }
    override fun <R> get(column: ColumnReference<R>) = df[column].addParent(this)
    override fun <R> get(column: ColumnReference<DataRow<R>>) = df[column].addParent(this) as MapColumn<R>
    override fun columns() = df.columns().map { it.addParent(this) }
    override fun column(columnIndex: Int) = df.column(columnIndex).addParent(this)

    override fun equals(other: Any?) = checkEquals(other)

    override fun hashCode() = getHashCode()

    override fun resolveSingle(context: ColumnResolutionContext): ColumnWithPath<DataRow<T>>? {
        return super<DataColumnWithParent>.resolveSingle(context)
    }

    override fun resolve(context: ColumnResolutionContext): List<ColumnWithPath<DataRow<T>>> {
        return super<DataColumnWithParent>.resolve(context)
    }
}