package com.usool.map

import com.usool.map.core.BBox
import com.usool.map.core.CoordinateParser
import com.usool.map.core.Gcj02
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.search.AreaLanguage
import com.usool.map.search.GeocodingProvider
import com.usool.map.search.NominatimProvider
import com.usool.map.search.Place
import com.usool.map.search.PoiConcepts
import com.usool.map.search.SearchRepository
import com.usool.map.search.TextNormalizer
import com.usool.map.search.TextTranslator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiConceptsTest {
    @Test fun understandsHalalRestaurantInManyWordings() {
        for (q in listOf("مطعم حلال", "مطعم مسلمين", "المطاعم الحلال", "مَطْعَم حَلَال", "أكل حلال", "halal restaurant", "Halal food", "Muslim restaurant", "清真餐厅", "清真", "穆斯林餐厅")) {
            assertEquals(q, "halal_restaurant", PoiConcepts.match(q)?.id)
        }
        // Without "halal" it is any restaurant.
        assertEquals("restaurant", PoiConcepts.match("مطعم")?.id)
        assertEquals("restaurant", PoiConcepts.match("restaurants")?.id)
    }

    @Test fun understandsOtherKindsOfPlace() {
        assertEquals("hotel", PoiConcepts.match("فندق")?.id)
        assertEquals("hotel", PoiConcepts.match("الفنادق")?.id)
        assertEquals("mosque", PoiConcepts.match("مسجد")?.id)
        assertEquals("mosque", PoiConcepts.match("清真寺")?.id)
        assertEquals("pharmacy", PoiConcepts.match("صيدلية")?.id)
        assertEquals("hospital", PoiConcepts.match("المستشفى")?.id)
        assertEquals("fuel", PoiConcepts.match("محطة وقود")?.id)
        assertEquals("station", PoiConcepts.match("محطة مترو")?.id)
        assertEquals("attraction", PoiConcepts.match("معالم سياحية")?.id)
        assertEquals("atm", PoiConcepts.match("ATM")?.id)
        assertEquals("toilets", PoiConcepts.match("دورة مياه")?.id)
    }

    @Test fun leavesNamesAndAddressesAlone() {
        assertNull(PoiConcepts.match("Guangzhou"))
        assertNull(PoiConcepts.match("غوانزو"))
        assertNull(PoiConcepts.match("Souq Waqif"))
        assertNull(PoiConcepts.match("Park Hyatt Guangzhou Zhujiang New Town"))
        assertNull(PoiConcepts.match("شارع حمد الكبير الدوحة قطر"))
    }

    @Test fun usesTheLocalWordForHalal() {
        assertEquals(listOf("清真", "穆斯林", "halal"), PoiConcepts.halalTerms("zh"))
        assertEquals(listOf("ハラール", "ハラル", "halal"), PoiConcepts.halalTerms("ja"))
        assertEquals(listOf("حلال", "halal"), PoiConcepts.halalTerms("ar"))
        assertEquals(listOf("halal"), PoiConcepts.halalTerms("fr"))
        assertEquals("مطعم حلال", PoiConcepts.match("halal")!!.label("ar-QA"))
        assertEquals("Halal restaurant", PoiConcepts.match("حلال")!!.label("de"))
    }

    @Test fun normalisesArabicAndKnowsAreaLanguages() {
        assertEquals("مطعم حلال", TextNormalizer.normalize("  المَطْعَم  الحلال؟ "))
        assertEquals("صيدليه", TextNormalizer.normalize("صيدلية"))
        assertEquals("zh", AreaLanguage.forCountry("CN"))
        assertEquals("zh", AreaLanguage.forCountry("hk"))
        assertEquals("ar", AreaLanguage.forCountry("QA"))
        assertEquals("ja", AreaLanguage.forCountry("JP"))
        assertEquals("en", AreaLanguage.forCountry("US"))
        assertEquals("en", AreaLanguage.forCountry(null))
    }
}

class AreaSearchTest {
    private fun resource(name: String) = Thread.currentThread().contextClassLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
    private val guangzhou = BBox(23.05, 113.20, 23.20, 113.40)

    /** Answers like the real service did for Guangzhou: results only for the Chinese word 清真. */
    private inner class FakeGeocoder : GeocodingProvider {
        override val name = "fake"
        val asked = ArrayList<Pair<String, BBox?>>()
        override suspend fun search(query: String, near: GeoPoint?, language: String, within: BBox?, limit: Int): List<Place> {
            asked += query to within
            return when {
                query == "清真" && within != null -> NominatimProvider.parseSearch(resource("nominatim_guangzhou_qingzhen.json"), language, inArea = true)
                query == "[hotel]" -> listOf(Place("如家酒店", "天河区, 广州", GeoPoint(23.12, 113.33), osmType = "tourism=hotel", inArea = true), Place("Art gallery", "x", GeoPoint(23.1, 113.3), osmType = "amenity=arts_centre", inArea = true))
                query == "Guangzhou" && within == null -> listOf(Place("Guangzhou", "Guangdong, China", GeoPoint(23.13, 113.26), countryCode = "CN"))
                else -> emptyList()
            }
        }
        override suspend fun reverse(point: GeoPoint, language: String, zoom: Int): Place? = null
    }

    private class FakeTranslator(private val ready: Boolean) : TextTranslator {
        override suspend fun isReady(from: String, to: String) = ready
        override suspend fun translate(texts: List<String>, from: String, to: String) = texts.map { "[$to] $it" }
    }

