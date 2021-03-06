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
import org.jetbrains.dataframe.columns.size
import org.jetbrains.dataframe.columns.type
import org.jetbrains.dataframe.columns.hasNulls
import org.jetbrains.dataframe.columns.name
import org.jetbrains.dataframe.columns.ndistinct
import org.jetbrains.dataframe.columns.values
import org.jetbrains.dataframe.impl.asList
import org.jetbrains.dataframe.impl.columns.ConvertedColumnDef
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

enum class UnresolvedColumnsPolicy { Fail, Skip, Create }

class ColumnResolutionContext(val df: DataFrameBase<*>, val unresolvedColumnsPolicy: UnresolvedColumnsPolicy) {

    val allowMissingColumns = unresolvedColumnsPolicy == UnresolvedColumnsPolicy.Skip
}

fun <TD, T : DataFrameBase<TD>, C> Selector<T, ColumnSet<C>>.toColumns(createReceiver: (ColumnResolutionContext) -> T): ColumnSet<C> =
    createColumnSet {
        val receiver = createReceiver(it)
        val columnSet = this(receiver, receiver)
        columnSet.resolve(ColumnResolutionContext(receiver, it.unresolvedColumnsPolicy))
    }

fun <C> createColumnSet(resolver: (ColumnResolutionContext) -> List<ColumnWithPath<C>>): ColumnSet<C> =
    object : ColumnSet<C> {
        override fun resolve(context: ColumnResolutionContext) = resolver(context)
    }

inline fun <C, reified R> ColumnReference<C>.map(noinline transform: (C) -> R): SingleColumn<R> =
    map(getType<R>(), transform)

fun <C, R> ColumnReference<C>.map(targetType: KType?, transform: (C) -> R): SingleColumn<R> =
    ConvertedColumnDef(this, transform, targetType)

typealias Column = ColumnReference<*>

typealias MapColumnReference = ColumnReference<AnyRow>

fun String.toColumnDef(): ColumnDefinition<Any?> = ColumnDefinitionImpl(this)

fun <T> String.toColumnOf(): ColumnDefinition<T> = ColumnDefinitionImpl(this)

fun <T> ColumnPath.toColumnOf(): ColumnDefinition<T> = ColumnDefinitionImpl(this)

fun ColumnPath.toColumnDef(): ColumnDefinition<Any?> = ColumnDefinitionImpl(this)

fun ColumnPath.toGroupColumnDef(): ColumnDefinition<AnyRow> = ColumnDefinitionImpl(this)

internal fun KProperty<*>.getColumnName() = this.findAnnotation<ColumnName>()?.name ?: name

fun <T> KProperty<T>.toColumnDef(): ColumnDefinition<T> = ColumnDefinitionImpl<T>(name)

fun <T> ColumnDefinition<DataRow<*>>.subcolumn(childName: String): ColumnDefinition<T> =
    ColumnDefinitionImpl(path() + childName)

inline fun <reified T> ColumnDefinition<T>.nullable() = changeType<T?>()

enum class ColumnKind {
    Value,
    Map,
    Frame
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> getType() = typeOf<T>()

fun KClass<*>.createStarProjectedType(nullable: Boolean) =
    this.starProjectedType.let { if (nullable) it.withNullability(true) else it }

inline fun <reified T> ColumnReference<T>.withValues(vararg values: T) = withValues(values.asIterable())

inline fun <reified T> ColumnReference<T>.withValues(values: Iterable<T>) =
    DataColumn.create(name(), values.asList(), getType<T>())

fun AnyCol.toDataFrame() = dataFrameOf(listOf(this))

inline fun <T, reified R> DataFrame<T>.newColumn(name: String, noinline expression: RowSelector<T, R>): DataColumn<R> {
    var nullable = false
    val values = (0 until nrow()).map { get(it).let { expression(it, it) }.also { if (it == null) nullable = true } }
    if (R::class == DataFrame::class) return DataColumn.frames(name, values as List<AnyFrame?>) as DataColumn<R>
    return column(name, values, nullable)
}

class ColumnDelegate<T>(private val parent: MapColumnReference? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): ColumnDefinition<T> = named(property.name)

    infix fun named(name: String): ColumnDefinition<T> =
        parent?.let { ColumnDefinitionImpl(it.path() + name) } ?: ColumnDefinitionImpl(name)
}

