package org.angryscan.app.scan.common.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.angryscan.app.scan.common.files.locations.XLSLocation
import org.angryscan.app.scan.common.files.types.XLSXType
import org.angryscan.common.engine.IScanEngine
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@Serializable
data class ReversibleMapping(
    val version: Int = 1,
    val entries: List<ReversibleMappingEntry>
)

@Serializable
data class ReversibleMappingEntry(
    val token: String,
    val original: String,
    val matcher: String,
    val location: String,
    val sheet: String,
    val row: Int,
    val col: Int,
    val cell: String
)

data class ReversibleMaskResult(
    val maskedCount: Int,
    val mappingEntries: Int
)

object ReversibleMasker {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun maskXlsx(
        inputFile: String,
        outputFile: String,
        mappingFile: String,
        engine: IScanEngine
    ): ReversibleMaskResult = maskXlsx(
        inputFile = inputFile,
        outputFile = outputFile,
        mappingFile = mappingFile,
        engines = listOf(engine)
    )

    suspend fun maskXlsx(
        inputFile: String,
        outputFile: String,
        mappingFile: String,
        engines: List<IScanEngine>
    ): ReversibleMaskResult {
        val locations = engines
            .flatMap { engine -> XLSXType.findLocation(inputFile, engine) }
            .filter { it.attachmentName == null }
            .map { it as XLSLocation }

        val entries = mutableListOf<ReversibleMappingEntry>()
        var maskedCount = 0

        withContext(Dispatchers.IO) {
            FileInputStream(inputFile).use { inputStream ->
                XSSFWorkbook(inputStream).use { workbook ->
                    locations
                        .groupBy { it.sheet }
                        .forEach { (sheetName, sheetLocations) ->
                            val sheet = workbook.getSheet(sheetName) ?: return@forEach
                            sheetLocations.forEach { location ->
                                val row = sheet.getRow(location.row) ?: return@forEach
                                val cell = row.getCell(location.col) ?: return@forEach
                                val originalCellValue = cellValueAsString(cell)
                                val token = tokenFor(location, entries.size + 1)
                                val replaced = originalCellValue.replaceFirst(location.entry.value, token)

                                if (replaced != originalCellValue) {
                                    setCellStringValue(cell, replaced)
                                    entries.add(
                                        ReversibleMappingEntry(
                                            token = token,
                                            original = location.entry.value,
                                            matcher = location.entry.matcher.name,
                                            location = location.location,
                                            sheet = location.sheet,
                                            row = location.row,
                                            col = location.col,
                                            cell = location.cell
                                        )
                                    )
                                    maskedCount++
                                }
                            }
                        }

                    ensureParentDirectory(outputFile)
                    FileOutputStream(outputFile).use { outputStream ->
                        workbook.write(outputStream)
                    }
                }
            }

            ensureParentDirectory(mappingFile)
            File(mappingFile).writeText(
                json.encodeToString(ReversibleMapping(entries = entries))
            )
        }

        return ReversibleMaskResult(
            maskedCount = maskedCount,
            mappingEntries = entries.size
        )
    }

    suspend fun unmaskXlsx(
        inputFile: String,
        outputFile: String,
        mappingFile: String
    ): ReversibleMaskResult {
        val mapping = withContext(Dispatchers.IO) {
            json.decodeFromString<ReversibleMapping>(File(mappingFile).readText())
        }
        var restoredCount = 0

        withContext(Dispatchers.IO) {
            FileInputStream(inputFile).use { inputStream ->
                XSSFWorkbook(inputStream).use { workbook ->
                    mapping.entries
                        .groupBy { it.sheet }
                        .forEach { (sheetName, entries) ->
                            val sheet = workbook.getSheet(sheetName) ?: return@forEach
                            entries.forEach { entry ->
                                val row = sheet.getRow(entry.row) ?: return@forEach
                                val cell = row.getCell(entry.col) ?: return@forEach
                                val currentValue = cellValueAsString(cell)
                                val restored = currentValue.replace(entry.token, entry.original)

                                if (restored != currentValue) {
                                    setCellStringValue(cell, restored)
                                    restoredCount++
                                }
                            }
                        }

                    ensureParentDirectory(outputFile)
                    FileOutputStream(outputFile).use { outputStream ->
                        workbook.write(outputStream)
                    }
                }
            }
        }

        return ReversibleMaskResult(
            maskedCount = restoredCount,
            mappingEntries = mapping.entries.size
        )
    }

    private fun setCellStringValue(cell: org.apache.poi.ss.usermodel.Cell, value: String) {
        val style = cell.cellStyle
        cell.setBlank()
        cell.cellStyle = style
        cell.setCellValue(value)
    }

    private fun cellValueAsString(cell: org.apache.poi.ss.usermodel.Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toString()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> cell.cellFormula
            else -> cell.toString()
        }
    }

    private fun tokenFor(location: XLSLocation, index: Int): String {
        val matcherName = location.entry.matcher.name
            .uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "PII" }
        return "[[ADS_${matcherName}_${index.toString().padStart(6, '0')}]]"
    }

    private fun ensureParentDirectory(path: String) {
        File(path).parentFile?.mkdirs()
    }
}
