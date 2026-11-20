package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment

object PreRequestService {
    /**
     * Executes declarative pre-request instructions, one per line:
     *   set <name> <value>   -> stores in the active environment and this run (value supports variables)
     *   unset <name>         -> removes from the active environment and this run
     * Lines starting with '#' are comments. Returns the variables for this request run.
     */
    fun execute(
        lines: List<String>,
        baseVars: Map<String, String>,
        environment: AstraEnvironment?,
    ): Map<String, String> {
        val vars = baseVars.toMutableMap()
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val tokens = line.split(Regex("\\s+"), limit = 3)
            when (tokens[0].lowercase()) {
                "set" -> if (tokens.size == 3) {
                    val name = tokens[1]
                    val value = VariableResolver.resolve(tokens[2], vars)
                    vars[name] = value
                    environment?.variables?.set(name, value)
                }
                "unset" -> if (tokens.size >= 2) {
                    vars.remove(tokens[1])
                    environment?.variables?.remove(tokens[1])
                }
            }
        }
        return vars
    }
}
