package com.usool.map.routing

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.usool.map.core.BBox
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Compact road network for one downloaded region, built from OpenStreetMap ways. It is what makes
 * route calculation possible with no connection. Edges are directed and stored sorted by origin
 * node so that the outgoing edges of node `n` are `edgeStart[n] until edgeStart[n + 1]`.
 */
class RoadGraph(
    val bbox: BBox,
    private val latE7: IntArray,
    private val lonE7: IntArray,
    val edgeStart: IntArray,
    val edgeTo: IntArray,
    val edgeLengthM: FloatArray,
    val edgeClass: ByteArray,
    val edgeFlags: ByteArray,
    val edgeMaxSpeedKmh: ShortArray,
    val edgeName: IntArray,
    val names: Array<String>,
) {
    val nodeCount: Int get() = latE7.size
    val edgeCount: Int get() = edgeTo.size

    fun lat(node: Int): Double = latE7[node] / 1e7
    fun lon(node: Int): Double = lonE7[node] / 1e7
    fun point(node: Int) = GeoPoint(lat(node), lon(node))
    fun name(edge: Int): String? = edgeName[edge].takeIf { it >= 0 }?.let { names[it] }
    fun allows(edge: Int, flag: Int): Boolean = edgeFlags[edge].toInt() and flag != 0

    /** Nearest node with at least one outgoing edge usable by [flag]; null when none within [maxM]. */
    fun nearestNode(p: GeoPoint, flag: Int, maxM: Double = 500.0): Int? {
        var best = -1
        var bestD = Double.MAX_VALUE
        val k = Math.cos(Math.toRadians(p.lat))
        for (n in 0 until nodeCount) {
            val dLat = lat(n) - p.lat
            val dLon = (lon(n) - p.lon) * k
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) {
                var usable = false
                for (e in edgeStart[n] until edgeStart[n + 1]) if (allows(e, flag)) { usable = true; break }
                if (usable) { bestD = d; best = n }
            }
        }
        if (best < 0) return null
        return best.takeIf { Geo.distanceM(p, point(it)) <= maxM }
    }

    fun write(out: OutputStream) {
        DataOutputStream(BufferedOutputStream(out)).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            d.writeDouble(bbox.south); d.writeDouble(bbox.west); d.writeDouble(bbox.north); d.writeDouble(bbox.east)
            d.writeInt(nodeCount)
            for (i in 0 until nodeCount) { d.writeInt(latE7[i]); d.writeInt(lonE7[i]) }
            for (i in 0..nodeCount) d.writeInt(edgeStart[i])
            d.writeInt(edgeCount)
            for (e in 0 until edgeCount) {
                d.writeInt(edgeTo[e]); d.writeFloat(edgeLengthM[e]); d.writeByte(edgeClass[e].toInt())
                d.writeByte(edgeFlags[e].toInt()); d.writeShort(edgeMaxSpeedKmh[e].toInt()); d.writeInt(edgeName[e])
            }
            d.writeInt(names.size)
            names.forEach { d.writeUTF(it) }
        }
    }

    companion object {
        private const val MAGIC = 0x55534D47 // "USMG"
        private const val VERSION = 1

        const val CAR = 1
        const val FOOT = 2
        const val BIKE = 4
        const val ROUNDABOUT = 8
        const val HGV_BLOCKED = 16
        const val TOLL = 32

        // Road classes, fastest first. Link roads share the class of the road they connect to.
        const val MOTORWAY = 0
        const val TRUNK = 1
        const val PRIMARY = 2
        const val SECONDARY = 3
        const val TERTIARY = 4
        const val MINOR = 5
        const val RESIDENTIAL = 6
        const val LIVING_STREET = 7
        const val SERVICE = 8
        const val TRACK = 9
        const val FOOTWAY = 10
        const val CYCLEWAY = 11
        const val STEPS = 12

        fun read(input: InputStream): RoadGraph = DataInputStream(BufferedInputStream(input)).use { d ->
            if (d.readInt() != MAGIC) throw IOException("Not a UsoolMap road graph")
            if (d.readInt() != VERSION) throw IOException("Road graph was written by a different app version")
            val bbox = BBox(d.readDouble(), d.readDouble(), d.readDouble(), d.readDouble())
            val n = d.readInt()
            val lat = IntArray(n)
            val lon = IntArray(n)
            for (i in 0 until n) { lat[i] = d.readInt(); lon[i] = d.readInt() }
            val start = IntArray(n + 1) { d.readInt() }
            val m = d.readInt()
            val to = IntArray(m); val len = FloatArray(m); val cls = ByteArray(m)
            val flags = ByteArray(m); val max = ShortArray(m); val name = IntArray(m)
            for (e in 0 until m) {
                to[e] = d.readInt(); len[e] = d.readFloat(); cls[e] = d.readByte()
                flags[e] = d.readByte(); max[e] = d.readShort(); name[e] = d.readInt()
            }
            val names = Array(d.readInt()) { d.readUTF() }
            RoadGraph(bbox, lat, lon, start, to, len, cls, flags, max, name, names)
        }

        /** Overpass query that returns every routable way in [b] plus the nodes they reference. */
        fun overpassQuery(b: BBox): String =
            "[out:json][timeout:180];way[\"highway\"][\"area\"!=\"yes\"](${b.south},${b.west},${b.north},${b.east});out body;>;out skel qt;"

        private val CLASS_BY_HIGHWAY = mapOf(
            "motorway" to MOTORWAY, "motorway_link" to MOTORWAY, "trunk" to TRUNK, "trunk_link" to TRUNK,
            "primary" to PRIMARY, "primary_link" to PRIMARY, "secondary" to SECONDARY, "secondary_link" to SECONDARY,
            "tertiary" to TERTIARY, "tertiary_link" to TERTIARY, "unclassified" to MINOR, "road" to MINOR,
            "residential" to RESIDENTIAL, "living_street" to LIVING_STREET, "service" to SERVICE, "track" to TRACK,
            "footway" to FOOTWAY, "pedestrian" to FOOTWAY, "path" to FOOTWAY, "bridleway" to FOOTWAY,
            "cycleway" to CYCLEWAY, "steps" to STEPS,
        )
        private val NO = setOf("no", "private")

        private class RawWay(val nodes: LongArray, val cls: Int, val forward: Int, val backward: Int, val maxSpeed: Int, val name: String?)

        /** Parses "50", "30 mph", "walk" and similar into km/h; 0 when unknown. */
        fun parseMaxSpeed(value: String?): Int {
            val v = value?.trim()?.lowercase() ?: return 0
            if (v == "walk") return 7
            val number = v.takeWhile { it.isDigit() }.toIntOrNull() ?: return 0
            return if (v.contains("mph")) Math.round(number * 1.609344f) else number
        }

        /** Direction flags for a way: first = along the way's node order, second = against it. */
        fun accessFlags(tags: Map<String, String>): Triple<Int, Int, Int>? {
            val cls = CLASS_BY_HIGHWAY[tags["highway"]] ?: return null
            val access = tags["access"]
            val blockedAll = access in NO
            fun allowed(default: Boolean, vararg keys: String): Boolean {
                for (k in keys) {
                    val v = tags[k] ?: continue
                    return v !in NO
                }
                return default && !blockedAll
            }
            val car = allowed(cls <= SERVICE, "motorcar", "motor_vehicle", "vehicle")
            val foot = allowed(cls != MOTORWAY && cls != TRUNK && cls != CYCLEWAY || tags["sidewalk"] in setOf("both", "left", "right", "yes"), "foot")
            val bike = allowed(cls != MOTORWAY && cls != TRUNK && cls != FOOTWAY && cls != STEPS, "bicycle", "vehicle")
            val roundabout = tags["junction"] == "roundabout" || tags["junction"] == "circular"
            val oneway = tags["oneway"]
            val reversed = oneway == "-1" || oneway == "reverse"
            val isOneway = reversed || oneway in setOf("yes", "1", "true") || (oneway == null && (roundabout || tags["highway"] == "motorway"))
            val bikeBothWays = tags["oneway:bicycle"] == "no" || tags["cycleway"]?.startsWith("opposite") == true
            var extra = 0
            if (roundabout) extra = extra or ROUNDABOUT
            if (tags["hgv"] in NO) extra = extra or HGV_BLOCKED
            if (tags["toll"] == "yes") extra = extra or TOLL
            var forward = extra or (if (foot) FOOT else 0)
            var backward = forward
            val along = (if (car) CAR else 0) or (if (bike) BIKE else 0)
            if (!isOneway) { forward = forward or along; backward = backward or along }
            else {
                if (reversed) backward = backward or along else forward = forward or along
                if (bike && bikeBothWays) { forward = forward or BIKE; backward = backward or BIKE }
            }
            return Triple(cls, forward, backward)
        }

        /**
         * Builds a graph from an Overpass JSON response, read as a stream so a city-sized
         * download does not have to be held in memory as text.
         */
        fun fromOverpass(input: InputStream, bbox: BBox): RoadGraph {
            val ways = ArrayList<RawWay>()
            val nodeIds = HashMap<Long, Int>()
            val coords = HashMap<Long, Long>()
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "elements") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var type = ""; var id = 0L; var lat = 0.0; var lon = 0.0
                        var refs: LongArray? = null
                        val tags = HashMap<String, String>()
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "type" -> type = r.nextString()
                                "id" -> id = r.nextLong()
                                "lat" -> lat = r.nextDouble()
                                "lon" -> lon = r.nextDouble()
                                "nodes" -> {
                                    val list = ArrayList<Long>()
                                    r.beginArray(); while (r.hasNext()) list += r.nextLong(); r.endArray()
                                    refs = list.toLongArray()
                                }
                                "tags" -> {
                                    r.beginObject()
                                    while (r.hasNext()) {
                                        val k = r.nextName()
                                        if (r.peek() == JsonToken.STRING) tags[k] = r.nextString() else r.skipValue()
                                    }
                                    r.endObject()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (type == "node") {
                            coords[id] = (Math.round(lat * 1e7).toLong() shl 32) or (Math.round(lon * 1e7).toLong() and 0xffffffffL)
                        } else if (type == "way" && refs != null && refs.size >= 2) {
                            val a = accessFlags(tags) ?: continue
                            if (a.second and (CAR or FOOT or BIKE) == 0 && a.third and (CAR or FOOT or BIKE) == 0) continue
                            ways += RawWay(refs, a.first, a.second, a.third, parseMaxSpeed(tags["maxspeed"]), tags["name"] ?: tags["ref"])
                        }
                    }
                    r.endArray()
                }
                r.endObject()
            }
            if (ways.isEmpty()) throw IOException("No roads were found in this area.")

            for (w in ways) for (id in w.nodes) if (coords.containsKey(id)) nodeIds.getOrPut(id) { nodeIds.size }
            val n = nodeIds.size
            val lat = IntArray(n); val lon = IntArray(n)
            for ((id, idx) in nodeIds) { val c = coords.getValue(id); lat[idx] = (c shr 32).toInt(); lon[idx] = c.toInt() }

            val nameIndex = LinkedHashMap<String, Int>()
            // Pass 1 counts outgoing edges per node, pass 2 fills the arrays in sorted order.
            val degree = IntArray(n + 1)
            forEachEdge(ways, nodeIds) { from, _, _, _ -> degree[from + 1]++ }
            for (i in 1..n) degree[i] += degree[i - 1]
            val m = degree[n]
            val start = degree.copyOf()
            val cursor = degree.copyOf(n)
            val to = IntArray(m); val len = FloatArray(m); val cls = ByteArray(m)
            val flags = ByteArray(m); val max = ShortArray(m); val name = IntArray(m)
            forEachEdge(ways, nodeIds) { from, dest, w, f ->
                val e = cursor[from]++
                to[e] = dest
                len[e] = Geo.distanceM(GeoPoint(lat[from] / 1e7, lon[from] / 1e7), GeoPoint(lat[dest] / 1e7, lon[dest] / 1e7)).toFloat()
                cls[e] = w.cls.toByte()
                flags[e] = f.toByte()
                max[e] = w.maxSpeed.coerceIn(0, 300).toShort()
                name[e] = w.name?.let { nameIndex.getOrPut(it) { nameIndex.size } } ?: -1
            }
            return RoadGraph(bbox, lat, lon, start, to, len, cls, flags, max, name, nameIndex.keys.toTypedArray())
        }

        private inline fun forEachEdge(ways: List<RawWay>, ids: Map<Long, Int>, block: (from: Int, to: Int, way: RawWay, flags: Int) -> Unit) {
            for (w in ways) {
                for (i in 1 until w.nodes.size) {
                    val a = ids[w.nodes[i - 1]] ?: continue
                    val b = ids[w.nodes[i]] ?: continue
                    if (a == b) continue
                    if (w.forward and (CAR or FOOT or BIKE) != 0) block(a, b, w, w.forward)
                    if (w.backward and (CAR or FOOT or BIKE) != 0) block(b, a, w, w.backward)
                }
            }
        }
    }
}
