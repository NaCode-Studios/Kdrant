package dev.kdrant.dsl

import dev.kdrant.model.AliasOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class M19BuildersTest {

    @Test
    fun `updateAliases builder collects operations in order`() {
        val ops = UpdateAliasesBuilder().apply {
            createAlias(collection = "c", alias = "a")
            deleteAlias("old")
            renameAlias(from = "x", to = "y")
        }.build()

        assertEquals(3, ops.size)
        assertEquals(AliasOperation.Create(collectionName = "c", aliasName = "a"), ops[0])
        assertEquals(AliasOperation.Delete(aliasName = "old"), ops[1])
        assertEquals(AliasOperation.Rename(oldAliasName = "x", newAliasName = "y"), ops[2])
    }

    @Test
    fun `searchMatrix builder omits an empty filter`() {
        val req = SearchMatrixBuilder().apply {
            sample = 10
            filter { }
        }.build()

        assertNull(req.filter)
        assertEquals(10, req.sample)
    }

    @Test
    fun `searchMatrix builder keeps a non-empty filter`() {
        val req = SearchMatrixBuilder().apply { filter { must { "lang" eq "en" } } }.build()
        assertNotNull(req.filter)
    }

    @Test
    fun `searchMatrix builder rejects a sample below 2`() {
        assertThrows<IllegalArgumentException> { SearchMatrixBuilder().apply { sample = 1 }.build() }
    }

    @Test
    fun `searchMatrix builder rejects a limit below 1`() {
        assertThrows<IllegalArgumentException> { SearchMatrixBuilder().apply { limit = 0 }.build() }
    }
}
