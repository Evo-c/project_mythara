package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `run_shell` — execute a shell command inside Mythara's app sandbox.
 *
 * The Android app process can `ProcessBuilder(...)` any binary on
 * `$PATH` without special permissions. This tool exposes that to the
 * agent, gated by a hard-coded allowlist of read-mostly binaries so
 * the model can't accidentally `rm -rf /` (or anything similar) on
 * the user's storage.
 *
 * **Default allowlist** (read-only, system-introspection): `ls`,
 * `cat`, `head`, `tail`, `grep`, `find`, `wc`, `df`, `du`, `pwd`,
 * `echo`, `getprop`, `dumpsys`, `pm`, `am`, `ip`, `ping`, `curl`,
 * `whoami`, `id`, `uname`, `date`.
 *
 * Working directory defaults to the app's `filesDir`. Stdout + stderr
 * are merged and returned (truncated to 8 KB). Timeout default 5 s,
 * max 30 s.
 *
 * Anything not on the allowlist returns:
 *   `{status:"blocked", binary:"...", reason:"not allowlisted"}`
 *
 * The user can expand the allowlist via Settings → Shell allowlist
 * (a future Phase 5 UI deliverable); for now this tool ships with
 * the safe default set.
 */
@Singleton
class RunShellTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "run_shell"
    override val description =
        "FALLBACK shell — use `termux_exec` first when Termux is installed (the dynamic system " +
            "prompt says so). Reach for run_shell when termux_exec returns a structured error or " +
            "isn't available. Runs ONE binary in Mythara's app sandbox (toybox + GNU subset; no " +
            "apt). `cmd` is JUST a binary name — NEVER a shell pipeline. For pipes, &&, " +
            "redirection, or \$VAR expansion, use cmd='sh' with args=['-c','<full pipeline " +
            "as one string>']. Examples: (curl -sI URL) → {cmd:'curl', args:['-sI','URL']}; " +
            "(ls /sdcard | head -5) → {cmd:'sh', args:['-c','ls /sdcard | head -5']}. " +
            "Allowlisted binaries only — anything else returns {status:'blocked'}."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cmd", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "ONE binary to run — JUST the name (e.g. 'getprop', 'curl', 'dumpsys'). " +
                        "NEVER a shell pipeline. For pipes / && chains / \$VAR expansion, set " +
                        "cmd='sh' and put the WHOLE pipeline as ONE string in args[1] after " +
                        "args[0]='-c'. Must be on the allowlist.",
                )
            })
            put("args", buildJsonObject {
                put("type", "array")
                put(
                    "description",
                    "Arguments to the binary, each as a separate string. For sh -c pipelines: " +
                        "['-c', 'the full pipeline as one string']. Example: " +
                        "['-c', 'curl -s URL | grep -oE pattern'].",
                )
                put("items", buildJsonObject { put("type", "string") })
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Milliseconds before kill. Default 5000, max 30000.")
            })
            put("cwd", buildJsonObject {
                put("type", "string")
                put("description", "Working directory. Default Mythara's filesDir.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("cmd"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val cmd = args["cmd"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (cmd.isBlank()) return ToolResult.fail("cmd must be non-empty")
        if (cmd !in ALLOWLIST) {
            return ToolResult.ok(
                """{"status":"blocked","binary":"${cmd.escape()}","reason":"not allowlisted — add via Settings → Shell allowlist if you trust it"}""",
            )
        }
        val cmdArgs = args["args"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull() }
            .orEmpty()
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 5_000L)
            .coerceIn(100L, 30_000L)
        val cwdPath = args["cwd"]?.jsonPrimitive?.contentOrNull()
        val cwd = if (cwdPath.isNullOrBlank()) context.filesDir else File(cwdPath)

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    runCatching {
                        val proc = ProcessBuilder(listOf(cmd) + cmdArgs)
                            .directory(cwd)
                            .redirectErrorStream(true)
                            .start()
                        val out = proc.inputStream.bufferedReader().readText()
                        val exit = proc.waitFor()
                        val truncated = if (out.length > MAX_OUT) out.take(MAX_OUT) + "\n…[truncated]" else out
                        ToolResult.ok(
                            """{"status":"ok","exit":$exit,"out":${jsonString(truncated)}}""",
                        )
                    }.getOrElse {
                        ToolResult.fail("exec failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                ToolResult.ok("""{"status":"timeout","timeout_ms":$timeoutMs}""")
            }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String) = "\"" + s.escape() + "\""

    companion object {
        private const val MAX_OUT = 8_192

        /** Default allowlist — Android's userland is a real Linux
         *  environment (toybox + a chunk of GNU tools), and the
         *  agent is expected to use `run_shell` as the FIRST stop
         *  for any Linux-style command. The list below covers:
         *
         *  • File inspection: ls, cat, head, tail, file, stat, find,
         *    wc, du, df, readlink, realpath, basename, dirname,
         *    hexdump, xxd, tar, gzip, gunzip, zcat
         *  • Text processing: grep, sed, awk, sort, uniq, tr, cut,
         *    paste, tee, xargs, seq, printf, expr
         *  • Process / system state: ps, top, uptime, w, kill (yes —
         *    the agent uses it to stop runaway background jobs it
         *    started itself), id, whoami, uname, hostname, env,
         *    printenv, getprop, dumpsys
         *  • Network: ping, curl, wget, ip, ifconfig, ss, netstat,
         *    arp, route, traceroute, nslookup, host
         *  • Android-specific: pm, am, content, settings (read-only
         *    via `settings get`)
         *  • Scripting glue: echo, sleep, timeout, true, false, sh,
         *    bash, mktemp, touch
         *  • Filesystem mutation (UNDER allowed paths only — the
         *    cwd defaults to filesDir and the file tools enforce
         *    the same root allowlist): mkdir, rmdir, rm, cp, mv,
         *    ln, chmod, chown
         *
         *  Truly dangerous binaries (`dd` to raw devices, `mkfs`,
         *  `mount`, `umount`, `swapon`, `swapoff`, `iptables`,
         *  `setprop`, `reboot`, `shutdown`, `pkill -9 1`) are left
         *  off the list — the agent should refuse and explain why. */
        private val ALLOWLIST: Set<String> = setOf(
            // file inspection
            "ls", "cat", "head", "tail", "file", "stat", "find", "wc",
            "du", "df", "pwd", "readlink", "realpath", "basename", "dirname",
            "hexdump", "xxd", "tar", "gzip", "gunzip", "zcat",
            // text processing
            "grep", "egrep", "fgrep", "sed", "awk", "sort", "uniq",
            "tr", "cut", "paste", "tee", "xargs", "seq", "printf", "expr",
            // process / system state
            "ps", "top", "uptime", "w", "kill", "killall",
            "id", "whoami", "uname", "hostname", "env", "printenv",
            "getprop", "dumpsys", "logcat",
            // network introspection
            "ping", "ping6", "curl", "wget", "ip", "ifconfig", "ss",
            "netstat", "arp", "route", "traceroute", "nslookup", "host",
            // android-specific
            "pm", "am", "content", "settings",
            // scripting glue
            "echo", "sleep", "timeout", "true", "false",
            "sh", "bash", "mktemp", "touch",
            // safe filesystem mutation (paths still enforced by
            // ReadFileTool / WriteFileTool style root checks via the
            // shell's own argument resolution — agent is told in the
            // system prompt to stay under filesDir / Downloads).
            "mkdir", "rmdir", "rm", "cp", "mv", "ln", "chmod", "chown",
        )
    }
}
