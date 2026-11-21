package com.astra.intellij.ui

import java.awt.Component
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * File chooser that always renders English labels, independent of the OS or JVM locale.
 *
 * The platform look and feel delegates JFileChooser to the native OS panel on macOS, and that panel
 * follows the system language. Overriding the UI delegate with the Swing implementation keeps every
 * label under our control.
 */
object AstraFileChooser {
    fun open(parent: Component?, title: String, description: String, vararg extensions: String): File? =
        withEnglishChooser {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileFilter = FileNameExtensionFilter(description, *extensions)
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    fun save(parent: Component?, title: String, defaultName: String, extension: String): File? =
        withEnglishChooser {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                selectedFile = File(defaultName)
                fileFilter = FileNameExtensionFilter("$extension files", extension)
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
                null
            } else {
                val selected = chooser.selectedFile
                when {
                    selected == null -> null
                    selected.extension.equals(extension, true) -> selected
                    else -> File("${selected.path}.$extension")
                }
            }
        }

    private val englishDefaults = mapOf(
        "FileChooserUI" to "javax.swing.plaf.metal.MetalFileChooserUI",
        "FileChooser.openButtonText" to "Open",
        "FileChooser.openButtonToolTipText" to "Open",
        "FileChooser.cancelButtonText" to "Cancel",
        "FileChooser.cancelButtonToolTipText" to "Cancel",
        "FileChooser.saveButtonText" to "Save",
        "FileChooser.saveButtonToolTipText" to "Save",
        "FileChooser.fileNameLabelText" to "File name:",
        "FileChooser.filesOfTypeLabelText" to "Files of type:",
        "FileChooser.lookInLabelText" to "Look in:",
        "FileChooser.upFolderToolTipText" to "Up one level",
        "FileChooser.homeFolderToolTipText" to "Home",
        "FileChooser.newFolderToolTipText" to "Create new folder",
        "FileChooser.listViewButtonToolTipText" to "List",
        "FileChooser.detailsViewButtonToolTipText" to "Details",
        "FileChooser.acceptAllFileFilterText" to "All files",
    )

    private fun <T> withEnglishChooser(block: () -> T): T {
        val previousLocale = Locale.getDefault()
        val defaults = UIManager.getLookAndFeelDefaults()
        val previous = englishDefaults.keys.associateWith { defaults[it] }
        Locale.setDefault(Locale.ENGLISH)
        return try {
            englishDefaults.forEach { (key, value) -> defaults[key] = value }
            block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) defaults.remove(key) else defaults[key] = value
            }
            Locale.setDefault(previousLocale)
        }
    }
}
