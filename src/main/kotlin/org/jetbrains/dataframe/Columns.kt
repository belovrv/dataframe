package org.jetbrains.dataframe

import org.jetbrains.dataframe.annotations.ColumnName
import org.jetbrains.dataframe.columns.ColumnDefinition
import org.jetbrains.dataframe.columns.ColumnDefinitionImpl
import org.jetbrains.dataframe.columns.ColumnReference
import org.jetbrains.dataframe.columns.ColumnSet
import org.jetbrains.dataframe.columns.ColumnWithPath
import org.jetbrains.dataframe.columns.DataColumn
import org.jetbrains.dataframe.columns.FrameColumn
import org.jetbrains.dataframe.columns.MapColumn
import org.jetbrains.dataframe.columns.SingleColumn
import org.jetbrains.dataframe.columns.ValueColumn
import org.jetbrains.dataframe.impl.columns.ConvertedColumnDef
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

enum class UnresolvedColumnsPolicy { Fail, Skip, Create }

class ColumnResolutionContext(val df: DataFrameBase<*>, val unresolvedColumnsPolicy: UnresolvedColumnsPolicy) {

    val allowMissingColumns = unresolvedColumnsPolicy == UnresolvedColumnsPolicy.Skip
}

internal val ColumnReference<*>.name get() = name()

fun <TD, T: DataFrameBase<TD>, C> Selector<T, ColumnSet<C>>.toColumns(createReceiver: (ColumnResolutionContext) -> T) = createColumnSet {
    val receiver = createReceiver(it)
    val columnSet = this(receiver, receiver)
    columnSet.resolve(ColumnResolutionContext(receiver, it.unresolvedColumnsPolicy))
}

fun <C> createColumnSet(resolver: (ColumnResolutionContext) -> List<ColumnWithPath<C>>): ColumnSet<C> =
    object: ColumnSet<C> {
        override fun resolve(context: ColumnResolutionContext) = resolver(context)
    }

inline fun <C, reified R> ColumnReference<C>.map(noinline transform: (C) -> R): SingleColumn<R> = map(getType<R>(), transform)

fun <C, R> ColumnReference<C>.map(targetType: KType?, transform: (C) -> R): SingleColumn<R> = ConvertedColumnDef(this, transform, targetType)

typealias Column = ColumnReference<*>

typealias MapColumnReference = ColumnReference<AnyRow>

fun String.toColumnDef(): ColumnDefinition<Any?> = ColumnDefinitionImpl(this)

fun <T> String.toColumnOf(): ColumnDefinition<T> = ColumnDefinitionImpl(this)

fun <T> ColumnPath.toColumnOf(): ColumnDefinition<T> = ColumnDefinitionImpl(this)

fun ColumnPath.toColumnDef(): ColumnDefinition<Any?> = ColumnDefinitionImpl(this)

fun ColumnPath.toGroupColumnDef(): ColumnDefinition<AnyRow> = ColumnDefinitionImpl(this)

internal fun KProperty<*>.getColumnName() = this.findAnnotation<ColumnName>()?.name ?: name

fun <T> KProperty<T>.toColumnDef(): ColumnDefinition<T> = ColumnDefinitionImpl<T>(name)

fun <T> ColumnDefinition<DataRow<*>>.subcolumn(childName: String): ColumnDefinition<T> = ColumnDefinitionImpl(path() + childName)

inline fun <reified T> ColumnDefinition<T>.nullable() = changeType<T?>()

enum class ColumnKind {
    Value,
    Map,
    Frame
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> getType() = typeOf<T>()

fun KClass<*>.createStarProjectedType(nullable: Boolean) = this.starProjectedType.let { if (nullable) it.withNullability(true) else it }

inline fun <reified T> ColumnReference<T>.withValues(values: List<T>, hasNulls: Boolean) =
    column(name(), values, hasNulls)

fun <T> DataColumn<T>.withValues(values: List<T>, hasNulls: Boolean) = when (this) {
    is FrameColumn<*> -> {
        val dfs = (values as List<AnyFrame>)
        DataColumn.create(name, dfs) as DataColumn<T>
    }
    else -> DataColumn.create(name, values, type.withNullability(hasNulls))
}

fun AnyCol.toDataFrame() = dataFrameOf(listOf(this))

inline fun <T, reified R> DataFrame<T>.newColumn(name: String, noinline expression: RowSelector<T, R>): DataColumn<R> {
    var nullable = false
    val values = (0 until nrow()).map { get(it).let { expression(it, it) }.also { if (it == null) nullable = true } }
    return column(name, values, nullable)
}


class ColumnDelegate<T>(private val parent: MapColumnReference? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): ColumnDefinition<T> = named(property.name)

    infix fun named(name: String): ColumnDefinition<T> = parent?.let { ColumnDefinitionImpl(it.path() + name) } ?: ColumnDefinitionImpl(name)
}

fun AnyCol.asFrame(): AnyFrame = when (this) {
    is MapColumn<*> -> df
    is ColumnWithPath<*> -> data.asFrame()
    else -> throw Exception()
}

