package com.github.limoiie.cppman.services.impls

import com.github.limoiie.cppman.database.dao.ManFile
import com.github.limoiie.cppman.database.dao.ManKeyword
import com.github.limoiie.cppman.database.dao.ManSection
import com.github.limoiie.cppman.database.dao.ManSource
import com.github.limoiie.cppman.database.dsl.ManFileSections
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import org.jetbrains.exposed.sql.insert
import java.nio.file.Files
import java.nio.file.Path

object ManIndex {
    private const val manPrefix = "man"
    private val logger = logger<ManIndex>()

    /**
     * Index each man source in [manSourcePaths] sequentially.
     *
     * A man source is a directory which contains sub directories like 'man1', 'man2', etc.
     */
    fun indexSources(manSourcePaths: List<Path>) {
        manSourcePaths.forEach(ManIndex::indexOneSource)
        logger.debug {
            val c = ManFile.count()
            "ManFile Count: $c"
        }
    }

    /**
     * Index one source on [manSourcePath].
     */
    private fun indexOneSource(manSourcePath: Path) {
        val fnParseManFileEntry = { (section, manFilePath): Pair<String, Path> ->
            parseManFileEntry(manSourcePath, section, manFilePath)
        }

        val manEntryIndex = ManEntriesIndex(manSourcePath)
        enumerateManFiles(manSourcePath)
            .map(fnParseManFileEntry)
            .filterNotNull()
            .forEach { entry ->
                manEntryIndex.index(entry)
            }
    }

    /**
     * Enumerate man files under [manSourcePath].
     *
     * Each item in the returned stream is a pair of the [ManSection] and the path
     * of the man file
     */
    private fun enumerateManFiles(manSourcePath: Path): Sequence<Pair<String, Path>> {
        val fnIsManSecDir = { path: Path ->
            Files.isDirectory(path) &&
                    path.fileName.toString().startsWith(manPrefix)
        }
        return Files.walk(manSourcePath, 1)
            .iterator().asSequence().drop(1) // drop itself
            .filter(fnIsManSecDir)
            .flatMap { manSecDirPath -> // mann
                val section = manSecDirPath.fileName.toString().substring(manPrefix.length)
                Files.walk(manSecDirPath)
                    .iterator().asSequence().drop(1) // drop itself
                    .filter { Files.isRegularFile(it) }
                    .map { Pair(section, it) }
            }
    }

    private class ManEntriesIndex(private val manSourcePath: Path) {
        private val manSource = ManSource.new {
            path = manSourcePath.toString()
        }

        private val manSectionsCached = ManSection.all()
            .associateBy(ManSection::name).toMutableMap()

        fun index(entry: ManFileEntry) {
            val manFile = ManFile.new {
                path = entry.manFile.toString()
                src = manSource
            }

            val manSection = newManSectionIfNotExist(entry.sections.first())

            ManFileSections.insert {
                it[this.file] = manFile.id
                it[this.section] = manSection.id
            }

            for (kw in entry.keywords) {
                ManKeyword.new {
                    keyword = kw
                    file = manFile
                }
            }
        }

        private fun newManSectionIfNotExist(section: String): ManSection {
            if (section !in manSectionsCached) {
                manSectionsCached[section] = ManSection.new {
                    name = section
                }
            }
            return manSectionsCached[section]!!
        }
    }
}