package dev.placeholder.server.analytics

import dev.placeholder.server.infra.Database
import dev.placeholder.server.snip.Snips
import dev.placeholder.shared.DayStats
import dev.placeholder.shared.LinkStats
import org.apache.datasketches.hll.HllSketch
import org.apache.datasketches.hll.HllUnion
import org.apache.datasketches.hll.TgtHllType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object ClickEvents : Table("click_events") {
    val id = javaUUID("id")
    val slug = varchar("slug", 16)
    val at = timestamp("at")

    override val primaryKey = PrimaryKey(id)
}

object ClickDays : Table("click_days") {
    val slug = varchar("slug", 16)
    val day = date("day")
    val visits = long("visits")
    val visitors = binary("visitors")

    override val primaryKey = PrimaryKey(slug, day)
}

/** Postgres side of click counting: idempotent ingest into daily rollups, and the queries that read them back. */
class ClickStore(
    private val db: Database,
) {
    /**
     * Counts [clicks] that haven't been counted before, in one transaction, and returns how many that was.
     * A click whose id is already in `click_events` is a redelivery and is skipped, which is what makes an
     * at-least-once queue count every visit exactly once.
     *
     * If two writers race on the same ids (a redelivery overlapping the original), the loser's insert fails on the
     * primary key once the winner commits. Exposed retries a failed transaction, and on the retry those ids are
     * recognised as counted.
     */
    suspend fun ingest(clicks: List<Click>): Int =
        db.query {
            val unique = clicks.distinctBy { it.id }
            val counted =
                ClickEvents
                    .select(ClickEvents.id)
                    .where { ClickEvents.id inList unique.map { it.id } }
                    .mapTo(HashSet()) { it[ClickEvents.id] }
            val fresh = unique.filterNot { it.id in counted }
            if (fresh.isEmpty()) return@query 0

            ClickEvents.batchInsert(fresh, shouldReturnGeneratedValues = false) { click ->
                this[ClickEvents.id] = click.id
                this[ClickEvents.slug] = click.slug
                this[ClickEvents.at] = Instant.ofEpochMilli(click.at)
            }
            // Rows are locked in one fixed order, so two consumers whose batches share rows can't deadlock each other.
            fresh
                .groupBy { DayKey(it.slug, dayOf(it.at)) }
                .toSortedMap(compareBy<DayKey> { it.slug }.thenBy { it.day })
                .forEach { (key, group) ->
                    ClickDays.insertIgnore {
                        it[slug] = key.slug
                        it[day] = key.day
                        it[visits] = 0
                        it[visitors] = HllSketch(LG_K, SKETCH_TYPE).toCompactByteArray()
                    }
                    val row =
                        ClickDays
                            .selectAll()
                            .where { (ClickDays.slug eq key.slug) and (ClickDays.day eq key.day) }
                            .forUpdate()
                            .single()
                    val union =
                        HllUnion(LG_K).apply {
                            update(HllSketch.heapify(row[ClickDays.visitors]))
                            group.forEach { update(it.visitor) }
                        }
                    ClickDays.update({ (ClickDays.slug eq key.slug) and (ClickDays.day eq key.day) }) {
                        it[visits] = visits + group.size.toLong()
                        it[visitors] = union.getResult(SKETCH_TYPE).toCompactByteArray()
                    }
                }
            fresh.size
        }

    /** All-time visits for each of [slugs]; slugs nobody has visited are left out. */
    suspend fun visits(slugs: Collection<String>): Map<String, Long> {
        if (slugs.isEmpty()) return emptyMap()
        return db.query {
            val total = ClickDays.visits.sum()
            ClickDays
                .select(ClickDays.slug, total)
                .where { ClickDays.slug inList slugs }
                .groupBy(ClickDays.slug)
                .associate { it[ClickDays.slug] to (it[total] ?: 0L) }
        }
    }

    /** All-time visits, plus a day-by-day series and unique visitors for the [days] up to and including [today]. */
    suspend fun stats(
        slug: String,
        today: LocalDate,
        days: Int = STATS_DAYS,
    ): LinkStats =
        db.query {
            val from = today.minusDays(days - 1L)
            val rows =
                ClickDays
                    .selectAll()
                    .where { (ClickDays.slug eq slug) and (ClickDays.day greaterEq from) }
                    .associateBy { it[ClickDays.day] }
            val total = ClickDays.visits.sum()
            val allTime = ClickDays.select(total).where { ClickDays.slug eq slug }.single()[total] ?: 0L

            val range = HllUnion(LG_K)
            val series =
                (0 until days).map { offset ->
                    val day = from.plusDays(offset.toLong())
                    val row = rows[day]
                    val sketch = row?.let { HllSketch.heapify(it[ClickDays.visitors]) }
                    sketch?.let(range::update)
                    DayStats(day.toString(), row?.get(ClickDays.visits) ?: 0L, sketch?.estimate() ?: 0L)
                }
            LinkStats(visits = allTime, visitors = range.estimate(), days = series)
        }

    /** Forgets the per-visit ids older than [before] (past any redelivery) and the rollups of deleted links. */
    suspend fun prune(before: Instant): Int =
        db.query {
            val events = ClickEvents.deleteWhere { ClickEvents.at less before }
            val orphans =
                ClickDays.deleteWhere {
                    notExists(Snips.select(Snips.slug).where { Snips.slug eq ClickDays.slug })
                }
            events + orphans
        }

    private data class DayKey(
        val slug: String,
        val day: LocalDate,
    )

    private fun dayOf(at: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(at), ZoneOffset.UTC)

    private fun HllSketch.estimate(): Long = Math.round(estimate)

    private fun HllUnion.estimate(): Long = Math.round(estimate)

    private companion object {
        /**
         * 2^12 registers: about 1.6% standard error, and at most about 2.6 KB per link per day with 4-bit registers.
         * A link with a handful of visitors stores far less, because small sketches are kept as a sparse list.
         */
        const val LG_K = 12
        val SKETCH_TYPE = TgtHllType.HLL_4
        const val STATS_DAYS = 30
    }
}