fun AnyCol.isGroup(): Boolean = kind() == ColumnKind.Map

fun <T> column() = ColumnDelegate<T>()

fun columnGroup() = column<AnyRow>()

fun columnGroup(parent: MapColumnReference) = column<AnyRow>(parent)

fun <T> columnList() = column<List<T>>()

fun <T> columnGroup(name: String) = column<DataRow<T>>(name)

fun <T> frameColumn(name: String) = column<DataFrame<T>>(name)

fun <T> columnList(name: String) = column<List<T>>(name)

fun <T> column(name: String): ColumnDefinition<T> = ColumnDefinitionImpl(name)

fun <T> column(parent: MapColumnReference): ColumnDelegate<T> = ColumnDelegate(parent)

fun <T> column(parent: MapColumnReference, name: String): ColumnDefinition<T> = ColumnDefinitionImpl(parent.path() + name)

interface ColumnProvider<out T>{
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = named(property.name)

    infix fun named(name: String): DataColumn<T>
}

class DataColumnDelegate<T>(val values: List<T>, val type: KType): ColumnProvider<T> {
    override fun named(name: String): DataColumn<T> = DataColumn.create(name, values, type)
}

class MapColumnDelegate(val columns: List<AnyCol>): ColumnProvider<AnyRow> {
    override fun named(name: String): DataColumn<AnyRow> = DataColumn.create(name, columns.toDataFrame())
}

class FrameColumnDelegate(val frames: List<AnyFrame?>): ColumnProvider<AnyFrame?> {
    override fun named(name: String): DataColumn<AnyFrame?> = DataColumn.create(name, frames)
}

inline fun <reified T> column(values: Iterable<T>): ColumnProvider<T> = when {
    values.all { it is AnyCol } -> MapColumnDelegate(values.toList() as List<AnyCol>)  as ColumnProvider<T>
    else -> DataColumnDelegate(values.toList(), getType<T>())
}

inline fun <reified T> columnOf(vararg values: T) = column(values.asIterable())

fun columnOf(vararg values: AnyCol) = MapColumnDelegate(values.toList())

fun columnOf(vararg frames: AnyFrame?) = columnOf(frames.asIterable())

fun columnOf(frames: Iterable<AnyFrame?>) = FrameColumnDelegate(frames.toList())

fun Iterable<AnyFrame>.toColumn() = columnOf(this)

fun Iterable<AnyFrame>.toColumn(name: String) = DataColumn.create(name, toList())

inline fun <reified T> column(name: String, values: List<T>): DataColumn<T> = when {
    values.size > 0 && values.all {it is AnyCol} -> DataColumn.create(name, values.map {it as AnyCol}.toDataFrame()) as DataColumn<T>
    else -> column(name, values, values.any { it == null })
}

inline fun <reified T> column(name: String, values: List<T>, hasNulls: Boolean): DataColumn<T> = DataColumn.create(name, values, getType<T>().withNullability(hasNulls))

fun columnGroup(vararg columns: AnyCol) = MapColumnDelegate(columns.toList())

fun <C> DataColumn<C>.single() = values.single()

fun <T> FrameColumn<T>.toDefinition() = frameColumn<T>(name)
fun <T> MapColumn<T>.toDefinition() = columnGroup<T>(name)
fun <T> ValueColumn<T>.toDefinition() = column<T>(name)

operator fun AnyCol.plus(other: AnyCol) = dataFrameOf(listOf(this, other))

typealias DoubleCol = DataColumn<Double?>
typealias BooleanCol = DataColumn<Boolean?>
typealias IntCol = DataColumn<Int?>
typealias NumberCol = DataColumn<Number?>
typealias StringCol = DataColumn<String?>
typealias AnyCol = DataColumn<*>

fun StringCol.len() = map { it?.length }
fun StringCol.lower() = map { it?.toLowerCase() }
fun StringCol.upper() = map { it?.toUpperCase() }

infix fun <T> DataColumn<T>.eq(value: T): BooleanArray = isMatching { it == value }
infix fun <T> DataColumn<T>.neq(value: T): BooleanArray = isMatching { it != value }

infix fun DataColumn<Int>.gt(value: Int): BooleanArray = isMatching { it > value }
infix fun DataColumn<Double>.gt(value: Double): BooleanArray = isMatching { it > value }
infix fun DataColumn<Float>.gt(value: Float): BooleanArray = isMatching { it > value }
infix fun DataColumn<String>.gt(value: String): BooleanArray = isMatching { it > value }

infix fun DataColumn<Int>.lt(value: Int): BooleanArray = isMatching { it < value }
infix fun DataColumn<Double>.lt(value: Double): BooleanArray = isMatching { it < value }
infix fun DataColumn<Float>.lt(value: Float): BooleanArray = isMatching { it < value }
infix fun DataColumn<String>.lt(value: String): BooleanArray = isMatching { it < value }

infix fun <T> DataColumn<T>.isMatching(predicate: Predicate<T>): BooleanArray = BooleanArray(size) {
    predicate(this[it])
}