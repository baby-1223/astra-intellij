package com.astra.intellij.ui

import com.astra.intellij.core.CollectionRunner
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.workspace.WorkspaceService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class RunnerDialog(
    project: Project,
    private val workspace: WorkspaceService,
    name: String,
    private val requests: List<AstraRequest>,
) : DialogWrapper(project) {
    private val model = DefaultTableModel(arrayOf("#", "Request", "Status", "Time", "Tests", "Result"), 0)
    private val table = JTable(model)
    private val progressLabel = JLabel("0 / ${requests.size}")
    private val runner = CollectionRunner()

    @Volatile
    private var running = false

    private val runAction = object : DialogWrapperAction("Run") {
        override fun doAction(e: java.awt.event.ActionEvent?) = startRun()
    }
    private val stopAction = object : DialogWrapperAction("Stop") {
        override fun doAction(e: java.awt.event.ActionEvent?) {
            runner.cancel()
        }
    }

    init {
        title = "Run $name"
        setCancelButtonText("Close")
        requests.forEachIndexed { index, request ->
            model.addRow(arrayOf<Any>(index + 1, "${request.method}  ${request.name}", "", "", "", "Pending"))
        }
        init()
        stopAction.isEnabled = false
        startRun()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 4)).apply {
        add(JScrollPane(table), BorderLayout.CENTER)
        add(progressLabel, BorderLayout.SOUTH)
        preferredSize = Dimension(720, 420)
    }

    override fun createActions(): Array<Action> = arrayOf(runAction, stopAction, cancelAction)

    override fun doCancelAction() {
        runner.cancel()
        super.doCancelAction()
    }

    private fun startRun() {
        if (running) return
        running = true
        runAction.isEnabled = false
        stopAction.isEnabled = true
        for (row in 0 until model.rowCount) {
            model.setValueAt("", row, 2)
            model.setValueAt("", row, 3)
            model.setValueAt("", row, 4)
            model.setValueAt("Pending", row, 5)
        }
        progressLabel.text = "0 / ${requests.size}"
        val vars = workspace.activeVariables()
        val environment = workspace.data.environments.firstOrNull { it.id == workspace.data.activeEnvironmentId }
        val settings = workspace.data.settings
        Thread {
            runner.run(requests, vars, environment, settings) { index, result ->
                ApplicationManager.getApplication().invokeLater {
                    if (index >= model.rowCount) return@invokeLater
                    model.setValueAt(if (result.error == null) result.status else "ERR", index, 2)
                    model.setValueAt("${result.timeMs} ms", index, 3)
                    model.setValueAt(
                        if (result.tests.isEmpty()) "-" else "${result.tests.count { it.passed }}/${result.tests.size}",
                        index,
                        4,
                    )
                    model.setValueAt(result.error ?: if (result.passed) "PASS" else "FAIL", index, 5)
                    progressLabel.text = "${index + 1} / ${requests.size}"
                }
            }
            ApplicationManager.getApplication().invokeLater {
                running = false
                runAction.isEnabled = true
                stopAction.isEnabled = false
            }
        }.apply {
            isDaemon = true
            name = "Astra-Collection-Runner"
            start()
        }
    }
}
