package dev.mcrecon.cli

import java.io.PrintWriter
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(McReconCli().run(args, PrintWriter(System.out), PrintWriter(System.err)))
}
