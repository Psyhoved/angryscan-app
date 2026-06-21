package org.angryscan.app.types

import IKoinTestRule
import kotlinx.coroutines.runBlocking
import org.angryscan.app.scan.common.files.ReversibleMasker
import org.angryscan.app.scan.common.files.types.XLSXType
import org.angryscan.app.scan.engine.toHyperScanMatchers
import org.angryscan.app.searcher.Matrix
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


class XLSXTypeTest : IKoinTestRule {
    @Test
    fun findLocation() {
        val fileName = "first/first.xlsx"

        CheckLocation.checkByMap(
            fileName,
            XLSXType::findLocation
        )
    }

    @Test
    fun maskLocation() {
        val fileName = "first/first.xlsx"

        CheckLocation.maskLocations(
            fileName,
            XLSXType::findLocation,
            XLSXType::maskLocations
        )
    }

    @Test
    fun reversibleMaskingRestoresXlsxContent() {
        val fileName = "first/first.xlsx"
        val originalPath = javaClass.getResource("/files/$fileName")?.file
        assertNotNull(originalPath)
        val matchers = Matrix.getMap(fileName)?.keys?.toList()
        assertNotNull(matchers)

        val maskedFile = File.createTempFile("ADS_masked_", ".xlsx")
        val restoredFile = File.createTempFile("ADS_restored_", ".xlsx")
        val mappingFile = File.createTempFile("ADS_mapping_", ".json")

        try {
            val result = runBlocking {
                ReversibleMasker.maskXlsx(
                    inputFile = originalPath,
                    outputFile = maskedFile.absolutePath,
                    mappingFile = mappingFile.absolutePath,
                    engine = org.angryscan.common.engine.hyperscan.HyperScanEngine(matchers.toHyperScanMatchers())
                )
            }

            assertEquals(true, result.maskedCount > 0)
            assertEquals(true, mappingFile.readText().contains("ADS_"))

            runBlocking {
                ReversibleMasker.unmaskXlsx(
                    inputFile = maskedFile.absolutePath,
                    outputFile = restoredFile.absolutePath,
                    mappingFile = mappingFile.absolutePath
                )
            }

            assertEquals(
                readWorkbookValues(originalPath),
                readWorkbookValues(restoredFile.absolutePath)
            )
        } finally {
            maskedFile.delete()
            restoredFile.delete()
            mappingFile.delete()
        }
    }

    private fun readWorkbookValues(path: String): List<List<List<String>>> {
        FileInputStream(path).use { input ->
            XSSFWorkbook(input).use { workbook ->
                return (0 until workbook.numberOfSheets).map { sheetIndex ->
                    val sheet = workbook.getSheetAt(sheetIndex)
                    (0..sheet.lastRowNum).map { rowIndex ->
                        val row = sheet.getRow(rowIndex)
                        val lastCell = row?.lastCellNum?.toInt() ?: 0
                        (0 until lastCell).map { colIndex ->
                            val cell = row?.getCell(colIndex)
                            when (cell?.cellType) {
                                CellType.STRING -> cell.stringCellValue
                                CellType.NUMERIC -> cell.numericCellValue.toString()
                                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                CellType.FORMULA -> cell.cellFormula
                                else -> ""
                            }
                        }
                    }
                }
            }
        }
    }
}
