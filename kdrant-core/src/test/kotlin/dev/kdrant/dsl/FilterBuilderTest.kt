@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.Filter
import dev.kdrant.model.GeoPoint
import dev.kdrant.model.PointId
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FilterBuilderTest {

    private fun json(configure: FilterBuilder.() -> Unit): String =
        KdrantJson.encodeToString(Filter.serializer(), filter(configure))

    @Test
    fun `must with exact match and range`() {
        assertJsonEquals(
            """
            {"must":[
              {"key":"city","match":{"value":"London"}},
              {"key":"price","range":{"gte":100.0,"lte":450.0}}
            ]}
            """.trimIndent(),
            json {
                must {
                    "city" eq "London"
                    "price" between 100.0..450.0
                }
            },
        )
    }

    @Test
    fun `should and must_not and min_should clauses`() {
        assertJsonEquals(
            """
            {
              "should":[{"key":"color","match":{"any":["red","green"]}}],
              "must_not":[{"key":"archived","match":{"value":true}}],
              "min_should":{"conditions":[
                {"key":"tag","match":{"value":"promo"}},
                {"key":"tag","match":{"value":"featured"}}
              ],"min_count":2}
            }
            """.trimIndent(),
            json {
                should { matchAny("color", "red", "green") }
                mustNot { "archived" eq true }
                minShould(2) {
                    "tag" eq "promo"
                    "tag" eq "featured"
                }
            },
        )
    }

    @Test
    fun `match except and full text and integer match`() {
        assertJsonEquals(
            """
            {"must":[
              {"key":"color","match":{"except":["black","yellow"]}},
              {"key":"description","match":{"text":"good cheap"}},
              {"key":"year","match":{"value":2024}}
            ]}
            """.trimIndent(),
            json {
                must {
                    matchExcept("color", "black", "yellow")
                    matchText("description", "good cheap")
                    "year" eq 2024
                }
            },
        )
    }

    @Test
    fun `numeric range shorthands and values count`() {
        assertJsonEquals(
            """
            {"must":[
              {"key":"price","range":{"gt":100.0}},
              {"key":"comments","values_count":{"gt":2}}
            ]}
            """.trimIndent(),
            json {
                must {
                    "price" gt 100
                    valuesCount("comments", gt = 2)
                }
            },
        )
    }

    @Test
    fun `datetime range`() {
        assertJsonEquals(
            """
            {"must":[{"key":"created_at","range":{
              "gt":"2023-02-08T10:49:00Z","lte":"2024-01-31T10:14:31Z"
            }}]}
            """.trimIndent(),
            json {
                must {
                    datetimeRange("created_at", gt = "2023-02-08T10:49:00Z", lte = "2024-01-31T10:14:31Z")
                }
            },
        )
    }

    @Test
    fun `geo radius and bounding box`() {
        assertJsonEquals(
            """
            {"must":[
              {"key":"location","geo_radius":{"center":{"lon":13.4,"lat":52.5},"radius":1000.0}},
              {"key":"loc2","geo_bounding_box":{
                "top_left":{"lon":13.4,"lat":52.5},
                "bottom_right":{"lon":13.5,"lat":52.4}
              }}
            ]}
            """.trimIndent(),
            json {
                must {
                    geoRadius("location", GeoPoint(lon = 13.4, lat = 52.5), radius = 1000.0)
                    geoBoundingBox(
                        "loc2",
                        topLeft = GeoPoint(lon = 13.4, lat = 52.5),
                        bottomRight = GeoPoint(lon = 13.5, lat = 52.4),
                    )
                }
            },
        )
    }

    @Test
    fun `geo polygon with an interior ring`() {
        assertJsonEquals(
            """
            {"must":[{"key":"area","geo_polygon":{
              "exterior":{"points":[
                {"lon":-70.0,"lat":-70.0},{"lon":60.0,"lat":-70.0},
                {"lon":60.0,"lat":60.0},{"lon":-70.0,"lat":-70.0}
              ]},
              "interiors":[{"points":[
                {"lon":-50.0,"lat":-50.0},{"lon":40.0,"lat":-50.0},
                {"lon":-50.0,"lat":-50.0}
              ]}]
            }}]}
            """.trimIndent(),
            json {
                must {
                    geoPolygon(
                        "area",
                        exterior = listOf(
                            GeoPoint(-70.0, -70.0), GeoPoint(60.0, -70.0),
                            GeoPoint(60.0, 60.0), GeoPoint(-70.0, -70.0),
                        ),
                        interiors = listOf(
                            listOf(
                                GeoPoint(-50.0, -50.0), GeoPoint(40.0, -50.0),
                                GeoPoint(-50.0, -50.0),
                            ),
                        ),
                    )
                }
            },
        )
    }

    @Test
    fun `special conditions is_empty is_null has_id has_vector`() {
        assertJsonEquals(
            """
            {"must":[
              {"is_empty":{"key":"reports"}},
              {"is_null":{"key":"notes"}},
              {"has_id":[1,3,"550e8400-e29b-41d4-a716-446655440000"]},
              {"has_vector":"image"}
            ]}
            """.trimIndent(),
            json {
                must {
                    isEmpty("reports")
                    isNull("notes")
                    hasId(PointId.num(1), PointId.num(3), PointId.uuid("550e8400-e29b-41d4-a716-446655440000"))
                    hasVector("image")
                }
            },
        )
    }

    @Test
    fun `nested object filter`() {
        assertJsonEquals(
            """
            {"must":[{"nested":{"key":"diet","filter":{"must":[
              {"key":"food","match":{"value":"meat"}},
              {"key":"likes","match":{"value":true}}
            ]}}}]}
            """.trimIndent(),
            json {
                must {
                    nested("diet") {
                        must {
                            "food" eq "meat"
                            "likes" eq true
                        }
                    }
                }
            },
        )
    }

    @Test
    fun `recursive sub-filter for grouped boolean logic`() {
        assertJsonEquals(
            """
            {"must_not":[{"must":[
              {"key":"city","match":{"value":"London"}},
              {"key":"color","match":{"value":"red"}}
            ]}]}
            """.trimIndent(),
            json {
                mustNot {
                    filter {
                        must {
                            "city" eq "London"
                            "color" eq "red"
                        }
                    }
                }
            },
        )
    }

    @Test
    fun `repeated clause blocks accumulate conditions`() {
        assertJsonEquals(
            """
            {"must":[
              {"key":"a","match":{"value":1}},
              {"key":"b","match":{"value":2}}
            ]}
            """.trimIndent(),
            json {
                must { "a" eq 1 }
                must { "b" eq 2 }
            },
        )
    }

    @Test
    fun `unsupported match value type is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            filter { must { "k" eq listOf(1, 2) } }
        }
    }

    @Test
    fun `range without any bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            filter { must { range("price") } }
        }
    }

    @Test
    fun `empty match-any is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            filter { must { matchAny("color") } }
        }
    }

    @Test
    fun `empty has-id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            filter { must { hasId(emptyList()) } }
        }
    }

    @Test
    fun `empty clause blocks normalize away instead of matching everything`() {
        // Regression: an empty block used to leave a non-null-but-empty list, which serialized to a
        // match-all filter and, on delete-by-filter, wiped the whole collection.
        assertJsonEquals("{}", json { must { } })
        assertJsonEquals("{}", json { should { }; mustNot { } })
        assertJsonEquals("{}", json { minShould(1) { } })
    }
}
