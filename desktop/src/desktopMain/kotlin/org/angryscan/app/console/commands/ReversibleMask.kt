package org.angryscan.app.console.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import org.angryscan.app.common.MatchersRegister
import org.angryscan.app.common.ScanSettings
import org.angryscan.app.common.UserSignatureSettings
import org.angryscan.app.scan.common.files.ReversibleMasker
import org.angryscan.app.scan.common.files.extensions.requireKeywords
import org.angryscan.app.scan.common.files.types.IFileType
import org.angryscan.app.scan.common.files.types.XLSXType
import org.angryscan.app.scan.engine.buildEngineChain
import org.angryscan.common.engine.IMatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class Mask : SuspendingCliktCommand(
    name = "mask"
), KoinComponent {
    private val scanSettings by inject<ScanSettings>()
    private val userSignatureSettings: UserSignatureSettings by inject()

    private val inputFile by option(
        "-i", "--input",
        help = "XLSX file to mask"
    ).file(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required()

    private val outputPath by option(
        "-o", "--out",
        help = "Path to write the masked XLSX file"
    ).required()

    private val mappingPath by option(
        "--map",
        help = "Path to write the reversible mapping JSON file"
    ).required()

    private val matchers by option(
        "-m", "--matchers",
        help = "Comma separated list of matchers to use"
    )
        .convert { inputValue ->
            MatchersRegister
                .find { it.name.replace(" ", "_") == inputValue }
                ?: throw PrintMessage("Unknown matcher: $inputValue")
        }
        .split(",")
        .default(
            value = scanSettings.matchers,
            defaultForHelp = "Loaded from settings"
        )

    private val userSignatures by option(
        "-us", "--user-signatures",
        help = "Comma separated list of user signatures to use"
    )
        .convert { inputValue ->
            userSignatureSettings
                .userSignatures
                .find { it.name.replace(" ", "_") == inputValue }
                ?: throw PrintMessage("Unknown user signature: $inputValue")
        }
        .split(",")
        .default(
            value = scanSettings.userSignatures,
            defaultForHelp = "Loaded from settings"
        )

    override suspend fun run() {
        requireXlsx(inputFile)
        val outputFile = File(outputPath)
        val mappingFile = File(mappingPath)
        val selectedMatchers: List<IMatcher> = matchers + userSignatures
        if (selectedMatchers.isEmpty()) {
            throw PrintMessage("No matchers selected")
        }

        val fileTypes = IFileType.getFileType(inputFile)
        val engines = buildEngineChain(
            primaryEngineClass = scanSettings.engine.value,
            matchers = selectedMatchers,
            requireKeywords = fileTypes.requireKeywords(inputFile.extension)
        )
        if (engines.isEmpty()) {
            throw PrintMessage("No scan engine can run the selected matchers")
        }

        val result = ReversibleMasker.maskXlsx(
            inputFile = inputFile.absolutePath,
            outputFile = outputFile.absolutePath,
            mappingFile = mappingFile.absolutePath,
            engines = engines
        )

        echo("Masked ${result.maskedCount} values")
        echo("Masked file: ${outputFile.absolutePath}")
        echo("Mapping file: ${mappingFile.absolutePath}")
    }

    private fun requireXlsx(file: File) {
        val supportsXlsx = IFileType.getFileType(file).any { it is XLSXType }
        if (!supportsXlsx) {
            throw PrintMessage("Only .xlsx files are supported by reversible masking for now")
        }
    }
}

class Unmask : SuspendingCliktCommand(
    name = "unmask"
) {
    private val inputFile by option(
        "-i", "--input",
        help = "Masked XLSX file to restore"
    ).file(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required()

    private val outputPath by option(
        "-o", "--out",
        help = "Path to write the restored XLSX file"
    ).required()

    private val mappingFile by option(
        "--map",
        help = "Reversible mapping JSON file created by mask"
    ).file(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required()

    override suspend fun run() {
        requireXlsx(inputFile)
        val outputFile = File(outputPath)
        val result = ReversibleMasker.unmaskXlsx(
            inputFile = inputFile.absolutePath,
            outputFile = outputFile.absolutePath,
            mappingFile = mappingFile.absolutePath
        )

        echo("Restored ${result.maskedCount} values")
        echo("Restored file: ${outputFile.absolutePath}")
    }

    private fun requireXlsx(file: File) {
        val supportsXlsx = IFileType.getFileType(file).any { it is XLSXType }
        if (!supportsXlsx) {
            throw PrintMessage("Only .xlsx files are supported by reversible masking for now")
        }
    }
}