    @Test fun arabicHalalSearchInGuangzhouFindsChineseNamedRestaurants() = runTest {
        val geo = FakeGeocoder()
        val results = SearchRepository(geo).search("مطعم حلال", null, "ar-QA", guangzhou, "zh")
        // Searched inside the map area with the Chinese terms, never with the Arabic words.
        assertEquals(listOf("清真", "穆斯林", "halal"), geo.asked.map { it.first })
        assertTrue(geo.asked.all { it.second == guangzhou })
        assertTrue(results.places.isNotEmpty())
        // Mosques and tombs whose names contain 清真 are not restaurants.
        assertTrue(results.places.all { it.osmType in setOf("amenity=restaurant", "amenity=fast_food", "amenity=cafe", "amenity=food_court") })
        val canteen = results.places.first { (it.localName ?: it.name) == "清真饭堂" }
        assertEquals("مطعم حلال", canteen.categoryLabel)
        assertEquals(SearchRepository.HALAL_NOTE, canteen.note)
        assertTrue(canteen.inArea)
        assertNotNull(canteen.distanceM)
        // The address comes back in Arabic where the map has Arabic names.
        assertTrue(canteen.description, canteen.description.contains("غوانزو"))
        assertTrue(results.heading!!, results.heading!!.startsWith("مطعم حلال (清真, 穆斯林, halal)"))
        assertEquals(results.places.sortedBy { it.distanceM }, results.places)
    }

    @Test fun translatesLocalNamesWhenTheDeviceCan() = runTest {
        val results = SearchRepository(FakeGeocoder(), FakeTranslator(ready = true)).search("حلال", null, "ar", guangzhou, "zh")
        assertEquals("[ar] 清真饭堂", results.places.first { (it.localName ?: it.name) == "清真饭堂" }.translation)
        val without = SearchRepository(FakeGeocoder(), FakeTranslator(ready = false)).search("حلال", null, "ar", guangzhou, "zh")
        assertNull(without.places.first { (it.localName ?: it.name) == "清真饭堂" }.translation)
    }

    @Test fun categorySearchKeepsOnlyTheRightKindOfPlace() = runTest {
        val geo = FakeGeocoder()
        val results = SearchRepository(geo).search("فنادق", null, "ar", guangzhou, "zh")
        assertEquals(listOf("如家酒店"), results.places.map { it.name })
        assertEquals("فندق", results.places[0].categoryLabel)
        assertNull(results.places[0].note)
        assertEquals("[hotel]", geo.asked.first().first)
    }

    @Test fun ordinaryNamesAreSearchedInTheAreaThenWorldwide() = runTest {
        val geo = FakeGeocoder()
        val results = SearchRepository(geo).search("Guangzhou", GeoPoint(25.28, 51.53), "en", BBox(25.0, 51.0, 25.6, 51.8), "ar")
        assertEquals(listOf("Guangzhou"), results.places.map { it.name })
        assertNull(results.heading)
        assertEquals(2, geo.asked.size)
        assertNotNull(geo.asked[0].second)
        assertNull(geo.asked[1].second)
    }

    @Test fun parsesLocalAndTranslatedNamesFromRealResults() {
        val places = NominatimProvider.parseSearch(resource("nominatim_guangzhou_qingzhen.json"), "ar", inArea = true)
        val mosque = places.first { it.localName == "小东营清真寺" }
        assertTrue(mosque.name.startsWith("مسجد"))
        assertEquals("amenity=place_of_worship", mosque.osmType)
        val xianxian = places.first { it.name == "先贤清真寺" }
        assertEquals("Xianxian Mosque", xianxian.translation)
        assertFalse(xianxian.description.startsWith("先贤清真寺"))
        assertEquals("CN", xianxian.countryCode)
    }
}

class Gcj02Test {
    @Test fun convertsChineseMapCoordinatesToTruePositions() {
        // Tiananmen: true position and the shifted one Chinese map apps show.
        val wgs = GeoPoint(39.9087, 116.3975)
        val gcj = Gcj02.fromWgs84(wgs)
        assertEquals(39.9101, gcj.lat, 0.0003)
        assertEquals(116.4037, gcj.lon, 0.0003)
        val offset = Geo.distanceM(wgs, gcj)
        assertTrue("offset was $offset m", offset in 400.0..700.0)
        assertTrue(Geo.distanceM(wgs, Gcj02.toWgs84(gcj)) < 0.5)
        val guangzhou = GeoPoint(23.1291, 113.2644)
        assertTrue(Geo.distanceM(guangzhou, Gcj02.toWgs84(Gcj02.fromWgs84(guangzhou))) < 0.5)
    }

    @Test fun leavesTheRestOfTheWorldUntouched() {
        val doha = GeoPoint(25.2854, 51.5310)
        assertEquals(doha, Gcj02.fromWgs84(doha))
        assertEquals(doha, Gcj02.toWgs84(doha))
    }

    @Test fun searchBoxAcceptsGcjPrefix() {
        val p = CoordinateParser.parse("gcj:39.9101,116.4037")!!
        assertEquals(39.9087, p.lat, 0.0004)
        assertEquals(116.3975, p.lon, 0.0004)
        assertEquals(CoordinateParser.parse("39.9101,116.4037"), CoordinateParser.parse("39.9101, 116.4037"))
        assertNull(CoordinateParser.parse("gcj:hello"))
    }
}
