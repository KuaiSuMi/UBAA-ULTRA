package cn.edu.ubaa.live

import kotlin.test.*

class LiveClassroomCatalogTest {
  private val json = checkNotNull(javaClass.getResource("/classrooms.json")).readText()
  private val catalog = LiveClassroomCatalog.parse(json)

  @Test
  fun bundledRoomsPreserveAllCampusesAndMissingCameras() {
    assertEquals(381, catalog.rooms.size)
    assertEquals(
        mapOf("学院路" to 168, "沙河" to 155, "杭州国际创新研究院" to 58),
        catalog.rooms.groupingBy { it.campus }.eachCount(),
    )
    assertEquals(373, catalog.rooms.count { it.cameras.isNotEmpty() })
    assertEquals(1109, catalog.rooms.sumOf { it.cameras.size })
    assertEquals(listOf(LiveCamera.TEACHER), catalog.matchCourse("主M304")!!.cameras)
    assertTrue(catalog.matchCourse("J1-405")!!.cameras.isEmpty())
  }

  @Test
  fun courseLocationsMatchFullWidthAndBuildingNames() {
    assertEquals("J1-101", catalog.matchCourse("沙河校区 Ｊ１－１０１")?.room)
    assertEquals("(三)101", catalog.matchCourse("学院路校区（三）101")?.room)
    assertEquals("(三)101", catalog.matchCourse("第三教学楼101")?.room)
    assertEquals("主M101", catalog.matchCourse("学院路主楼M101")?.room)
    assertEquals("B101", catalog.matchCourse("新主楼 B101")?.room)
    assertEquals("教学一号楼3001", catalog.matchCourse("杭州国际创新研究院教学一号楼3001")?.room)
  }

  @Test
  fun incompleteAndAmbiguousLocationsNeverOpenAnotherRoom() {
    listOf(null, "", "101", "J1", "J1-1010", "沙河", "沙河主M101", "体育场", "J1-101,J1-102").forEach {
      assertNull(catalog.matchCourse(it), it)
    }
    val room = catalog.matchCourse("J1-101")!!
    val ambiguous = LiveClassroomCatalog(listOf(room, room.copy(campus = "学院路")))
    assertNull(ambiguous.matchCourse("J1-101"))
    assertEquals("沙河", ambiguous.matchCourse("沙河校区J1-101")?.campus)
  }

  @Test
  fun searchCombinesCampusWithNormalizedRoomQuery() {
    assertEquals(listOf("J1-101"), catalog.search(" j１－101 ", "沙河").map { it.room })
    assertTrue(catalog.search("J1", "学院路").isEmpty())
    assertEquals(58, catalog.search("杭州").size)
    assertEquals(381, catalog.search("").size)
    assertEquals(listOf("(三)101"), catalog.search("三教101").map { it.room })
  }

  @Test
  fun eachViewUsesItsOwnProvidedStream() {
    val room = catalog.matchCourse("J1-101")!!
    assertEquals(3, room.cameras.map { room.stream(it) }.distinct().size)
    assertTrue(room.stream(LiveCamera.TEACHER).contains("382A5E12D5A50390"))
    assertTrue(room.stream(LiveCamera.STUDENT).contains("555528E4C3E58C83"))
    assertTrue(room.stream(LiveCamera.SCREEN).contains("109F31276DCDA231"))
  }

  @Test
  fun invalidCatalogUrlsAndDuplicateRoomsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      LiveClassroomCatalog.parse(
          json.replace("https://lmt2.buaa.edu.cn:443", "https://example.com")
      )
    }
    assertFailsWith<IllegalArgumentException> { LiveClassroomCatalog.parse("[]") }
    val single = """{"campus":"沙河","room":"J1-101","teacher":"","student":"","screen":""}"""
    assertFailsWith<IllegalArgumentException> { LiveClassroomCatalog.parse("[$single,$single]") }
  }
}
