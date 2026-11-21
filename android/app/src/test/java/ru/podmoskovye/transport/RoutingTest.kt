package ru.podmoskovye.transport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Model
import ru.podmoskovye.transport.core.Router

class RoutingTest {

    private fun asset(name: String): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("нет ассета: $name")
        val text = stream.bufferedReader().readText()
        return JSONObject(text)
    }

    private val network by lazy {
        Model.buildNetwork(
            asset("lines.json"),
            asset("project_lines.json"),
            asset("cities.json"),
        )
    }

    private val router by lazy { Router(network) }

    private val allTypes = setOf(LineType.METRO, LineType.MCC, LineType.MCD, LineType.PROJECT)

    @Test
    fun networkContainsOnlyAllowedSystems() {
        assertEquals(15, network.metroLines.size)
        assertEquals(1, network.mccLines.size)
        assertEquals(4, network.mcdLines.size)
        assertTrue(network.projectLines.size >= 24)
        for (line in network.lines) {
            assertFalse("РЖД не должно быть: ${line.name}", line.name.contains("РЖД"))
            assertFalse("МЦД-5 не должно быть: ${line.name}", Regex("МЦД-?5").containsMatchIn(line.name))
        }
    }

    @Test
    fun everyLineHasRequiredFields() {
        for (line in network.lines) {
            assertTrue(line.id.isNotBlank())
            assertTrue(line.name.isNotBlank())
            assertTrue("цвет: ${line.color}", Regex("^#[0-9a-fA-F]{6}$").matches(line.color))
            assertTrue("станций: ${line.name}", line.stations.size >= 2)
            for (st in line.stations) {
                assertTrue("широта ${st.name}", Math.abs(st.lat - 55.7) < 2.2)
            }
        }
    }

    @Test
    fun directionPointsToTerminalInTravelDirection() {
        val d1 = network.lineById["mcd-D1"]!!
        val forward = Model.directionOf(d1, "Одинцово", "Окружная")
        assertEquals("Лобня", forward.terminal)
        val backward = Model.directionOf(d1, "Лобня", "Окружная")
        assertEquals("Одинцово", backward.terminal)
    }

    @Test
    fun directionNeverLeavesTheLine() {
        for (line in network.lines) {
            if (line.ring) continue
            val names = line.stations.map { it.name }
            for (i in names.indices) {
                for (j in names.indices) {
                    if (i == j) continue
                    val dir = Model.directionOf(line, names[i], names[j])
                    assertTrue("${line.id}: ${dir.terminal}", names.contains(dir.terminal))
                    assertEquals(
                        if (j > i) names.last() else names.first(),
                        dir.terminal,
                    )
                }
            }
        }
    }

    @Test
    fun sliceContainsOnlyRiddenStations() {
        val d1 = network.lineById["mcd-D1"]!!
        val slice = Model.sliceStations(d1, "Белорусская", "Окружная").map { it.name }
        assertEquals(
            listOf("Белорусская", "Савёловская", "Тимирязевская", "Петровско-Разумовская", "Окружная"),
            slice,
        )
    }

    @Test
    fun ringDirectionIsSideOfRing() {
        val ring = network.lines.first { it.ring && it.type == LineType.MCC }
        val dir = Model.directionOf(ring, ring.stations[0].name, ring.stations[2].name)
        assertTrue(dir.label.contains("часовой"))
    }

    /** Главный сценарий: МЦД-1 заканчивается на Окружной, текст не подставляет имя линии. */
    @Test
    fun oruzhnayaScenarioTextMatchesMap() {
        val res = router.findRoutes("Сколково", "Ботанический сад", allTypes)
        assertTrue(res.ok)
        for (variant in res.variants) {
            val text = router.buildInstructions(variant).joinToString(" ") { it.text }
            assertFalse(text, text.contains("Белорусский вокзал"))
            assertFalse(text, text.contains("МЦД-1 Белорусско"))
            assertTrue(text, !text.contains("Окружная") || text.contains("станции «Окружная»"))
        }
        val best = res.variants.first()
        val d1Leg = best.legs.firstOrNull { it.lineId == "mcd-D1" && it.to == "Окружная" }
        assertNotNull("маршрут должен идти по МЦД-1 до Окружной", d1Leg)
        assertEquals("Лобня", d1Leg!!.direction.terminal)
    }

    @Test
    fun mapAndTextShareOneObject() {
        val cases = listOf(
            "Сколково" to "Ботанический сад",
            "Одинцово" to "Химки",
            "Лобня" to "Видное",
            "Подольск" to "Мытищи",
            "Ногинск" to "Красногорск",
        )
        for ((from, to) in cases) {
            val res = router.findRoutes(from, to, allTypes)
            assertTrue("$from → $to", res.ok)
            val v = res.variants.first()

            // точки маршрута собираются только из участков
            val rebuilt = mutableListOf<Pair<Double, Double>>()
            for (leg in v.legs) for (p in leg.points) {
                if (rebuilt.isEmpty() || rebuilt.last() != p) rebuilt.add(p)
            }
            assertEquals("$from → $to: линия на карте", rebuilt, v.path)

            for (leg in v.legs) {
                assertEquals("$from → $to: начало участка", leg.from, leg.stations.first())
                assertEquals("$from → $to: конец участка", leg.to, leg.stations.last())
                assertTrue("$from → $to: участок из одной станции", leg.stations.size >= 2)

                val line = network.lineById[leg.lineId]!!
                if (!line.ring) {
                    val names = line.stations.map { it.name }
                    val forward = names.indexOf(leg.to) > names.indexOf(leg.from)
                    assertEquals(
                        "$from → $to: направление",
                        if (forward) names.last() else names.first(),
                        leg.direction.terminal,
                    )
                }
            }

            val instructions = router.buildInstructions(v)
            val boards = instructions.filter { it.kind == "board" }
            assertEquals("$from → $to: число этапов", v.legs.size, boards.size)
            assertEquals("$from → $to: последний шаг", "finish", instructions.last().kind)
            assertTrue("$from → $to: выход", instructions.last().text.contains("«${v.legs.last().to}»"))
        }
    }

    @Test
    fun requiredScenariosFromSpec() {
        val pairs = listOf(
            Triple("Бауманская", "Одинцово", "Москва → область"),
            Triple("Лобня", "Ботанический сад", "область → Москва"),
            Triple("Подольск", "Мытищи", "область → область"),
        )
        for ((from, to, label) in pairs) {
            val res = router.findRoutes(from, to, allTypes)
            assertTrue(label, res.ok)
            assertTrue(label, res.variants.first().minutes > 0)
        }
    }

    @Test
    fun mcdAndMccScenarios() {
        val mcd = router.findRoutes("Одинцово", "Химки", allTypes)
        assertTrue(mcd.variants.flatMap { it.lineIds }.any { it.startsWith("mcd-") })

        val ring = router.findRoutes("Ботанический сад", "Лужники", allTypes)
        assertTrue(ring.variants.flatMap { it.lineIds }.contains("metro-14"))
        val ringLeg = ring.variants.first().legs.firstOrNull { it.lineId == "metro-14" }
        if (ringLeg != null) assertTrue(ringLeg.direction.ring || ringLeg.direction.label.contains("часовой"))
    }

    @Test
    fun projectLineScenario() {
        val res = router.findRoutes("Химки", "Ховрино", allTypes)
        assertTrue(res.ok)
        assertTrue(res.variants.any { v -> v.lineIds.any { it.startsWith("proj-") } })
    }

    @Test
    fun variantsAreDistinctAndSorted() {
        val res = router.findRoutes("Одинцово", "Лобня", allTypes)
        val signatures = res.variants.map { v -> v.legs.joinToString("|") { "${it.lineId}:${it.from}>${it.to}" } }
        assertEquals(signatures.size, signatures.distinct().size)
        val times = res.variants.map { it.minutes }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun errorsAreHandled() {
        val missing = router.findRoutes("Нетакой", "Одинцово", allTypes)
        assertFalse(missing.ok)
        assertTrue(missing.reason.contains("не найдена"))

        val same = router.findRoutes("Лобня", "Лобня", allTypes)
        assertFalse(same.ok)
        assertTrue(same.reason.contains("совпадают"))
    }

    @Test
    fun cityIsAssigned() {
        assertEquals("Москва", network.byName["Окружная"]!!.city)
        assertEquals("Лобня", network.byName["Лобня"]!!.city)
        assertEquals("Химки", network.byName["Химки"]!!.city)
        assertTrue(network.places.all { it.city != null })
    }
}