fun AnyCol.asFrame(): AnyFrame = when (this) {
    is MapColumn<*> -> df
    is ColumnWithPath<*> -> data.asFrame()
    else -> error("Can not extract DataFrame from ${javaClass.kotlin}")
}

fun AnyCol.isGroup(): Boolean = kind() == ColumnKind.Map

fun <T> column() = ColumnDelegate<T>()

fun columnGroup() = column<AnyRow>()

fun columnGroup(parent: MapColumnReference) = column<AnyRow>(parent)

fun frameColumn() = column<AnyFrame>()

fun <T> columnList() = column<List<T>>()

fun <T> columnGroup(name: String) = column<DataRow<T>>(name)

fun <T> frameColumn(name: String) = column<DataFrame<T>>(name)

fun <T> columnList(name: String) = column<List<T>>(name)

fun <T> column(name: String): ColumnDefinition<T> = ColumnDefinitionImpl(name)

fun <T> column(parent: MapColumnReference): ColumnDelegate<T> = ColumnDelegate(parent)

fun <T> column(parent: MapColumnReference, name: String): ColumnDefinition<T> =
    ColumnDefinitionImpl(parent.path() + name)

inline fun <reified T> columnOf(vararg values: T) = column(values.asIterable())

fun columnOf(vararg values: AnyCol) = DataColumn.create("", dataFrameOf(values.asIterable()))

fun columnOf(vararg frames: AnyFrame?) = columnOf(frames.asIterable())

fun <T> columnOf(frames: Iterable<DataFrame<T>?>) = DataColumn.create("", frames.toList())

interface ColumnProvider<out T> {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = named(property.name)

    infix fun named(name: String): DataColumn<T>
}

inline fun <reified T> column(values: Iterable<T>): DataColumn<T> = when {
    values.all { it is AnyCol } -> DataColumn.create("", (values as Iterable<AnyCol>).toDataFrame()) as DataColumn<T>
    else -> DataColumn.create("", values.toList(), getType<T>())
}

fun <T> Iterable<DataFrame<T>?>.toFrameColumn(name: String): FrameColumn<T> =
    DataColumn.create(name, asList())

inline fun <reified T> Iterable<T>.toColumn(name: String = ""): ValueColumn<T> =
    asList().let { DataColumn.create(name, it, getType<T>().withNullability(it.any { it == null })) }

inline fun <reified T> Iterable<T>.toColumn(ref: ColumnReference<T>): ValueColumn<T> =
    toColumn(ref.name())

fun Iterable<AnyFrame?>.toColumn() = columnOf(this)

fun Iterable<AnyFrame?>.toColumn(name: String) = DataColumn.create(name, toList())

inline fun <reified T> column(name: String, values: List<T>): DataColumn<T> = when {
    values.size > 0 && values.all { it is AnyCol } -> DataColumn.create(
        name,
        values.map { it as AnyCol }.toDataFrame()
    ) as DataColumn<T>
    else -> column(name, values, values.any { it == null })
}

inline fun <reified T> column(name: String, values: List<T>, hasNulls: Boolean): DataColumn<T> =
    DataColumn.create(name, values, getType<T>().withNullability(hasNulls))

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

fun <T> DataColumn<T>.first() = get(0)
fun <T> DataColumn<T>.firstOrNull() = if(size > 0) first() else null
fun <T> DataColumn<T>.first(predicate: (T)->Boolean) = values.first(predicate)
fun <T> DataColumn<T>.firstOrNull(predicate: (T)->Boolean) = values.firstOrNull(predicate)
fun <T> DataColumn<T>.last() = get(size-1)
fun <T> DataColumn<T>.lastOrNull() = if(size > 0) last() else null

fun <C> DataColumn<C>.allNulls() = size == 0 || (hasNulls && ndistinct == 1)

fun AnyCol.isSubtypeOf(type: KType) = this.type.isSubtypeOf(type) && (!this.type.isMarkedNullable || type.isMarkedNullable)
inline fun <reified T> AnyCol.isSubtypeOf() = isSubtypeOf(getType<T>())
inline fun <reified T> AnyCol.isType() = type() == getType<T>()

fun AnyCol.isNumber() = type.withNullability(false).isSubtypeOf(getType<Number>())

fun AnyCol.guessType() = guessColumnType(name, toList())